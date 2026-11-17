/**
 * 완료된 generation.json을 기준으로 생성 JSON의 Excel 내보내기를 일괄 실행한다.
 * 원본 Excel·기준 마스터·평가기 호출 없이 기존 생성 JSON을 보존한다.
 */

import fs from "node:fs/promises";
import { createReadStream } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { setTimeout as waitForPoll } from "node:timers/promises";

const SHEETS = ["전사월별합계", "조직월별합계", "매출상세", "비용상세"];

/**
 * 기존 파일의 SHA256을 스트리밍 계산한다.
 * Args: filename - 읽기 전용 파일 경로.
 * Returns: SHA256 16진수 문자열.
 * Raises: 파일 읽기 오류.
 * Side Effects: 파일만 읽는다.
 */
async function fileHash(filename) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(filename)) hash.update(chunk);
  return hash.digest("hex");
}

/**
 * 경로가 존재하는지 확인하고 부재 이외의 오류는 전파한다.
 * Args: filename - 검사할 경로.
 * Returns: 경로 존재 여부.
 * Raises: 권한·입출력 오류.
 * Side Effects: 파일 메타데이터만 읽는다.
 */
async function exists(filename) {
  try { await fs.access(filename); return true; }
  catch (error) { if (error.code === "ENOENT") return false; throw error; }
}

/**
 * 일괄 실행 인자를 엄격하게 읽는다.
 * Args: argv - 실행 파일 이름을 제외한 인자 목록.
 * Returns: 생성 폴더, 1 또는 2 workers, preview 및 watch 여부.
 * Raises: Error - 필수 경로 또는 옵션 오류.
 * Side Effects: 없음.
 */
function parseArguments(argv) {
  if (!argv[0] || argv[0].startsWith("--")) throw new Error("사용법: export_masters.mjs <generation_dir> [--workers 1|2] [--preview] [--watch]");
  const options = { directory: argv[0], workers: 1, preview: false, watch: false };
  for (let index = 1; index < argv.length; index += 1) {
    if (argv[index] === "--preview") options.preview = true;
    else if (argv[index] === "--watch") options.watch = true;
    else if (argv[index] === "--workers") {
      options.workers = Number(argv[index + 1]);
      index += 1;
    } else throw new Error("알 수 없는 옵션: " + argv[index]);
  }
  if (![1, 2].includes(options.workers)) throw new Error("동시 실행은 메모리 보존을 위해 1 또는 2만 허용합니다");
  return options;
}

/**
 * 생성 완료 manifest의 최소 계약과 중복 월폴더를 확인한다.
 * Args: generation - generation.json에서 읽은 객체.
 * Returns: 검증된 원래 객체.
 * Raises: Error - 미완료·원본 마스터 접근·중복 월폴더.
 * Side Effects: 없음.
 */
function validateGeneration(generation) {
  if (!Array.isArray(generation.batches) || generation.batches.length === 0 || !Number.isFinite(generation.generation_seconds)) throw new Error("완료된 generation.json이 필요합니다");
  if (generation.reference_masters_read !== 0) throw new Error("원본 마스터를 읽지 않고 생성한 실행만 처리합니다");
  const directories = new Set();
  for (const batch of generation.batches) {
    if (directories.has(batch.directory)) throw new Error("generation.json 월폴더가 중복되었습니다");
    directories.add(batch.directory);
  }
  return generation;
}

/**
 * 생성 프로세스가 월별 JSON 저장을 마친 폴더만 발견한다.
 * Args: directory - 승인된 생성 실행 폴더.
 * Returns: 파싱이 끝난 master.json과 이미 닫힌 details의 해시를 가진 월 항목.
 * Raises: 디렉터리·완성 파일 읽기 오류. 작성 중 JSON은 다음 순회로 미룬다.
 * Side Effects: 생성 폴더의 이름·JSON·압축 상세 해시만 읽는다.
 */
