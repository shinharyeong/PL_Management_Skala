"""규칙 기반 월별 파서 출력을 독립 마스터 상세와 대조한다."""

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

from ..extraction import file_fingerprint
from .cli import json_value, validate_output_path


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


def _run_source(job: tuple[str, str, str]) -> dict:
    """조직 원본을 규칙 기반 파서로 읽고 독립 평가용 상세를 구성한다.

    Args:
        job: 조직 원본 경로, 유형, 기준월.
    Returns:
        원본 검증·처리 시간을 가진 trial과 월별 predictions.
    Raises:
        KeyError: 파서 출력의 필수 필드가 누락된 경우.
    Side Effects:
        지정 조직 파일만 읽는다. 정답 마스터 조회·외부 통신·파일 쓰기는 없다.
    """
    from .adapters import run_rule_adapter
    from .monthly_evaluation import project_predictions

    path, type_name, closing_month = job
    result = run_rule_adapter(Path(path))
    predictions = project_predictions((result.get("mapped") or {}).get("records", []),
                                      result.get("workbook") or {}, type_name=type_name,
                                      closing_month=closing_month)
    trial = {key: value for key, value in result.items() if key not in {"workbook", "mapped"}}
    trial.update(type=type_name, closing_month=closing_month, source_file=path)
    return {"trial": trial, "predictions": predictions}


