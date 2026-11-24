"""저장된 v3 평가와 실제 Excel v4 평가를 원본 불변·행 제외 감사와 연결한다."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator

from .monthly_truth import decimal_amount
from .reconstruction_selection import _checked_path, _sha256
from .regression import (
    _aggregate_matrix,
    _allowed_root,
    _read_json,
    _saved_index,
    _write_csv,
    _write_json,
    metric_snapshot,
)


def _lines(path: Path) -> Iterator[dict]:
    """저장된 압축 JSONL을 한 레코드씩 읽는다.

    Args:
        path: 기존 감사 또는 평가 결과 경로.
    Returns:
        JSON 객체를 순서대로 내는 반복자.
    Raises:
        OSError, ValueError: 읽기·압축·JSON 오류.
    Side Effects:
        지정 결과만 읽으며 원본 Excel을 열지 않는다.
    """
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        for line in stream:
            yield json.loads(line)


def _uncompressed_hash(path: Path) -> str:
    """gzip 작성 시각과 무관하게 실제 저장 원본 파서 레코드 바이트를 해시한다.

    Args:
        path: source_records.jsonl.gz 경로.
    Returns:
        압축 해제한 전체 바이트의 SHA256.
    Raises:
        OSError: 읽기 또는 압축 오류.
    Side Effects:
        파일을 스트리밍으로 읽고 새 파일을 만들지 않는다.
    """
    digest = hashlib.sha256()
    with gzip.open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _artifact(path: Path, hashes: dict[str, str]) -> Path:
    """저장 JSON/GZ 경계를 확인하고 분석 전 해시를 보호 목록에 넣는다.

    Args:
        path: 저장 생성·평가·감사 경로.
        hashes: 읽기 전 SHA256을 기록할 사전.
    Returns:
        comparisons 내부의 심볼릭 링크가 아닌 실제 경로.
    Raises:
        OSError, ValueError: 경계·파일형식·읽기 중 변경 문제.
    Side Effects:
        지정 산출물의 바이트만 읽고 hashes를 갱신한다. Excel은 열지 않는다.
    """
    path = _checked_path(path, _allowed_root(), exists=True)
    if path.suffix not in {".json", ".gz"}:
        raise ValueError("후처리는 저장 JSON/GZ만 읽습니다")
    digest = _sha256(path)
    if str(path) in hashes and hashes[str(path)] != digest:
        raise ValueError("저장 산출물이 분석 중 변경되었습니다")
    hashes[str(path)] = digest
    return path


def _evaluation_index(report: dict, expected: set[tuple[str, str]], *, actual_excel: bool) -> dict:
    """중복 없는 완료 평가와 실제 Excel 평가 출처를 확인한다.

    Args:
        report: 저장 validation 사전.
        expected: 최종 생성 목록의 정확한 배치 키 집합.
        actual_excel: 현재 결과에 실제 Excel 평가 계약을 요구할지 여부.
    Returns:
        입력 평가 행을 변경하지 않은 유형·기준월 색인.
    Raises:
        ValueError: 누락·중복·범위·미완료·실제 Excel 출처 오류.
    Side Effects:
        없음. 점수·키·분모를 재계산하거나 변경하지 않는다.
    """
    rows = report.get("rows")
    if (not isinstance(rows, list) or len(rows) != len(expected)
            or report.get("generated_count") != len(expected)
            or report.get("detailed_and_total_compared_count") != len(expected)):
        raise ValueError("평가에 중복 없는 전체 완료 행이 필요합니다")
    result = {}
    for row in rows:
        key = (row.get("type"), row.get("closing_month"))
        if key in result or key not in expected:
            raise ValueError("중복 또는 범위 밖 평가 행입니다")
        if row.get("detailed_and_total_compared") is not True or row.get("reference_unchanged") is not True:
            raise ValueError("완료 및 기준 원본 보존이 확인된 평가만 보고합니다")
        field = "strict_excel_detail_and_total_match" if actual_excel else "strict_detail_and_total_match"
        if type(row.get(field)) is not bool:
            raise ValueError("엄격 평가 완료 플래그가 없습니다")
        if actual_excel and (row.get("prediction_source") != "saved_excel" or row.get("metadata_json_value_reads") != 0):
            raise ValueError("저장 Excel 자체만 판독한 평가가 필요합니다")
        result[key] = row
    return result


def audit_exclusion(record: dict) -> dict:
    """제외 감사의 원문 금액 상태·정책 사유·미해결 원본 요구를 요약한다.

    Args:
        record: 생성기가 보존한 제외 월별 상세 하나.
    Returns:
        금액 상태와 제외 사유 및 필요한 원본 정정 설명.
    Raises:
        없음. 읽을 수 없는 금액은 미확정으로 남긴다.
    Side Effects:
        없음. 원문·금액·키를 변경하지 않는다.
    """
    amount = decimal_amount(record.get("normalized_value"))
    if amount is not None and record.get("confirmed"):
        kind = "zero" if amount == 0 else "nonzero"
    elif record.get("formula") and record["formula"].get("cached_status") not in {"stored_unverified", "confirmed"}:
        kind = "formula_unresolved"
    else:
        kind = "blank_or_unresolved"
    decision = record.get("publication_decision") or {}
    reasons = decision.get("reasons") or [record.get("exclusion_reason", "unspecified")]
    needed = None
    correction_kind = None
    correction_fields = []
    if record.get("exclusion_reason") == "detail_identity_unresolved":
        if record.get("organization_code") in (None, "", "unknown") or record.get("organization_status") not in (None, "resolved"):
            correction_fields.append("organization")
        for field in ("account_code", "detail_id"):
            if record.get(field) in (None, "", "unknown"):
                correction_fields.append(field)
        if not record.get("source_file") or not record.get("sheet") or type(record.get("source_row")) is not int:
            correction_fields.append("source_location")
        correction_kind = "organization_identity" if correction_fields == ["organization"] else "identity_fields"
        labels = {"organization": "조직코드·조직명·팀코드", "account_code": "계정코드",
                  "detail_id": "상세 식별자", "source_location": "물리 원본행 출처"}
        needed = "·".join(labels[field] for field in correction_fields) if correction_fields else "상세 식별·원본 출처"
        needed += "의 확인된 원본 또는 승인된 정정 근거 필요; 관측값은 변경하지 않음"
    elif ("missing_required_actual" in reasons and record.get("period_group") == "outlook"
          and record.get("scenario") == "actual" and record.get("month") == 1
          and str(record.get("year")) == str(record.get("closing_month", ""))[:4]
          and "raw_value" in record and record["raw_value"] in (None, "") and amount is None and not record.get("formula")):
        needed = "당해 1월 실적의 실제 원본 금액 또는 승인된 정정값 필요; 0원으로 대체하지 않음"
        correction_kind, correction_fields = "required_january_actual", ["amount"]
    elif kind == "formula_unresolved":
        needed = "수식을 재계산하여 저장한 원본 또는 승인된 금액 근거 필요"
        correction_kind, correction_fields = "formula_cache", ["amount", "formula"]
    return {"amount_kind": kind, "reasons": reasons, "needed_source_correction": needed,
            "source_correction_kind": correction_kind, "source_correction_fields": correction_fields}


def create_publication_report(baseline: Path, current: Path, output: Path, *, write_output: bool = False) -> dict:
    """81개 저장 평가의 before/after와 실제 원본 출력 불변·정책 제외를 보고한다.

    Args:
        baseline: 보존된 v3 final 폴더.
        current: v4 final generation 및 실제 Excel evaluation 폴더.
        output: 아직 없는 보고 결과 폴더.
        write_output: 명시 저장 요청일 때만 결과를 생성한다.
    Returns:
        월별·전체 비교와 원본 출력 불변, 제외 및 원본 품질 요약.
    Raises:
        OSError, ValueError: 경계·범위·동일 원본·실제 Excel 평가 계약 위반.
    Side Effects:
        저장 결과만 읽는다. 요청 시 새 JSON·CSV·미해결 출처 JSONL을 쓴다.
        원본 및 기준 Excel에 접근하지 않고 재채점하거나 점수를 수정하지 않는다.
    """
    allowed = _allowed_root()
    baseline, current = (_checked_path(path, allowed, exists=True) for path in (baseline, current))
    output = _checked_path(output, allowed, exists=False)
    if output.exists():
        raise ValueError("기존 보고 결과는 덮어쓰지 않습니다")
    manifests, evaluations, hashes = [], [], {}
    for directory in (baseline, current):
        for name, target in (("generation.json", manifests), ("evaluation/validation.json", evaluations)):
            data, digest = _read_json(directory / name)
            target.append(data)
            hashes[str(directory / name)] = digest
    old, new = (_saved_index(manifest, label) for manifest, label in zip(manifests, ("v3", "v4")))
    if len(old) != 81 or set(old) != set(new) or manifests[0]["inventory_sha256"] != manifests[1]["inventory_sha256"]:
        raise ValueError("동일한 목록의 9유형·9기준월 81개가 필요합니다")
    indices = [_evaluation_index(report, set(old), actual_excel=index == 1) for index, report in enumerate(evaluations)]
    rows, audits, corrections, residuals, actual_residuals = [], [], [], [], []
    for key, batch in new.items():
        prior = old[key]
        if any(prior[field] != batch[field] for field in ("organization_files", "organization_count", "master")):
            raise ValueError("before/after 조직 입력과 기준 마스터 대응이 다릅니다")
        old_dir, new_dir = (_checked_path(Path(item["directory"]), allowed, exists=True) for item in (prior, batch))
        if old_dir == new_dir:
            raise ValueError("v4는 별도 경로에서 실제 재생성되어야 합니다")
        source_before = _uncompressed_hash(_artifact(old_dir / "source_records.jsonl.gz", hashes))
        source_after = _uncompressed_hash(_artifact(new_dir / "source_records.jsonl.gz", hashes))
        if source_before != source_after:
            raise ValueError(f"원본 파서 출력이 발행 정책 외에 변경되었습니다: {key}")
        metrics = []
        for root, index in zip((baseline, current), indices):
            errors = list(_lines(_artifact(root / "evaluation" / key[0] / key[1] / "errors.jsonl.gz", hashes)))
            metrics.append(metric_snapshot(index[key], errors))
        summary = indices[1][key]
        if summary.get("prediction_source") != "saved_excel":
            raise ValueError("저장 Excel 자체를 판독한 평가만 v4 최종 비교로 인정합니다")
        rows.append({"type": key[0], "closing_month": key[1], "before": metrics[0], "after": metrics[1],
                     "before_strict_complete": indices[0][key]["strict_detail_and_total_match"],
                     "after_strict_complete": summary["strict_excel_detail_and_total_match"],
                     "source_records_uncompressed_sha256": source_after,
                     "source_comparison": {"identical": True, "changed_cell_count": 0},
                     "nonregression_required": key[0].startswith("09_"),
                     "nonregression_pass": summary["strict_excel_detail_and_total_match"]})
        counts = Counter()
        audit_path = _artifact(new_dir / "excluded_records.jsonl.gz", hashes)
        decision_path = _artifact(new_dir / "master.json", hashes)
        for record in _lines(audit_path):
            audit = audit_exclusion(record)
            for reason in audit["reasons"]:
                counts[(reason, record.get("domain"), audit["amount_kind"])] += 1
            if audit["needed_source_correction"]:
                corrections.append({"type": key[0], "closing_month": key[1], **record,
                                    "needed_source_correction": audit["needed_source_correction"],
                                    "source_correction_kind": audit["source_correction_kind"],
                                    "source_correction_fields": audit["source_correction_fields"]})
        audits.extend({"type": key[0], "closing_month": key[1], "reason": reason, "domain": domain,
                       "amount_kind": kind, "slot_count": count,
                       "full_audit_file": str(audit_path), "decision_evidence_file": str(decision_path)}
                      for (reason, domain, kind), count in sorted(counts.items(), key=lambda item: str(item[0])))
        totals_path = _artifact(current / "evaluation" / key[0] / key[1] / "totals.json", hashes)
        for total in json.loads(totals_path.read_text(encoding="utf-8")):
            if not total.get("matching"):
                residuals.append({"type": key[0], "closing_month": key[1], **total,
                                  "detail_error_file": str(totals_path.with_name("errors.jsonl.gz"))})
        actual_path = _artifact(totals_path.with_name("excel_total_checks.jsonl.gz"), hashes)
        for check in _lines(actual_path):
            if not check.get("fully_matching"):
                actual_residuals.append({"type": key[0], "closing_month": key[1], **check,
                                         "actual_excel_total_check_file": str(actual_path)})
        print(f"publication report {len(rows)}/81 {key[0]}/{key[1]}", flush=True)
    if any(_sha256(Path(path)) != digest for path, digest in hashes.items()):
        raise ValueError("보고 준비 중 저장 목록 또는 평가가 바뀌었습니다")
    result = {"contract_version": "publication-v4-saved-excel-comparison-v1",
              "created_at_utc": datetime.now(timezone.utc).isoformat(), "command": sys.argv,
              "input_sha256": hashes, "inputs_unchanged": True, "source_excel_reads": 0,
              "reference_excel_reads": 0, "compared_batches": len(rows), "source_records_unchanged_batches": len(rows),
              "before_strict_complete": sum(row["before_strict_complete"] for row in rows),
              "after_strict_complete": sum(row["after_strict_complete"] for row in rows),
              "matrix": _aggregate_matrix(rows), "rows": rows, "audit_summary": audits,
              "source_correction_slots": len(corrections), "remaining_total_difference_groups": len(residuals),
              "source_correction_kind_counts": dict(Counter(row["source_correction_kind"] for row in corrections)),
              "remaining_actual_excel_total_checks": len(actual_residuals),
              "source_quality_is_separate_from_strict_excel_match": True, "written": write_output}
    if write_output:
        output.mkdir(exist_ok=False)
        _write_json(output / "comparison.json", result)
        _write_csv(output / "before_after.csv", result["matrix"])
        _write_csv(output / "before_after_monthly.csv", [
            {"type": row["type"], "closing_month": row["closing_month"],
             **{side + "_" + field: value for side in ("before", "after") for field, value in row[side].items()},
             "before_strict_complete": row["before_strict_complete"], "after_strict_complete": row["after_strict_complete"]}
            for row in rows])
        if audits:
            _write_csv(output / "publication_exclusions.csv", audits)
        _write_json(output / "remaining_total_differences.json", {"groups": residuals})
        _write_json(output / "remaining_actual_excel_total_checks.json", {"checks": actual_residuals})
        with gzip.open(output / "source_corrections_needed.jsonl.gz", "xt", encoding="utf-8") as stream:
            for correction in corrections:
                stream.write(json.dumps(correction, ensure_ascii=False, separators=(",", ":")) + "\n")
    return result


def main(argv: list[str] | None = None) -> int:
    """보존된 평가·생성 감사의 v3→v4 최종 비교 명령을 실행한다.

    Args:
        argv: 인자 목록. None이면 현재 명령행.
    Returns:
        81개 엄격 완전 일치 시 0, 하나라도 남으면 2.
    Raises:
        OSError, ValueError: 입력·검증·저장 오류.
    Side Effects:
        --write-output일 때 승인된 새 보고 폴더만 생성한다.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--current", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--write-output", action="store_true")
    args = parser.parse_args(argv)
    result = create_publication_report(args.baseline, args.current, args.output, write_output=args.write_output)
    print(json.dumps({key: result[key] for key in ("compared_batches", "before_strict_complete", "after_strict_complete", "source_correction_slots")}))
    return 0 if result["after_strict_complete"] == 81 else 2


if __name__ == "__main__":
    raise SystemExit(main())