async function discoverReady(directory) {
  const ready = [];
  for (const type of await fs.readdir(directory, { withFileTypes: true })) {
    if (!type.isDirectory() || !/^\d{2}_/.test(type.name)) continue;
    const typeDirectory = path.join(directory, type.name);
    for (const month of await fs.readdir(typeDirectory, { withFileTypes: true })) {
      if (!month.isDirectory() || !/^\d{4}-\d{2}$/.test(month.name)) continue;
      const monthly = path.join(typeDirectory, month.name);
      const metadataPath = path.join(monthly, "master.json");
      const detailsPath = path.join(monthly, "details.jsonl.gz");
      if (!(await exists(metadataPath)) || !(await exists(detailsPath))) continue;
      let metadata;
      try { metadata = JSON.parse(await fs.readFile(metadataPath, "utf8")); }
      catch (error) { if (error instanceof SyntaxError) continue; throw error; }
      if (metadata.generated_before_reference_read !== true || !metadata.generated_at_utc
        || !Number.isInteger(metadata.detail_count) || !Array.isArray(metadata.totals)) continue;
      ready.push({ type: type.name, closing_month: month.name, directory: monthly,
        master_details_sha256: await fileHash(detailsPath) });
    }
  }
  return ready;
}

/**
 * 월별 생성 완료를 관찰하며 최대 두 작성기를 실행하고 최종 manifest와 다시 대조한다.
 * Args: context - 승인 경로·작성기·로그 설정. manifestPath - 최종 생성 manifest 경로.
 * Returns: 최종 generation 객체와 그 순서의 월별 결과, watch 중 추가 발견 폴더.
 * Raises: manifest 계약·생성 파일 읽기 오류.
 * Side Effects: 승인된 월별 Excel·검증·로그만 생성하며 입력은 변경하지 않는다.
 */
async function watchGeneration(context, manifestPath) {
  const completed = new Map();
  let generation;
  while (!generation) {
    const pending = [];
    for (const batch of await discoverReady(context.directory)) if (!completed.has(batch.directory)) pending.push(batch);
    let nextIndex = 0;

    /**
     * 발견된 생성 완료 월 목록을 하나씩 가져와 병렬 작업 간 중복 없이 내보낸다.
     * Args: 없음. 상위 pending·context·completed를 읽는다.
     * Returns: 현재 발견 묶음을 모두 처리한 Promise.
     * Raises: 진행 콘솔 출력의 시스템 오류.
     * Side Effects: 승인된 월별 작성·검증·로그를 수행하고 완료 목록을 갱신한다.
     */
    async function watchWorker() {
      while (nextIndex < pending.length) {
        const batch = pending[nextIndex];
        nextIndex += 1;
        const result = await exportBatch(batch, context);
        completed.set(batch.directory, result);
        console.log(`xlsx watch ${completed.size}: ${result.type}/${result.closingMonth} ${result.status}`);
      }
    }

    const workers = [];
    for (let index = 0; index < context.workers; index += 1) workers.push(watchWorker());
    await Promise.all(workers);
    if (await exists(manifestPath)) {
      try { generation = validateGeneration(JSON.parse(await fs.readFile(manifestPath, "utf8"))); }
      catch (error) { if (!(error instanceof SyntaxError)) throw error; }
    }
    if (!generation) await waitForPoll(5000);
  }
  const results = [];
  const finalDirectories = new Set();
  for (const batch of generation.batches) {
    finalDirectories.add(batch.directory);
    let result = completed.get(batch.directory);
    if (!result) result = await exportBatch(batch, context);
    if (result.status !== "failed") {
      const detailHash = await fileHash(path.join(batch.directory, "details.jsonl.gz"));
      result.finalManifestValidation = await verifyExisting(batch.directory, context.preview);
      result.finalManifestDetailsMatch = detailHash === batch.master_details_sha256;
      if (!result.finalManifestDetailsMatch || !result.finalManifestValidation.valid) {
        result.status = "failed";
        result.error = { name: "FinalManifestMismatch", message: "최종 생성 manifest와 내보내기 입력·검증 결과가 일치하지 않습니다" };
        await fs.appendFile(result.log, JSON.stringify({ stage: "final_manifest_failed", error: result.error }) + "\n");
      }
    }
    results.push(result);
  }
  const unexpectedDirectories = [];
  for (const directory of completed.keys()) if (!finalDirectories.has(directory)) unexpectedDirectories.push(directory);
  return { generation, results, unexpectedDirectories };
}

/**
 * 작성 직후와 저장 파일 재읽기의 모든 시트 검사 계수가 일치하는지 확인한다.
 * Args: verification - 작성기가 남긴 검증 JSON.
 * Returns: 네 시트의 행·셀 수와 재읽기 검증 완료 여부.
 * Raises: 없음.
 * Side Effects: 없음.
 */