def summarize_months(rows: list[dict]) -> list[dict]:
    """월별 레코드 건수를 합산해 유형별·전체 마이크로 지표를 계산한다.

    Args:
        rows: 월별 엔진 점수 목록.
    Returns:
        유형별·전체 집계 행. 비율의 단순 평균을 사용하지 않는다.
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
    lines = ["# 월별 규칙 기반 파서 검증", "",
             "조직 원본을 규칙 기반 파서로 읽고 독립 마스터 상세와 대조했다. 마스터는 평가기만 읽었다.", "",
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
                  "- 연간·소계·합계 시트의 레코드 F1은 중복 집계를 피하기 위해 측정 범위에서 제외했다.", "",
                  "## 실행 감사", "",
                  f"- 모든 조직 원본과 평가 마스터의 실행 전후 해시 동일: {report['sources_unchanged']}.",
                  f"- 전체 검증 실행 벽시계 시간: {report['elapsed_wall_seconds']:.3f}초(채점·저장 포함). 파일별 timings는 해시·추출·검증·파서 적용 시간이며 채점·저장은 제외한다.", "",
                  "## 산출물", "",
                  "- summary.csv, comparison.json, monthly_scores.json: 유형별·월별 수치와 조건",
                  "- *-truth.jsonl.gz: 마스터에서 독립 변환한 조직별 정답",
                  "- *-rule-records.jsonl.gz: 유형별로 이어 붙인 규칙 기반 조직 상세 출력",
                  "- *-errors.jsonl.gz: 매출·비용별 누락, 금액 불일치, 오탐, 중복, 미확정과 양쪽 출처·값",
                  "- domain_scores.jsonl.gz: 매출·비용별 건수와 상세 대조 후 합계 검증",
                  "- truth_sources.json: 정답 근거, 상세키 중복 조사, 미측정 셀과 이유",
                  "- execution_trials.jsonl.gz: 모든 조직 파일의 처리 시간·검증·실패 내역",
                  "- ../inventory.json: 유형·기준월별 조직 원본과 독립 마스터 목록", "",
                  "## 실행 명령", "", "```bash",
                  "python3 -B " + shlex.join(report["conditions"]["command"]), "```", ""])
    with (output / "report.md").open("x", encoding="utf-8") as stream:
        stream.write("\n".join(lines))


def run_monthly(dataset: Path, types: list[str], output: Path, repo: Path, workers: int) -> dict:
    """규칙 기반 월별 상세를 독립 마스터와 대조하고 새 실행 결과를 저장한다.

    Args:
        dataset: 읽기 전용 월별 원본 폴더.
        types: 실행할 두 자리 유형 번호 목록.
        output: 사전 승인된 새 실행 폴더.
        repo: 저장소 루트.
        workers: 조직 파일별 독립 실행 프로세스 수. 1 이상이어야 한다.
    Returns:
        규칙 기반 유형별 점수와 원본 무변경·실행 조건을 담은 요약.
    Raises:
        FileExistsError: 결과 폴더가 이미 존재하는 경우.
        ValueError: 실행 유형·출력 경로·프로세스 수가 잘못된 경우.
        OSError: 조직 원본·평가 마스터 읽기 또는 승인된 결과 저장 실패.
    Side Effects:
        조직 원본은 파서만, 마스터는 평가기만 읽는다. 새 실행 폴더에
        inventory.json과 results를 만들며 원본·기존 결과는 변경하지 않는다.
    """
    from .monthly_truth import build_master_truth
    from .monthly_evaluation import score_monthly_records
    from .runner import implementation_fingerprint

    if workers < 1:
        raise ValueError("프로세스 수는 1 이상이어야 합니다")
    output = validate_output_path(output, repo)
    inventory = inventory_dataset(dataset, types)
    if not inventory["batches"]:
        raise ValueError("검증할 월별 조직 원본과 마스터가 없습니다")
    source_hashes = {path: file_fingerprint(Path(path))
                     for batch in inventory["batches"] for path in batch["organization_files"]}
    output.mkdir(parents=True, exist_ok=False)
    write_json(output / "inventory.json", inventory)
    results = output / "results"
    results.mkdir(exist_ok=False)
    started = time.perf_counter()
    run_conditions = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "command": sys.argv,
                      "implementation_sha256": implementation_fingerprint(), "parser": "rule",
                      "workers": workers,
                      "file_timings": "hash, extraction, validation and rule parsing; scoring excluded",
                      "wall_timing": "includes rule parsing, independent scoring and result serialization",
                      "scope": "all monthly detail cells; annual totals excluded"}
    write_json(results / "conditions.json", run_conditions)
    month_scores = []
    all_trials = []
    truth_metadata = []
    with ProcessPoolExecutor(max_workers=workers) as pool:
        for number, batch in enumerate(inventory["batches"], 1):
            type_name, month = batch["type"], batch["closing_month"]
            predictions = []
            jobs = [(path, type_name, month) for path in batch["organization_files"]]
            for result in pool.map(_run_source, jobs):
                trial = result["trial"]
                integrity = trial["source_integrity"]
                trial["matches_source_snapshot"] = (
                    integrity["sha256_before"] == integrity["sha256_after"] == source_hashes[trial["source_file"]]
                )
                all_trials.append(trial)
                predictions.extend(result["predictions"])
            master = Path(batch["master"])
            master_hash = file_fingerprint(master)
            truth = build_master_truth(master, type_name=type_name, closing_month=month)
            truth_metadata.append({key: value for key, value in truth.items() if key != "records"})
            truth_metadata[-1].update(type=type_name, closing_month=month, master_sha256=master_hash,
                                      master_unchanged=master_hash == file_fingerprint(master))
            _append_rows(results / (type_name + "-truth.jsonl.gz"), truth["records"])
            score = score_monthly_records(predictions, truth["records"])
            row = {"type": type_name, "closing_month": month, "parser": "rule", **score["summary"]}
            month_scores.append(row)
            _append_rows(results / (type_name + "-rule-records.jsonl.gz"), predictions)
            _append_rows(results / (type_name + "-rule-errors.jsonl.gz"), score["details"])
            _append_rows(results / "domain_scores.jsonl.gz", [{"type": type_name, "closing_month": month,
                         "parser": "rule", "scores": score.get("by_domain", {}), "aggregates": score.get("aggregates", {})}])
            print(f"comparison {number}/{len(inventory['batches'])}: {type_name}/{month}", flush=True)
            write_json(results / (type_name + "-" + month + "-completed.json"), [row])
    write_json(results / "monthly_scores.json", month_scores)
    write_json(results / "truth_sources.json", truth_metadata)
    _append_rows(results / "execution_trials.jsonl.gz", all_trials)
    execution_by_type = []
    for type_name in inventory["selected_types"]:
        batches = [batch for batch in inventory["batches"] if batch["type"] == type_name]
        trials = [trial for trial in all_trials if trial["type"] == type_name]
        expected = sum(batch["organization_count"] for batch in batches)
        execution_by_type.append({"type": type_name, "parser": "rule", "expected_files": expected,
                                  "attempted_files": len(trials),
                                  "parser_completed_files": sum(trial["completed"] for trial in trials),
                                  "validation_passed_files": sum(trial["validation_passed"] for trial in trials),
                                  "evaluation_complete": len(trials) == expected and len([row for row in month_scores if row["type"] == type_name]) == len(batches)})
    report = {"inventory": inventory, "conditions": run_conditions, "monthly_scores": month_scores,
              "summary": summarize_months(month_scores), "execution_by_type": execution_by_type,
              "unmeasured_truth_cells": sum(value["metadata"]["unmeasured_cells"] for value in truth_metadata),
              "elapsed_wall_seconds": time.perf_counter() - started,
              "sources_unchanged": (all(trial["matches_source_snapshot"] for trial in all_trials)
                                     and all(value["master_unchanged"] for value in truth_metadata)),
              "trial_count": len(all_trials)}
    write_json(results / "comparison.json", report)
    write_summary(report, results)
    return {key: value for key, value in report.items() if key not in {"inventory", "monthly_scores"}}
