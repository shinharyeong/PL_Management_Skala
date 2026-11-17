"""월별 원본 컨텍스트 내보내기와 정답 격리 오프라인 비교를 실행한다."""

from __future__ import annotations

import csv
import gzip
import json
import shlex
import sys
import time
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

from ..extraction import extract_workbook, file_fingerprint
from .cli import json_value, validate_output_path

_WORKER_PLANS: dict = {}
_WORKER_PLAN_PATH: str | None = None


def write_json(path: Path, value: object) -> None:
    """승인된 실행 폴더에 새 JSON을 저장한다.

    Args:
        path: 아직 존재하지 않는 결과 경로.
        value: JSON으로 직렬화할 값.
    Returns:
        없음.
    Raises:
        FileExistsError: 기존 결과가 있는 경우.
        OSError: 저장 실패.
    Side Effects:
        지정한 JSON 파일 하나를 생성한다.
    """
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, default=json_value, separators=(",", ":"))
        stream.write("\n")


def inventory_dataset(dataset: Path, types: list[str]) -> dict:
    """실제 유형·월 디렉터리에서 조직 원본과 마스터를 식별한다.

    Args:
        dataset: 읽기 전용 월별검증_2026 폴더.
        types: 실행할 두 자리 유형 번호 목록.
    Returns:
        파일 경로와 기준월, 전체 발견 유형을 담은 목록 사전.
    Raises:
        ValueError: 유형 누락 또는 월별 마스터가 유일하지 않은 경우.
        OSError: 디렉터리 조회 실패.
    Side Effects:
        파일 목록만 조회하며 마스터 내용은 읽지 않는다.
    """
    dataset = dataset.resolve()
    found = sorted(p for p in dataset.iterdir() if p.is_dir() and p.name[:2].isdigit())
    selected = [p for p in found if p.name[:2] in types]
    if {p.name[:2] for p in selected} != set(types):
        raise ValueError("요청한 유형이 실제 목록에 없습니다")
    batches = []
    for folder in selected:
        for month in sorted(p for p in folder.iterdir() if p.is_dir()):
            files = sorted(month.glob("*.xlsx"))
            if not files:
                continue
            masters = [p for p in files if "마스터" in p.name]
            if len(masters) != 1:
                raise ValueError(f"마스터가 유일하지 않습니다: {month}")
            batches.append({"type": folder.name, "closing_month": month.name[:4] + "-" + month.name[5:7],
                            "organization_files": [str(p) for p in files if p not in masters],
                            "master": str(masters[0]), "organization_count": len(files) - 1})
    return {"dataset": str(dataset), "discovered_types": [p.name for p in found],
            "selected_types": [p.name for p in selected], "batches": batches,
            "organization_count": sum(b["organization_count"] for b in batches),
            "master_count": len(batches), "master_provided_to_parsers": False}


def _capture_source(path: str) -> dict:
    """조직 원본 하나에서 기존 AI의 초기 요청을 추출한다.

    Args:
        path: 조직 원본 절대 경로.
    Returns:
        요청·생략 내역과 원본 해시, 소요 시간.
    Raises:
        OSError: 원본 읽기 실패.
    Side Effects:
        원본만 읽으며 외부 통신이나 파일 쓰기는 하지 않는다.
    """
    from .replay import collect_initial_requests

    started = time.perf_counter()
    source = Path(path)
    before = file_fingerprint(source)
    result = collect_initial_requests(extract_workbook(source))
    result.update(source_file=path, sha256=before, unchanged=before == file_fingerprint(source),
                  seconds=time.perf_counter() - started)
    return result