function readbackComplete(verification) {
  const authored = verification.worksheetChecks;
  const reopened = verification.exportedFileReadbackChecks;
  if (!Array.isArray(authored) || !Array.isArray(reopened) || authored.length !== 4 || reopened.length !== 4) return false;
  for (const sheet of SHEETS) {
    let first = null;
    let second = null;
    for (const row of authored) if (row.sheet === sheet) first = row;
    for (const row of reopened) if (row.sheet === sheet) second = row;
    if (!first || !second || !Number.isInteger(first.dataRows) || first.dataRows < 0
      || !Number.isInteger(first.checkedCells) || first.checkedCells < 0
      || first.dataRows !== second.dataRows || first.checkedCells !== second.checkedCells) return false;
  }
  return true;
}

/**
 * 기존 Excel이 현재 생성 JSON과 일치하는 검증 완료 산출물인지 확인한다.
 * Args: directory - 생성 월폴더. preview - PNG도 필요할 때 True.
 * Returns: valid, reason, 현재 해시와 검증 요약. 실패는 건너뛰지 않는다.
 * Raises: 현재 생성 JSON 읽기·해시 오류.
 * Side Effects: 생성 JSON·Excel·검증 JSON·PNG 존재 여부만 읽는다.
 */
async function verifyExisting(directory, preview) {
  const xlsxPath = path.join(directory, "master.xlsx");
  const verificationPath = path.join(directory, "workbook_verification.json");
  if (!(await exists(xlsxPath)) || !(await exists(verificationPath))) return { valid: false, reason: "workbook_or_verification_missing" };
  let verification;
  try { verification = JSON.parse(await fs.readFile(verificationPath, "utf8")); }
  catch (error) { return { valid: false, reason: "verification_unreadable", error: String(error.message) }; }
  const metadataPath = path.join(directory, "master.json");
  const detailsPath = path.join(directory, "details.jsonl.gz");
  const metadata = JSON.parse(await fs.readFile(metadataPath, "utf8"));
  const hashes = { xlsx: await fileHash(xlsxPath), metadata: await fileHash(metadataPath), details: await fileHash(detailsPath) };
  if (hashes.xlsx !== verification.xlsxSha256 || hashes.metadata !== verification.authoritativeInputs?.sha256?.metadata
    || hashes.details !== verification.authoritativeInputs?.sha256?.details) return { valid: false, reason: "artifact_or_input_hash_mismatch", hashes };
  if (path.resolve(verification.authoritativeInputs?.metadata ?? "") !== metadataPath
    || path.resolve(verification.authoritativeInputs?.details ?? "") !== detailsPath) return { valid: false, reason: "verification_input_paths_mismatch", hashes };
  if (verification.inputPreserved !== true || verification.masterExcelReads !== 0 || verification.sourceExcelReads !== 0
    || !readbackComplete(verification) || verification.counts?.inputRecords !== metadata.detail_count
    || verification.counts?.includedInTotals !== metadata.confirmed_detail_count
    || verification.totalsVerified !== metadata.totals.length) return { valid: false, reason: "verification_completion_contract_failed", hashes };
  if (preview) {
    for (const sheet of SHEETS) if (!(await exists(path.join(directory, `preview-${sheet}.png`)))) return { valid: false, reason: "requested_preview_missing", hashes };
  }
  return { valid: true, reason: "verified_existing_workbook", hashes,
    counts: verification.counts, worksheetChecks: verification.worksheetChecks, totalsVerified: verification.totalsVerified };
}

/**
 * 불완전한 기존 작성기 산출물을 복구 폴더로 이동 보존한다.
 * Args: directory - 월폴더. recoveryDirectory - 이번 시도의 별도 복구 폴더.
 * Returns: 이동한 파일의 이전·보존 경로 목록.
 * Raises: 파일 이동 또는 폴더 생성 오류.
 * Side Effects: 알려진 작성기 결과만 이동한다. JSON 입력과 원본은 변경하지 않는다.
 */
async function preserveIncomplete(directory, recoveryDirectory) {
  const names = ["master.xlsx", "master.xlsx.inspect.ndjson", "workbook_verification.json"];
  for (const sheet of SHEETS) names.push(`preview-${sheet}.png`);
  const moved = [];
  for (const name of names) {
    const source = path.join(directory, name);
    if (!(await exists(source))) continue;
    if (moved.length === 0) await fs.mkdir(recoveryDirectory, { recursive: true });
    const target = path.join(recoveryDirectory, name);
    if (await exists(target)) throw new Error("기존 복구 사본을 덮어쓰지 않습니다: " + target);
    await fs.rename(source, target);
    moved.push({ source, preservedAt: target });
  }
  return moved;
}

