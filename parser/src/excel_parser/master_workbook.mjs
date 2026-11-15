/**
 * 생성된 기술 마스터 JSON만 읽어 출처가 있는 월별 Excel을 작성한다.
 * 원본 조직 Excel과 기준 마스터 Excel은 읽지 않는다.
 */

import fs from "node:fs/promises";
import { createReadStream } from "node:fs";
import path from "node:path";
import { homedir } from "node:os";
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";
import { createGunzip } from "node:zlib";
import { createInterface } from "node:readline";
import { createHash } from "node:crypto";

const MONTH_HEADERS = [];
for (let month = 1; month <= 12; month += 1) MONTH_HEADERS.push(`${month}월`);
const SCENARIOS = { actual: "실적", plan: "계획", estimate: "추정", mixed: "실적·추정", unknown: "미확정" };
const PERIODS = { prior: "전년 실적", plan: "당년 계획", outlook: "당년 실적·추정", next_plan: "차년 계획", next_projection: "차년 추정" };
const COLORS = { title: "#203864", header: "#29465B", pale: "#E8EEF3", text: "#182A38", note: "#526777" };

/**
 * 그룹 키를 일정한 순서로 정렬한다.
 * Args: left, right - 문자열 키가 첫 원소인 Map 항목.
 * Returns: 사전식 비교 결과.
 * Raises: 없음.
 * Side Effects: 없음.
 */
function compareEntries(left, right) {
  return left[0].localeCompare(right[0], "en");
}

/**
 * 1부터 시작하는 열 번호를 Excel 열 문자로 바꾼다.
 * Args: column - 양수 열 번호.
 * Returns: A1 표기에 사용할 열 문자.
 * Raises: Error - 유효하지 않은 번호.
 * Side Effects: 없음.
 */
function columnLabel(column) {
  if (!Number.isInteger(column) || column < 1) throw new Error("열 번호는 양수 정수여야 합니다");
  let label = "";
  while (column > 0) {
    const remainder = (column - 1) % 26;
    label = String.fromCharCode(65 + remainder) + label;
    column = Math.floor((column - 1) / 26);
  }
  return label;
}

/**
 * 유한 십진수 문자열을 손실 없는 정수 계수와 10진 지수로 분리한다.
 * Args: value - 정규화된 금액 문자열 또는 숫자.
 * Returns: coefficient BigInt, exponent 정수, signature 문자열, significantDigits.
 * Raises: Error - 비숫자·비유한 값 또는 과도한 지수.
 * Side Effects: 없음.
 */
function decimalParts(value) {
  const text = String(value).trim();
  const match = /^([+-]?)(\d+)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/.exec(text);
  if (!match) throw new Error(`유한 십진수 형식이 아닙니다: ${text}`);
  const fraction = match[3] ?? "";
  let digits = (match[2] + fraction).replace(/^0+/, "");
  let exponent = Number(match[4] ?? 0) - fraction.length;
  if (!Number.isInteger(exponent) || Math.abs(exponent) > 1000) throw new Error("금액 지수가 허용 범위를 벗어납니다");
  if (!digits) return { coefficient: 0n, exponent: 0, signature: "0", significantDigits: 1 };
  while (digits.endsWith("0")) { digits = digits.slice(0, -1); exponent += 1; }
  const sign = match[1] === "-" ? "-" : "";
  return { coefficient: BigInt(sign + digits), exponent, signature: `${sign}${digits}@${exponent}`, significantDigits: digits.length };
}

/**
 * JSON 정밀값이 Excel 숫자로 왕복 보존될 때만 typed number로 변환한다.
 * Args: value - 원화 정규화값 또는 null. location - 오류 근거 위치.
 * Returns: 유한 숫자 또는 실제 빈셀 null.
 * Raises: Error - Excel 15자리 유효숫자 또는 숫자 왕복 보존 조건 위반.
 * Side Effects: 없음. 권위 있는 JSON 값은 변경하지 않는다.
 */