def export_contexts(dataset: Path, types: list[str], output: Path, repo: Path, workers: int) -> dict:
    """전체 조직 원본의 정확한 요청을 새 실행 폴더에 중복 없이 저장한다.

    Args:
        dataset: 월별 원본 폴더.
        types: 실행 유형 번호.
        output: 새 결과 폴더.
        repo: 저장소 루트.
        workers: 독립 읽기 작업 프로세스 수.
    Returns:
        요청 수, 원본 수, 소요 시간 요약.
    Raises:
        ValueError: 잘못된 목록 또는 출력 경로.
        OSError: 원본 읽기나 승인된 출력 저장 실패.
    Side Effects:
        조직 원본을 읽고 요청 JSON과 목록·실행 이력을 새로 저장한다.
    """
    from .runner import implementation_fingerprint

    output = validate_output_path(output, repo)
    inventory = inventory_dataset(dataset, types)
    output.mkdir(parents=True, exist_ok=False)
    requests_dir = output / "requests"
    requests_dir.mkdir()
    write_json(output / "inventory.json", inventory)
    started = time.perf_counter()
    files = [p for batch in inventory["batches"] for p in batch["organization_files"]]
    catalog = {}
    sources = []
    with ProcessPoolExecutor(max_workers=workers) as pool:
        for index, result in enumerate(pool.map(_capture_source, files), 1):
            source = {k: v for k, v in result.items() if k != "requests"}
            source["request_hashes"] = []
            for request in result["requests"]:
                digest = request["request_hash"]
                if digest not in catalog:
                    write_json(requests_dir / (digest + ".json"), request)
                    catalog[digest] = {"task": request["task"], "sheet": request["context"]["sheet"], "occurrences": []}
                catalog[digest]["occurrences"].append(result["source_file"])
                source["request_hashes"].append(digest)
            sources.append(source)
            if index == 1 or index % 25 == 0 or index == len(files):
                print(f"context {index}/{len(files)}; unique requests={len(catalog)}", flush=True)
    summary = {"stage": "context_export", "organization_count": len(files),
               "unique_requests": len(catalog), "request_occurrences": sum(len(x["request_hashes"]) for x in sources),
               "seconds": time.perf_counter() - started, "external_api_calls": 0,
               "implementation_sha256": implementation_fingerprint(), "command": sys.argv,
               "created_at_utc": datetime.now(timezone.utc).isoformat()}
    write_json(output / "request_catalog.json", catalog)
    write_json(output / "source_capture.json", sources)
    write_json(output / "capture_execution.json", summary)
    return summary


def _append_rows(path: Path, rows: list[dict]) -> None:
    """현재 실행의 압축 JSONL에 레코드를 순서대로 보존한다.

    Args:
        path: 현재 새 결과 폴더의 JSONL gzip 경로.
        rows: 중복과 미확정 상태를 유지한 레코드 목록.
    Returns:
        없음.
    Raises:
        OSError: 저장 실패.
    Side Effects:
        지정한 실행 파일에 gzip 멤버를 추가한다.
    """
    with gzip.open(path, "at", encoding="utf-8", compresslevel=5) as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, default=json_value, separators=(",", ":")) + "\n")


def _run_source(job: tuple[str, str, str, str]) -> dict:
    """동일 조직 파일에 기존 두 어댑터를 독립 적용하고 평가용 출력을 보존한다.

    Args:
        job: 원본 경로, 유형, 기준월, 고정 계획 파일 경로.
    Returns:
        엔진별 축약 실행 내역과 상세 예측 레코드.
    Raises:
        OSError: 고정 계획을 읽을 수 없는 경우.
    Side Effects:
        조직 파일과 계획만 읽으며 네트워크 접근은 차단한다.
    """
    from .adapters import run_ai_adapter, run_rule_adapter
    from .monthly_evaluation import project_predictions
    from .replay import NetworkBlocker, OfflineReplayClient

    path, type_name, closing_month, plan_path = job
    global _WORKER_PLANS, _WORKER_PLAN_PATH
    if _WORKER_PLAN_PATH != plan_path:
        _WORKER_PLANS = json.loads(Path(plan_path).read_text(encoding="utf-8"))
        _WORKER_PLAN_PATH = plan_path
    client = OfflineReplayClient(_WORKER_PLANS, source_id=path)
    results = {}
    with NetworkBlocker() as guard:
        for engine in ("rule", "ai"):
            result = run_rule_adapter(Path(path)) if engine == "rule" else run_ai_adapter(
                Path(path), client=client, allow_api=False, template_cache={}, review=False)
            predictions = project_predictions((result.get("mapped") or {}).get("records", []),
                                               result.get("workbook") or {}, type_name=type_name,
                                               closing_month=closing_month)
            trial = {k: v for k, v in result.items() if k not in {"workbook", "mapped", "semantic"}}
            trial["semantic"] = {k: v for k, v in result.get("semantic", {}).items() if k != "structure_plans"}
            trial["type"] = type_name
            trial["closing_month"] = closing_month
            trial["source_file"] = path
            results[engine] = {"trial": trial, "predictions": predictions}
            # 대용량 원본의 두 추출·전체 표준 레코드를 동시에 보관하지 않는다.
            del result
    results["blocked_network_attempts"] = guard.blocked_attempts
    results["missing_requests"] = {key: value for key, value in client.requests.items()
                                   if key not in _WORKER_PLANS}
    return results