/**
 * 월별 작성기를 별도 프로세스로 실행하고 stdout·stderr를 한 로그에 보존한다.
 * Args: writer - master_workbook.mjs 경로. directory - 월폴더. preview - PNG 요청.
 *       logPath - 새 로그 경로. reason - 실행 또는 복구 사유.
 * Returns: 종료 코드, 종료 신호, 실제 명령과 경과 시간.
 * Raises: 프로세스 시작·로그 쓰기 오류.
 * Side Effects: 자식 작성기가 승인된 월별 산출물을 만들고 이 함수는 로그만 쓴다.
 */
async function runWriter(writer, directory, preview, logPath, reason) {
  const arguments_ = [writer, directory];
  if (preview) arguments_.push("--preview");
  const log = await fs.open(logPath, "wx");
  const started = Date.now();
  const command = [process.execPath, ...arguments_];
  try {
    await log.write(JSON.stringify({ stage: "start", at: new Date().toISOString(), reason, command }) + "\n");
    const child = spawn(process.execPath, arguments_, { stdio: ["ignore", log.fd, log.fd] });
    const [exitCode, signal] = await once(child, "close");
    const result = { exitCode, signal, command, seconds: (Date.now() - started) / 1000 };
    await log.write("\n" + JSON.stringify({ stage: "finish", at: new Date().toISOString(), ...result }) + "\n");
    return result;
  } finally { await log.close(); }
}

/**
 * 한 월 산출물을 검증하여 건너뛰거나 보존 후 신규 작성한다.
 * Args: batch - generation.json의 월별 항목. context - 실행 경계와 로그 경로.
 * Returns: 월별 성공·실패·복구·건너뛰기와 검증 근거.
 * Raises: 없음. 월별 실패를 결과에 남겨 다른 월 처리를 계속한다.
 * Side Effects: 승인된 생성물과 로그만 작성·이동하고 JSON 입력은 보존한다.
 */
async function exportBatch(batch, context) {
  const label = `${batch.type}-${batch.closing_month}`;
  const result = { type: batch.type, closingMonth: batch.closing_month, directory: batch.directory,
    startedAt: new Date().toISOString(), log: path.join(context.logs, label + ".log") };
  try {
    const directory = await fs.realpath(batch.directory);
    const relative = path.relative(context.directory, directory);
    if (!relative || relative.startsWith("..") || path.isAbsolute(relative)
      || directory.toLowerCase().split(path.sep).includes("data")) throw new Error("생성 실행 폴더 밖의 월폴더를 처리하지 않습니다");
    if (await fileHash(path.join(directory, "details.jsonl.gz")) !== batch.master_details_sha256) throw new Error("generation.json의 고정 상세 해시가 달라졌습니다");
    const existing = await verifyExisting(directory, context.preview);
    result.previousValidation = existing;
    if (existing.valid) {
      result.status = "skipped_verified";
      result.validation = existing;
      await fs.writeFile(result.log, JSON.stringify({ stage: "skipped_verified", at: new Date().toISOString(), validation: existing }) + "\n", { flag: "wx" });
      return result;
    }
    result.preservedArtifacts = await preserveIncomplete(directory, path.join(context.recovery, label));
    result.execution = await runWriter(context.writer, directory, context.preview, result.log, existing.reason);
    if (result.execution.exitCode !== 0) throw new Error(`작성기 종료 코드 ${result.execution.exitCode}, 신호 ${result.execution.signal}`);
    result.validation = await verifyExisting(directory, context.preview);
    if (!result.validation.valid) throw new Error("작성 후 검증 실패: " + result.validation.reason);
    result.status = "exported";
  } catch (error) {
    result.status = "failed";
    result.error = { name: error.name, message: error.message };
    await fs.appendFile(result.log, JSON.stringify({ stage: "failed", at: new Date().toISOString(), error: result.error }) + "\n");
  } finally { result.finishedAt = new Date().toISOString(); }
  return result;
}

/**
 * 승인된 최신 내보내기 요약만 갱신하고 직전 요약을 보존한다.
 * Args: directory - 생성 실행 폴더. summary - 새 실행 결과. attempt - 유일 시도 ID.
 * Returns: 최신 요약과 불변 시도별 요약 경로.
 * Raises: 요약 저장 또는 이전 요약 보존 오류.
 * Side Effects: export-summary 관련 파일만 쓴다. 파서 점수·계획·입력은 변경하지 않는다.
 */