function exactNumber(value, location) {
  if (value === null || value === undefined || value === "") return null;
  const original = decimalParts(value);
  const numeric = Number(value);
  if (!Number.isFinite(numeric) || original.significantDigits > 15 || decimalParts(String(numeric)).signature !== original.signature) {
    throw new Error(`Excel 숫자 정밀도 보존 실패: ${location} (${String(value)}). JSON을 기준값으로 유지합니다.`);
  }
  return numeric;
}

/**
 * 부동소수점을 쓰지 않고 금액 합계 상태를 갱신한다.
 * Args: state - 기존 계수·지수 또는 undefined. value - 정확한 금액 문자열.
 * Returns: 합계 coefficient와 exponent.
 * Raises: Error - 금액 형식 오류.
 * Side Effects: 없음. 새 합계 객체를 반환한다.
 */
function addDecimal(state, value) {
  const incoming = decimalParts(value);
  if (!state) return { coefficient: incoming.coefficient, exponent: incoming.exponent };
  const exponent = Math.min(state.exponent, incoming.exponent);
  return { coefficient: state.coefficient * 10n ** BigInt(state.exponent - exponent)
      + incoming.coefficient * 10n ** BigInt(incoming.exponent - exponent), exponent };
}

/**
 * 금액 합계 상태를 비교용 정규 십진수 표식으로 바꾼다.
 * Args: state - coefficient와 exponent가 있는 합계.
 * Returns: decimalParts와 같은 값 표식.
 * Raises: Error - 유효하지 않은 상태.
 * Side Effects: 없음.
 */
function sumSignature(state) {
  return decimalParts(`${state.coefficient.toString()}e${state.exponent}`).signature;
}

/**
 * 파일 SHA256을 스트리밍 계산한다.
 * Args: filename - 기존 입력 또는 생성 결과 파일.
 * Returns: SHA256 16진수 문자열.
 * Raises: 파일 읽기 오류.
 * Side Effects: 해당 파일만 읽는다.
 */
async function fileHash(filename) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(filename)) hash.update(chunk);
  return hash.digest("hex");
}

/**
 * 생성 상세 JSONL을 읽고 원본 행을 보존한 12개월 가로 표를 만든다.
 * Args: filename - details.jsonl.gz 경로.
 * Returns: 영역별 wide rows, 월 슬롯 수·빈값·0 계수, 정확한 합계 색인.
 * Raises: JSON·금액·월 계약 오류 또는 같은 원본 행·월의 중복.
 * Side Effects: 승인된 상세 JSONL만 읽으며 원본 Excel은 읽지 않는다.
 */