def summarize_months(rows: list[dict]) -> list[dict]:
    """월별 레코드 건수를 합산해 유형별·전체 마이크로 지표를 계산한다.

    Args:
        rows: 월별 엔진 점수 목록.
    Returns:
        유형별 두 행과 전체 두 행. 비율의 단순 평균을 사용하지 않는다.
    Raises:
        KeyError: 필수 점수 필드 누락.
    Side Effects:
        없음.
    """
    groups = defaultdict(list)
    for row in rows:
        groups[(row["type"], row["parser"])].append(row)
        if row.get("status") == "scored":
            groups[("전체", row["parser"])].append(row)
    output = []
    fields = ("truth_count", "confirmed_output_count", "match_count", "false_positive", "false_negative",
              "observed_output_count", "unresolved_output_count", "duplicate_output_groups",
              "duplicate_output_excess", "duplicate_truth_groups", "amount_mismatch", "missing", "extra")
    for (type_name, engine), scoped in sorted(groups.items()):
        row = {"type": type_name, "parser": engine,
               "months_scored": sum(s.get("status") == "scored" for s in scoped)}
        row.update({field: sum(s.get(field, 0) for s in scoped) for field in fields})
        truth, confirmed, matched = row["truth_count"], row["confirmed_output_count"], row["match_count"]
        row.update(precision=matched / confirmed if confirmed else (0.0 if truth else None),
                   recall=matched / truth if truth else None,
                   f1=2 * matched / (truth + confirmed) if truth else None,
                   recognition_rate=matched / truth if truth else None,
                   status="scored" if truth else "not_scored")
        output.append(row)
    return output