async function saveSummary(directory, summary, attempt) {
  const text = JSON.stringify(summary, null, 2) + "\n";
  const unique = path.join(directory, `export-summary-${attempt}.json`);
  const current = path.join(directory, "export-summary.json");
  await fs.writeFile(unique, text, { flag: "wx" });
  if (await exists(current)) await fs.rename(current, path.join(directory, `export-summary-before-${attempt}.json`));
  await fs.writeFile(current, text, { flag: "wx" });
  return { current, unique };
}

/**
 * 완료된 생성 실행의 Excel을 최대 두 프로세스로 내보내고 감사 요약을 저장한다.
 * Args: process.argv - 생성 폴더, --workers 1|2, 선택 --preview·--watch.
 * Returns: 성공 0, 월별 실패 또는 구현 변경이 있으면 2.
 * Raises: 생성 미완료·경로·로그 디렉터리·최종 요약 오류.
 * Side Effects: 월별 작성기와 승인된 로그·복구·내보내기 요약만 생성한다.
 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const directory = await fs.realpath(options.directory);
  if (directory.toLowerCase().split(path.sep).includes("data")) throw new Error("Data 경로를 처리하지 않습니다");
  const manifestPath = path.join(directory, "generation.json");
  let generation = options.watch ? null : validateGeneration(JSON.parse(await fs.readFile(manifestPath, "utf8")));
  const attempt = new Date().toISOString().replace(/[:.]/g, "-") + "-" + process.pid;
  const logs = path.join(directory, "export-logs", attempt);
  await fs.mkdir(logs, { recursive: true });
  const writer = fileURLToPath(new URL("./master_workbook.mjs", import.meta.url));
  const writerHash = await fileHash(writer);
  let manifestHash = options.watch ? null : await fileHash(manifestPath);
  const started = Date.now();
  const context = { directory, logs, recovery: path.join(directory, "export-recovery", attempt), writer, preview: options.preview, workers: options.workers };
  let results = generation ? Array(generation.batches.length) : [];
  let unexpectedDirectories = [];
  let nextIndex = 0;
  let completed = 0;

  /**
   * 공유 대기열에서 한 번에 하나의 월 작성 작업을 순차 수행한다.
   * Args: 없음. 상위 실행의 batches와 context를 읽는다.
   * Returns: 작업자 완료 Promise.
   * Raises: 진행 콘솔 출력의 시스템 오류.
   * Side Effects: 월별 작성·로그를 수행하고 공유 결과 배열을 갱신한다.
   */
  async function worker() {
    while (nextIndex < generation.batches.length) {
      const index = nextIndex;
      nextIndex += 1;
      results[index] = await exportBatch(generation.batches[index], context);
      completed += 1;
      const row = results[index];
      console.log(`xlsx ${completed}/${results.length}: ${row.type}/${row.closingMonth} ${row.status}`);
    }
  }

  if (options.watch) {
    const watched = await watchGeneration(context, manifestPath);
    generation = watched.generation;
    results = watched.results;
    unexpectedDirectories = watched.unexpectedDirectories;
    manifestHash = await fileHash(manifestPath);
  } else {
    const workers = [];
    for (let index = 0; index < options.workers; index += 1) workers.push(worker());
    await Promise.all(workers);
  }
  const counts = { exported: 0, skipped_verified: 0, failed: 0 };
  for (const result of results) counts[result.status] += 1;
  const sourceUnchanged = writerHash === await fileHash(writer) && manifestHash === await fileHash(manifestPath);
  const summary = { contractVersion: "technical-master-xlsx-export-v1", attempt, command: process.argv,
    startedAt: new Date(started).toISOString(), completedAt: new Date().toISOString(), seconds: (Date.now() - started) / 1000,
    generation: manifestPath, generationSha256: manifestHash, writerSha256: writerHash,
    workers: options.workers, preview: options.preview, watch: options.watch, sourceUnchanged,
    originalExcelReadsByExporter: 0, artifactOperationMarkerAlreadyStarted: true,
    expectedWorkbooks: generation.batches.length, counts, unexpectedDirectories,
    complete: counts.failed === 0 && sourceUnchanged && unexpectedDirectories.length === 0, results };
  const saved = await saveSummary(directory, summary, attempt);
  console.log(JSON.stringify({ complete: summary.complete, counts, expectedWorkbooks: summary.expectedWorkbooks, ...saved }));
  return summary.complete ? 0 : 2;
}

process.exitCode = await main();