async function readDetails(filename) {
  const input = createReadStream(filename).pipe(createGunzip());
  const lines = createInterface({ input, crlfDelay: Infinity });
  const groups = new Map();
  const sums = new Map();
  const counts = { inputRecords: 0, blankAmounts: 0, numericAmounts: 0, zeroAmounts: 0, includedInTotals: 0 };
  for await (const line of lines) {
    const record = JSON.parse(line);
    counts.inputRecords += 1;
    if (!Number.isInteger(record.month) || record.month < 1 || record.month > 12) throw new Error(`월 미확정: ${record.detail_id}`);
    const sourceRow = record.source_row ?? Number(/\d+$/.exec(record.cell)?.[0]);
    if (!Number.isInteger(sourceRow)) throw new Error("출처 행 미확정");
    const identity = [record.domain, record.organization_code, record.account_code, record.detail_id,
      record.year, record.scenario, record.period_group, record.revision,
      record.source_file, record.sheet, sourceRow];
    const key = JSON.stringify(identity);
    if (!groups.has(key)) groups.set(key, { record, sourceRow, amounts: Array(12).fill(null), cells: {}, statuses: {}, seen: new Set() });
    const group = groups.get(key);
    if (group.seen.has(record.month)) throw new Error(`동일 원본 행·월 중복을 합산하지 않습니다: ${record.source_file}!${record.cell}`);
    group.seen.add(record.month);
    const numeric = exactNumber(record.normalized_value, `${record.sheet}!${record.cell}`);
    group.amounts[record.month - 1] = numeric;
    group.cells[record.month] = record.cell;
    if (typeof record.confirmed !== "boolean" || typeof record.semantic_complete !== "boolean") throw new Error("월별 금액·업무키 확정 상태가 없습니다");
    group.statuses[record.month] = { value: record.value_status, assembly: record.assembly_status,
      included: record.included_in_totals === true, confirmed: record.confirmed,
      semantic_complete: record.semantic_complete, value_kind: record.value_kind ?? null,
      mapping_status: record.mapping_status ?? null, mapping_reasons: record.mapping_reasons ?? [] };
    if (numeric === null) counts.blankAmounts += 1;
    else { counts.numericAmounts += 1; if (numeric === 0) counts.zeroAmounts += 1; }
    if (record.included_in_totals === true) {
      if (numeric === null) throw new Error("합계 포함으로 표시된 상세의 금액이 비었습니다");
      counts.includedInTotals += 1;
      for (const scope of ["company", "organization"]) {
        const aggregateKey = JSON.stringify([scope, scope === "company" ? null : record.organization_code,
          record.domain, record.account_code, record.year, record.month, record.scenario]);
        const prior = sums.get(aggregateKey);
        sums.set(aggregateKey, { ...addDecimal(prior, record.normalized_value), detailCount: (prior?.detailCount ?? 0) + 1 });
      }
    }
  }
  const output = { revenue: [], expense: [] };
  const sorted = [...groups.entries()].sort(compareEntries);
  for (const [, group] of sorted) {
    const record = group.record;
    if (!(record.domain in output)) throw new Error(`알 수 없는 영역: ${record.domain}`);
    output[record.domain].push([record.organization_code, record.account_code, record.detail_id,
      record.year, SCENARIOS[record.scenario] ?? record.scenario, PERIODS[record.period_group] ?? record.period_group,
      record.revision ?? null, ...group.amounts, record.organization_name ?? null, record.account_name ?? null,
      record.source_file, record.sheet, group.sourceRow, JSON.stringify(group.cells), JSON.stringify(group.statuses)]);
  }
  return { rows: output, counts, sums };
}

/**
 * 이미 생성된 합계를 검증하고 전사·조직별 월별 표로 전환한다.
 * Args: totals - master.json 합계 목록. sums - 상세에서 독립 확인한 합계 색인.
 * Returns: 전사·조직별 가로 행 목록과 확인 그룹 수.
 * Raises: Error - 합계·상세 수 불일치, 중복 합계 또는 숫자 정밀도 문제.
 * Side Effects: 없음. 합계를 수정하거나 기존 기준 마스터를 조회하지 않는다.
 */
function prepareTotals(totals, sums) {
  const groups = new Map();
  const verified = new Set();
  for (const record of totals) {
    const key = JSON.stringify([record.scope, record.organization_code ?? null, record.domain,
      record.account_code, record.year, record.month, record.scenario]);
    const expected = sums.get(key);
    if (!expected || expected.detailCount !== record.detail_count || sumSignature(expected) !== decimalParts(record.normalized_value).signature) throw new Error(`JSON 상세와 합계 불일치: ${key}`);
    if (verified.has(key)) throw new Error(`중복 합계: ${key}`);
    verified.add(key);
    const wideKey = JSON.stringify([record.scope, record.organization_code ?? null, record.domain, record.account_code, record.year, record.scenario]);
    if (!groups.has(wideKey)) groups.set(wideKey, { record, amounts: Array(12).fill(null), detailCount: 0, monthCounts: {} });
    const group = groups.get(wideKey);
    group.amounts[record.month - 1] = exactNumber(record.normalized_value, key);
    group.monthCounts[record.month] = record.detail_count;
    group.detailCount += record.detail_count;
  }
  if (verified.size !== sums.size) throw new Error("상세 합계 일부가 master.json 합계에 없습니다");
  const rows = { company: [], organization: [] };
  for (const [, group] of [...groups.entries()].sort(compareEntries)) {
    const record = group.record;
    const fields = [record.domain === "revenue" ? "매출" : "비용", record.account_code, record.year,
      SCENARIOS[record.scenario] ?? record.scenario, ...group.amounts, group.detailCount, JSON.stringify(group.monthCounts)];
    if (record.scope === "organization") fields.unshift(record.organization_code);
    if (!(record.scope in rows)) throw new Error(`알 수 없는 합계 범위: ${record.scope}`);
    rows[record.scope].push(fields);
  }
  return { rows, verifiedGroups: verified.size };
}