def write_summary(report: dict, output: Path) -> None:
    """결과 CSV와 실행·미측정 사유를 포함하는 한국어 보고서를 저장한다.

    Args:
        report: 유형별 summary와 실행 감사 내역.
        output: 현재 실행의 results 폴더.
    Returns:
        없음.
    Raises:
        FileExistsError: 기존 요약 파일이 있는 경우.
        OSError: 저장 실패.
    Side Effects:
        summary.csv와 report.md를 새로 생성한다.
    """
    rows = report["summary"]
    with (output / "summary.csv").open("x", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    lines = ["# 월별 파서 비교 최초 검증", "",
             "동일한 조직 원본을 각각 기존 룰 파서와 Codex 세션 구조계획 재생 파서에 전달했다. 마스터는 평가기만 읽었다.", "",
             "## 레코드 점수", "",
             "인식률은 Recall이다. 전체 점수는 채점 가능한 모든 월·유형의 건수를 합산한 마이크로 지표다.", "",
             "| 유형 | 파서 | 정답 수 | 확정 출력 수 | 일치 수 | Precision | Recall·인식률 | F1 |",
             "|---|---|---:|---:|---:|---:|---:|---:|"]
    for row in rows:
        rates = [f"{row[k]:.4%}" if row[k] is not None else "미측정" for k in ("precision", "recall", "f1")]
        lines.append(f"| {row['type']} | {row['parser']} | {row['truth_count']:,} | {row['confirmed_output_count']:,} | {row['match_count']:,} | " + " | ".join(rates) + " |")
    lines.extend(["", "## 범위와 채점", "",
                  f"- 실제 조직 원본 {report['inventory']['organization_count']:,}개, 마스터 {report['inventory']['master_count']}개, {len(report['inventory']['selected_types'])}개 유형을 inventory의 모든 기준월에 실행했다.",
                  "- 마감월별 스냅샷을 구분하고 조직코드+계정코드+연월+실적/계획/추정+원본 상세 ID로 연결했다. 조직 상세를 전사 합계로 먼저 집계하지 않았다.",
                  "- 같은 키의 금액 불일치는 상세 1건, F1의 FP와 FN 각 1건이다. 중복 레코드는 일대일 다중집합 연결로 보존했다.",
                  "- 빈 셀은 0원 정답으로 만들지 않았다. 마스터 빈칸·미확정 정답은 truth_sources.json의 issues에 기록했다.",
                  "- 월별 상세 금액만 레코드 평가한다. 연간 합계·소계·답안지 합계는 레코드 F1에 중복 포함하지 않는다.",
                  "- 파서가 생략한 시트·금액 또는 의미가 미확정인 출력도 대응 정답을 Recall 분모에서 제거하지 않는다.",
                  "- 마스터에서 빠진 실제 0원 상세도 확정 출력에만 있으면 오탐으로 세었다. 마스터 자체의 상세 생략 정책 영향을 포함한다.", "",
                  "## 유형별 실행 완료 여부", "",
                  "| 유형 | 파서 | 실행 파일 | 파싱 완료 | 검증 통과 | 전체 월 평가 |",
                  "|---|---|---:|---:|---:|---|"])
    for row in report["execution_by_type"]:
        lines.append(f"| {row['type']} | {row['parser']} | {row['attempted_files']}/{row['expected_files']} | {row['parser_completed_files']} | {row['validation_passed_files']} | {'완료' if row['evaluation_complete'] else '미완료'} |")
    lines.extend(["", "## 미측정 항목", "",
                  f"- 마스터 빈칸·미확정 금액: {report['unmeasured_truth_cells']:,}셀. 실제 0원과 구분해 상세 사유를 보존했다.",
                  "- API 방식 전체 처리 시간, 모델 API 토큰 사용량·요금은 미측정이다. 외부 API를 호출하지 않았다.",
                  "- 연간·소계·합계 시트의 레코드 F1은 중복 집계를 피하기 위해 측정 범위에서 제외했다.", "",
                  "## 실행 감사", "",
                  f"- 실제 외부 API 호출: {report['external_api_calls']}건. HTTP 시도: {report['http_post_attempts']}건. 차단된 네트워크 시도: {len(report['blocked_network_attempts'])}건.",
                  f"- 모든 원본이 캡처와 두 파서 실행 사이에 동일: {report['sources_unchanged']}. 고정 계획 해시 보존: {report['plans_unchanged']}.",
                  "- review off, exception 추가 호출 0, 파일마다 빈 구조 템플릿 캐시. 요청 본문 해시가 동일할 때 저장된 계획을 재사용한다.",
                  "- 기존 수식 비율 0.3 시트 생략 정책을 유지했다. 계획 누락·적용 실패·시트 생략은 execution_trials.jsonl.gz에 기록했다.",
                  "- 계획 작성은 정답과 룰 결과에 접근하지 않은 별도 Codex 작업에서 수행했다. 계획 작성 방식과 시간은 ../plan_authoring.json에 기록하며 parser 재생 시간과 구분한다.",
                  f"- 전체 비교 실행 벽시계 시간: {report['elapsed_wall_seconds']:.3f}초(채점·저장 포함). 파일별 timings는 해시·추출·검증·파서 적용 시간으로 채점·저장·계획 작성은 제외하며 API 방식 전체 처리 시간이 아니다.", "",
                  "## 산출물", "",
                  "- summary.csv, comparison.json, monthly_scores.json: 유형별·월별 수치와 조건",
                  "- *-truth.jsonl.gz: 마스터에서 독립 변환한 조직별 정답",
                  "- *-rule-records.jsonl.gz, *-ai-records.jsonl.gz: 유형별로 이어 붙인 조직 상세 출력",
                  "- *-errors.jsonl.gz: 매출·비용별 누락, 금액 불일치, 오탐, 중복, 미확정과 양쪽 출처·값",
                  "- domain_scores.jsonl.gz: 매출·비용별 건수와 상세 대조 후 합계 검증",
                  "- truth_sources.json: 정답 근거, 상세키 중복 조사, 미측정 셀과 이유",
                  "- execution_trials.jsonl.gz: 모든 파일·엔진의 처리 시간·검증·실패·생략·재생 요청",
                  "- ../inventory.json, ../requests/, ../plans.json: 확정 입력 목록, 실제 요청 컨텍스트, 최초 고정 계획", "",
                  "## 실행 명령", "", "```bash",
                  "python3 -B " + shlex.join(report["capture_execution"]["command"]),
                  "python3 -B " + shlex.join(report["conditions"]["command"]), "```", ""])
    with (output / "report.md").open("x", encoding="utf-8") as stream:
        stream.write("\n".join(lines))


def run_monthly(output: Path, workers: int) -> dict:
    """고정 계획을 재생하고 독립 마스터 상세로 최초 검증 점수를 저장한다.

    Args:
        output: 컨텍스트와 plans.json이 있는 승인된 실행 폴더.
        workers: 조직 파일별 독립 실행 프로세스 수.
    Returns:
        유형별 두 파서 점수와 실행 조건을 포함한 비교 보고서.
    Raises:
        FileExistsError: 첫 검증 결과가 이미 존재하는 경우.
        ValueError: 캡처 원본 또는 고정 계획이 실행 중 변경된 경우.
        OSError: 원본·계획 읽기 또는 결과 저장 실패.
    Side Effects:
        조직 원본·독립 마스터·계획을 읽고 새 results 폴더에 상세 결과를 저장한다.
        외부 통신은 차단하며 첫 결과나 계획을 덮어쓰지 않는다.
    """
    from .monthly_truth import build_master_truth
    from .monthly_evaluation import score_monthly_records
    from .runner import implementation_fingerprint

    repo = Path(__file__).resolve().parents[4]
    allowed = (repo / "parser/output/comparisons").resolve()
    output = output.resolve()
    if output == allowed or not output.is_relative_to(allowed):
        raise ValueError("기존 실행 폴더는 parser/output/comparisons 아래여야 합니다")
    inventory = json.loads((output / "inventory.json").read_text(encoding="utf-8"))
    capture_execution = json.loads((output / "capture_execution.json").read_text(encoding="utf-8"))
    captures = {r["source_file"]: r for r in json.loads((output / "source_capture.json").read_text(encoding="utf-8"))}
    plan_path = output / "plans.json"
    plan_hash = file_fingerprint(plan_path)
    results = output / "results"
    results.mkdir(exist_ok=False)
    started = time.perf_counter()
    run_conditions = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "command": sys.argv,
                      "plans_sha256": plan_hash, "implementation_sha256": implementation_fingerprint(),
                      "review": False, "max_exception_calls": 0, "workers": workers,
                      "template_cache": "empty_per_source", "external_api_enabled": False,
                      "file_timings": "parser replay only; excludes Codex plan authoring and scoring",
                      "wall_timing": "includes parser replay, independent scoring and result serialization",
                      "scope": "all monthly detail cells; annual totals excluded", "first_validation": True}
    write_json(results / "conditions.json", run_conditions)
    month_scores = []
    all_trials = []
    truth_metadata = []
    blocked = []
    with ProcessPoolExecutor(max_workers=workers) as pool:
        for number, batch in enumerate(inventory["batches"], 1):
            type_name, month = batch["type"], batch["closing_month"]
            predictions = {"rule": [], "ai": []}
            jobs = [(path, type_name, month, str(plan_path)) for path in batch["organization_files"]]
            for result in pool.map(_run_source, jobs):
                blocked.extend(result["blocked_network_attempts"])
                for key, request in result["missing_requests"].items():
                    target = results / ("missing-request-" + key + ".json")
                    if not target.exists():
                        write_json(target, request)
                for engine in ("rule", "ai"):
                    trial = result[engine]["trial"]
                    expected_hash = captures[trial["source_file"]]["sha256"]
                    integrity = trial["source_integrity"]
                    trial["matches_captured_source"] = integrity["sha256_before"] == integrity["sha256_after"] == expected_hash
                    all_trials.append(trial)
                    predictions[engine].extend(result[engine]["predictions"])
            master = Path(batch["master"])
            master_hash = file_fingerprint(master)
            truth = build_master_truth(master, type_name=type_name, closing_month=month)
            truth_metadata.append({k: v for k, v in truth.items() if k != "records"})
            truth_metadata[-1].update(type=type_name, closing_month=month, master_sha256=master_hash,
                                      master_unchanged=master_hash == file_fingerprint(master))
            _append_rows(results / (type_name + "-truth.jsonl.gz"), truth["records"])
            for engine in ("rule", "ai"):
                score = score_monthly_records(predictions[engine], truth["records"])
                row = {"type": type_name, "closing_month": month, "parser": engine, **score["summary"]}
                month_scores.append(row)
                _append_rows(results / (type_name + "-" + engine + "-records.jsonl.gz"), predictions[engine])
                _append_rows(results / (type_name + "-" + engine + "-errors.jsonl.gz"), score["details"])
                _append_rows(results / "domain_scores.jsonl.gz", [{"type": type_name, "closing_month": month,
                             "parser": engine, "scores": score.get("by_domain", {}), "aggregates": score.get("aggregates", {})}])
            print(f"comparison {number}/{len(inventory['batches'])}: {type_name}/{month}", flush=True)
            write_json(results / (type_name + "-" + month + "-completed.json"), month_scores[-2:])
    if file_fingerprint(plan_path) != plan_hash:
        raise ValueError("검증 중 고정 계획이 변경되었습니다")
    write_json(results / "monthly_scores.json", month_scores)
    write_json(results / "truth_sources.json", truth_metadata)
    _append_rows(results / "execution_trials.jsonl.gz", all_trials)
    execution_by_type = []
    for type_name in inventory["selected_types"]:
        batches = [b for b in inventory["batches"] if b["type"] == type_name]
        for engine in ("rule", "ai"):
            trials = [t for t in all_trials if t["type"] == type_name and t["parser"] == engine]
            expected = sum(b["organization_count"] for b in batches)
            execution_by_type.append({"type": type_name, "parser": engine, "expected_files": expected,
                                      "attempted_files": len(trials),
                                      "parser_completed_files": sum(t["completed"] for t in trials),
                                      "validation_passed_files": sum(t["validation_passed"] for t in trials),
                                      "evaluation_complete": len(trials) == expected and len([r for r in month_scores if r["type"] == type_name and r["parser"] == engine]) == len(batches)})
    report = {"inventory": inventory, "capture_execution": capture_execution,
              "conditions": run_conditions, "monthly_scores": month_scores,
              "summary": summarize_months(month_scores),
              "execution_by_type": execution_by_type,
              "unmeasured_truth_cells": sum(m["metadata"]["unmeasured_cells"] for m in truth_metadata),
              "elapsed_wall_seconds": time.perf_counter() - started, "blocked_network_attempts": blocked,
              "external_api_calls": sum(t["inference"]["api_calls"] for t in all_trials),
              "http_post_attempts": sum(t["inference"]["http_post_attempts"] for t in all_trials),
              "sources_unchanged": (all(t["matches_captured_source"] for t in all_trials)
                                     and all(c["unchanged"] for c in captures.values())
                                     and all(m["master_unchanged"] for m in truth_metadata)),
              "plans_unchanged": True, "trial_count": len(all_trials)}
    write_json(results / "comparison.json", report)
    write_summary(report, results)
    return {k: v for k, v in report.items() if k not in {"inventory", "monthly_scores"}}