/**
 * 승인된 실행 폴더에서 런타임 패키지의 공개 진입점을 연결한다.
 * Args: monthlyDir - 승인된 월별 결과 폴더.
 * Returns: Workbook와 SpreadsheetFile 공개 API.
 * Raises: Error - 런타임 부재 또는 기존 symlink의 다른 대상.
 * Side Effects: 실행 루트의 node_modules symlink만 필요할 때 생성한다.
 */
async function loadArtifactTool(monthlyDir) {
  let outputRoot = monthlyDir;
  while (path.basename(path.dirname(outputRoot)) !== "comparisons") {
    const parent = path.dirname(outputRoot);
    if (parent === outputRoot) throw new Error("승인 comparisons 실행 폴더를 찾을 수 없습니다");
    outputRoot = parent;
  }
  const runtimeModules = path.join(homedir(), ".cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules");
  const link = path.join(outputRoot, "node_modules");
  try { await fs.symlink(runtimeModules, link, "dir"); }
  catch (error) {
    if (error.code !== "EEXIST") throw error;
    if (await fs.realpath(link) !== await fs.realpath(runtimeModules)) throw new Error("기존 node_modules가 승인된 런타임을 가리키지 않습니다");
  }
  const requireFromOutput = createRequire(path.join(monthlyDir, "package.json"));
  return import(pathToFileURL(requireFromOutput.resolve("@oai/artifact-tool")).href);
}

/**
 * 표 한 개를 블록으로 작성하고 읽기 쉬운 서식과 필터를 적용한다.
 * Args: workbook - 빈 통합문서. definition - 제목·헤더·행·월 열 범위. context - 유형·마감월·발행 및 원본 감사 상태.
 * Returns: 시트 객체와 검증용 행·열 경계.
 * Raises: 도구의 범위·서식·표 생성 오류.
 * Side Effects: 메모리 통합문서의 해당 시트만 생성·편집한다.
 */
function addSheet(workbook, definition, context) {
  const sheet = workbook.worksheets.add(definition.name);
  const width = definition.headers.length;
  const endColumn = columnLabel(width);
  const lastRow = Math.max(6, definition.rows.length + 5);
  sheet.showGridLines = false;
  sheet.tabColor = definition.detail ? "#8CA7BB" : COLORS.title;
  sheet.getRange(`A1:${endColumn}${lastRow}`).format.font = { name: "Arial", size: 10, color: COLORS.text };
  sheet.getRange(`A1:${endColumn}${lastRow}`).format.verticalAlignment = "center";
  sheet.getRange(`A1:${endColumn}${lastRow}`).format.rowHeight = 19;
  sheet.getRange("A2").values = [[definition.title]];
  sheet.getRange("A2").format.font = { name: "Arial", size: 14, bold: true, color: COLORS.title };
  sheet.getRange(`A2:${endColumn}2`).format.borders = { bottom: { style: "thin", color: "#8CA7BB" } };
  sheet.getRange("A3").values = [[`${context.type} · 마감 ${context.closing_month} · 금액 단위 원`]];
  sheet.getRange("A3").format.font = { name: "Arial", size: 10, italic: true, color: COLORS.note };
  const status = `출력 상태 ${context.publication_status ?? context.status} · 원본 품질 ${context.source_quality?.status ?? context.status} · 조립 상태 ${context.status} · 기대 조직 ${context.expected_organization_count} · 반영 조직 ${context.selected_organization_count}`;
  const note = definition.detail ? " · 빈칸과 0원을 구분합니다. 해당 월의 원본 셀과 금액 상태는 오른쪽 출처 열에서 확인할 수 있습니다." : " · 출력 완료는 기준 정답과의 일치 여부를 뜻하지 않습니다.";
  sheet.getRange("A4").values = [[status + note]];
  sheet.getRange("A4").format.font = { name: "Arial", size: 10, color: COLORS.note };
  sheet.getRange(`A5:${endColumn}5`).values = [definition.headers];
  for (let offset = 0; offset < definition.rows.length; offset += 1000) {
    const block = definition.rows.slice(offset, offset + 1000);
    sheet.getRangeByIndexes(offset + 5, 0, block.length, width).values = block;
  }
  const table = sheet.tables.add(`A5:${endColumn}${lastRow}`, true, definition.tableName);
  table.showFilterButton = true;
  table.style = "TableStyleMedium2";
  sheet.getRange(`A5:${endColumn}5`).format = { fill: COLORS.header,
    font: { name: "Arial", size: 10, bold: true, color: "#FFFFFF" }, horizontalAlignment: "center", verticalAlignment: "center", rowHeight: 27 };
  sheet.getRange(`A6:${endColumn}${lastRow}`).format.rowHeight = 19;
  sheet.getRange(`A1:${endColumn}${lastRow}`).format.wrapText = false;
  sheet.getRange(`A1:${endColumn}${lastRow}`).format.columnWidth = 13;
  const monthStart = columnLabel(definition.monthColumn);
  const monthEnd = columnLabel(definition.monthColumn + 11);
  sheet.getRange(`${monthStart}6:${monthEnd}${lastRow}`).format.numberFormat = "#,##0;[Red](#,##0);0";
  sheet.getRange(`${monthStart}6:${monthEnd}${lastRow}`).format.horizontalAlignment = "right";
  sheet.getRange(`${monthStart}1:${monthEnd}${lastRow}`).format.columnWidth = 16;
  if (definition.detail) {
    sheet.getRange(`C1:C${lastRow}`).format.columnWidth = 34;
    sheet.getRange(`F1:F${lastRow}`).format.columnWidth = 18;
    sheet.getRange(`T1:U${lastRow}`).format.columnWidth = 24;
    sheet.getRange(`V1:V${lastRow}`).format.columnWidth = 70;
    sheet.getRange(`W1:W${lastRow}`).format.columnWidth = 27;
    sheet.getRange(`Y1:Z${lastRow}`).format.columnWidth = 60;
    sheet.getRange(`A6:C${lastRow}`).format.numberFormat = "@";
  } else {
    sheet.getRange(`A1:A${lastRow}`).format.columnWidth = 15;
    sheet.getRange(`B1:B${lastRow}`).format.columnWidth = 15;
    sheet.getRange(`${endColumn}1:${endColumn}${lastRow}`).format.columnWidth = 60;
  }
  sheet.freezePanes.freezeRows(5);
  sheet.freezePanes.freezeColumns(definition.detail ? 3 : definition.scope === "organization" ? 1 : 0);
  return { sheet, name: definition.name, rows: definition.rows, lastRow, width, monthColumn: definition.monthColumn,
    header: definition.headers, contextTitle: `${context.type} · 마감 ${context.closing_month} · 금액 단위 원`, statusNote: status + note };
}

/**
 * 모든 작성 행의 typed 값·빈셀·헤더·생성 문맥과 상태를 다시 읽어 확인한다.
 * Args: written - 작성 시트와 원래 2차원 입력 행.
 * Returns: 검증한 시트·행·셀 수.
 * Raises: Error - 셀값·타입·빈칸 보존 불일치.
 * Side Effects: 메모리 통합문서 값을 읽기만 한다.
 */
function verifyWritten(written) {
  let checkedCells = 0;
  if (written.sheet.getRange("A3").values[0]?.[0] !== written.contextTitle || written.sheet.getRange("A4").values[0]?.[0] !== written.statusNote) throw new Error(`생성 문맥·상태 왕복 불일치: ${written.name}`);
  const observedHeader = written.sheet.getRangeByIndexes(4, 0, 1, written.width).values[0];
  for (let column = 0; column < written.width; column += 1) {
    if (observedHeader?.[column] !== written.header[column]) throw new Error(`헤더 왕복 불일치: ${written.name}`);
  }
  for (let offset = 0; offset < written.rows.length; offset += 1000) {
    const expected = written.rows.slice(offset, offset + 1000);
    const actual = written.sheet.getRangeByIndexes(offset + 5, 0, expected.length, written.width).values;
    for (let row = 0; row < expected.length; row += 1) {
      for (let column = 0; column < written.width; column += 1) {
        const value = expected[row][column] ?? null;
        const observed = actual[row]?.[column] ?? null;
        if (value !== observed) throw new Error(`작성 값 왕복 불일치: ${written.name}!${columnLabel(column + 1)}${offset + row + 6}`);
        checkedCells += 1;
      }
    }
  }
  return { sheet: written.name, dataRows: written.rows.length, checkedCells, headerCellsVerified: written.width, contextAndStatusVerified: true };
}

/**
 * 월별 JSON에서 마스터 Excel과 선택적 검토 이미지를 신규 생성한다.
 * Args: process.argv[2] - 월별 생성 폴더. --preview - 각 시트 머리 범위 PNG 저장.
 * Returns: 없음. 완료 요약을 콘솔에 출력한다.
 * Raises: 입력·정밀도·합계·도구 오류 또는 기존 결과 덮어쓰기 시도.
 * Side Effects: 생성 JSON 두 개를 읽고 master.xlsx·검증 JSON·선택적 PNG를 쓴다.
 */
async function main() {
  const argument = process.argv[2];
  if (!argument || argument.startsWith("--")) throw new Error("사용법: master_workbook.mjs <monthly_dir> [--preview]");
  const monthlyDir = await fs.realpath(argument);
  if (monthlyDir.toLowerCase().split(path.sep).includes("data")) throw new Error("Data에는 작성할 수 없습니다");
  const preview = process.argv.includes("--preview");
  const outputPath = path.join(monthlyDir, "master.xlsx");
  const verificationPath = path.join(monthlyDir, "workbook_verification.json");
  for (const target of [outputPath, verificationPath]) {
    try { await fs.access(target); throw new Error(`기존 산출물을 덮어쓰지 않습니다: ${target}`); }
    catch (error) { if (error.code !== "ENOENT") throw error; }
  }
  const detailsPath = path.join(monthlyDir, "details.jsonl.gz");
  const metadataPath = path.join(monthlyDir, "master.json");
  const metadata = JSON.parse(await fs.readFile(metadataPath, "utf8"));
  const inputHashes = { details: await fileHash(detailsPath), metadata: await fileHash(metadataPath) };
  const detail = await readDetails(detailsPath);
  if (detail.counts.inputRecords !== metadata.detail_count || detail.counts.includedInTotals !== metadata.confirmed_detail_count) throw new Error("master.json 상세 수가 입력과 다릅니다");
  const totals = prepareTotals(metadata.totals, detail.sums);
  const { Workbook, SpreadsheetFile, FileBlob } = await loadArtifactTool(monthlyDir);
  const workbook = Workbook.create();
  const detailHeaders = ["조직코드", "계정코드", "상세 ID", "연도", "구분", "기간 블록", "수정번호",
    ...MONTH_HEADERS, "조직명", "계정명", "원본 파일", "원본 시트", "원본 행", "월별 셀 주소", "월별 금액 상태"];
  const definitions = [
    { name: "전사월별합계", title: "전사 월별 합계", scope: "company", tableName: "CompanyMonthly",
      headers: ["영역", "계정코드", "연도", "구분", ...MONTH_HEADERS, "합산 상세 수", "월별 상세 수"], rows: totals.rows.company, monthColumn: 5 },
    { name: "조직월별합계", title: "조직별 월별 합계", scope: "organization", tableName: "OrganizationMonthly",
      headers: ["조직코드", "영역", "계정코드", "연도", "구분", ...MONTH_HEADERS, "합산 상세 수", "월별 상세 수"], rows: totals.rows.organization, monthColumn: 6 },
    { name: "매출상세", title: "조직별 매출 상세", tableName: "RevenueDetails", detail: true,
      headers: detailHeaders, rows: detail.rows.revenue, monthColumn: 8 },
    { name: "비용상세", title: "조직별 비용 상세", tableName: "ExpenseDetails", detail: true,
      headers: detailHeaders, rows: detail.rows.expense, monthColumn: 8 },
  ];
  const written = [];
  for (const definition of definitions) written.push(addSheet(workbook, definition, metadata));
  workbook.recalculate();
  const verified = written.map(verifyWritten);
  const inspections = [];
  for (const definition of written) {
    const check = await workbook.inspect({ kind: "table", range: `${definition.name}!A5:L10`, include: "values,formulas", tableMaxRows: 6, tableMaxCols: 12, maxChars: 2500 });
    inspections.push({ sheet: definition.name, head: check.ndjson });
    if (preview) {
      const imagePath = path.join(monthlyDir, `preview-${definition.name}.png`);
      const image = await workbook.render({ sheetName: definition.name, range: "A1:L12", scale: 1.3, format: "png" });
      await fs.writeFile(imagePath, new Uint8Array(await image.arrayBuffer()), { flag: "wx" });
    }
  }
  const formulaErrors = await workbook.inspect({ kind: "match", searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!", options: { useRegex: true, maxResults: 30 }, maxChars: 2000 });
  const xlsx = await SpreadsheetFile.exportXlsx(workbook);
  await xlsx.save(outputPath);
  const reopened = await SpreadsheetFile.importXlsx(await FileBlob.load(outputPath));
  const reopenedChecks = [];
  for (const expected of written) {
    const sheet = reopened.worksheets.getItem(expected.name);
    reopenedChecks.push(verifyWritten({ ...expected, sheet }));
  }
  const unchanged = inputHashes.details === await fileHash(detailsPath) && inputHashes.metadata === await fileHash(metadataPath);
  if (!unchanged) throw new Error("Excel 작성 중 생성 JSON이 바뀌었습니다");
  const verification = { createdAt: new Date().toISOString(), workbookContractVersion: "source-master-xlsx-v2", masterExcelReads: 0, sourceExcelReads: 0,
    authoritativeInputs: { details: detailsPath, metadata: metadataPath, sha256: inputHashes },
    inputPreserved: unchanged, counts: detail.counts, totalsVerified: totals.verifiedGroups,
    worksheetChecks: verified, exportedFileReadbackChecks: reopenedChecks,
    precisionPolicy: "15 significant decimal digits and Number.toString roundtrip; JSON remains authoritative",
    blankPolicy: "null remains empty; real numeric zero is stored and displayed as 0; absent scenario months distinguished by source-cell map",
    wideGroupingPolicy: "organization/account/detail/year/scenario/period_group/revision/source_file/sheet/source_row",
    formulaErrorScan: formulaErrors.ndjson, inspections, xlsxSha256: await fileHash(outputPath), previews: preview };
  await fs.writeFile(verificationPath, JSON.stringify(verification, null, 2) + "\n", { flag: "wx" });
  console.log(JSON.stringify({ output: outputPath, verification: verificationPath, rows: verified, totalsVerified: totals.verifiedGroups, inputPreserved: unchanged }));
}

await main();
