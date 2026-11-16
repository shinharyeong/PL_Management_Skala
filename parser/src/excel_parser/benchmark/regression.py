"""저장된 원본 전용 생성물을 합치고 고정 v2와 새 파서의 회귀를 비교한다.

조직 원본·기준 마스터 Excel은 열지 않는다. join은 생성 JSON과 압축 상세의
해시만 확인하며 compare는 이미 완료된 평가와 원본별 파서 출력을 대조한다.
"""

from __future__ import annotations

import argparse
import csv
import gzip
import json
import re
import sys
import time
from collections import Counter, defaultdict
from copy import deepcopy
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path

from .reconstruction_selection import _checked_path, _index_batches, _sha256

SEMANTIC_FIELDS = (
    "domain", "organization_code", "organization_status", "account_code", "detail_id",
    "revision", "year", "month", "scenario", "aggregation", "period_group", "role_kind",
    "source_unit", "value_kind", "value_status", "mapping_status", "confirmed",
    "semantic_complete", "raw_value", "formula",
)
METRIC_FIELDS = (
    "truth_count", "confirmed_output_count", "match_count", "false_positive", "nonzero_fp",
    "false_negative", "amount_mismatch", "unresolved_required", "total_matching_groups", "total_groups",
)


def _allowed_root() -> Path:
    """읽기·쓰기 허용 산출물 경계를 반환한다.

    Args: 없음.
    Returns: comparisons의 절대 경로.
    Raises: 없음.
    Side Effects: 경로를 계산할 뿐 폴더를 생성하지 않는다.
    """
    return (Path(__file__).resolve().parents[4] / "parser/output/comparisons").resolve()


def _read_json(path: Path) -> tuple[dict, str]:
    """승인된 기존 JSON을 읽고 읽는 동안 해시가 유지됐는지 확인한다.

    Args: path - 생성 또는 평가 JSON 경로.
    Returns: 사전과 SHA256.
    Raises: OSError, ValueError - 경계·JSON·읽기 중 변경 오류.
    Side Effects: 지정 산출물만 읽는다.
    """
    path = _checked_path(path, _allowed_root(), exists=True)
    before = _sha256(path)
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or _sha256(path) != before:
        raise ValueError("JSON 객체가 아니거나 읽는 중 변경되었습니다: " + str(path))
    return value, before


def _saved_index(manifest: dict, label: str) -> dict[tuple[str, str], dict]:
    """원시 생성 목록 또는 이미 고정된 복합 목록을 배치별로 검증한다.

    Args: manifest - 저장 생성 목록. label - 오류 위치 이름.
    Returns: 유형·마감월 색인.
    Raises: ValueError - 생성 계약·배치별 구현·중복 오류.
    Side Effects: 메모리만 조회하며 기존 선택 모듈은 변경하지 않는다.
    """
    if manifest.get("implementation_sha256") is not None:
        return _index_batches(manifest, label)
    if manifest.get("contract_version") != "selected-source-only-technical-master-v1" or manifest.get("implementation_profile") != "per_batch_lineage":
        raise ValueError(label + ": 알려진 배치별 계보 계약이 아닙니다")
    indexed = {}
    for batch in manifest.get("batches", []):
        single = {**manifest, "implementation_sha256": batch.get("generation_implementation_sha256"), "batches": [batch]}
        checked = _index_batches(single, label)
        key = next(iter(checked))
        if key in indexed:
            raise ValueError(label + ": 중복 배치")
        indexed.update(checked)
    if not indexed:
        raise ValueError(label + ": 빈 생성 목록")
    return indexed


def join_manifest_data(groups: list[dict], baseline: dict, *, group_paths: list[str],
                       group_hashes: list[str], baseline_path: str, baseline_hash: str) -> dict:
    """같은 구현으로 재파싱한 비중복 그룹을 기준 목록과 동일한 81개로 합친다.

    Args: groups - 새 생성 목록들. baseline - v2 최종 목록. group_paths/group_hashes -
        실제 읽은 그룹 목록의 출처. baseline_path/baseline_hash - 고정 기준 목록 근거.
    Returns: 기존 evaluate와 호환되는 단일 구현 final generation 사전.
    Raises: ValueError - 구현·inventory·범위·원본 목록·기준 마스터 경로 불일치.
    Side Effects: 없음. 입력 객체와 파일을 변경하지 않는다.
    """
    original = _saved_index(baseline, "baseline")
    types = {key[0] for key in original}
    months = {key[1] for key in original}
    if len(original) != 81 or len(types) != 9 or len(months) != 9 or {name[:2] for name in types} != {f"{i:02d}" for i in range(1, 10)} or set(original) != {(t, m) for t in types for m in months}:
        raise ValueError("기준 목록은 01~09 유형의 동일한 9개월, 81개여야 합니다")
    if not groups or len(groups) != len(group_paths) or len(groups) != len(group_hashes):
        raise ValueError("그룹 목록·경로·해시 수가 다릅니다")
    implementations = {group.get("implementation_sha256") for group in groups}
    if len(implementations) != 1:
        raise ValueError("모든 새 생성 그룹의 구현 SHA256이 같아야 합니다")
    policies = {group.get("publication_policy") for group in groups}
    if len(policies) != 1:
        raise ValueError("서로 다른 발행 정책의 생성 결과를 합칠 수 없습니다")
    selected = {}
    inputs = []
    for group, source, digest in zip(groups, group_paths, group_hashes):
        indexed = _index_batches(group, source)
        if group["inventory_sha256"] != baseline["inventory_sha256"]:
            raise ValueError("새 생성과 v2의 inventory 해시가 다릅니다")
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError("그룹 manifest 해시 형식 오류")
        inputs.append({"manifest": source, "manifest_sha256": digest,
                       "implementation_sha256": group["implementation_sha256"],
                       "generation_seconds": group["generation_seconds"], "batch_count": len(indexed)})
        for key, batch in indexed.items():
            if key in selected or key not in original:
                raise ValueError("새 그룹에 중복 또는 기준 밖 배치가 있습니다")
            if batch.get("generation_implementation_sha256", group["implementation_sha256"]) != group["implementation_sha256"]:
                raise ValueError("새 배치 구현 해시가 생성 그룹과 다릅니다")
            for field in ("organization_files", "organization_count", "master"):
                if batch[field] != original[key][field]:
                    raise ValueError(f"{key}: v2와 {field}가 다릅니다")
            item = deepcopy(batch)
            item["generation_implementation_sha256"] = group["implementation_sha256"]
            item["lineage"] = {"manifest": source, "manifest_sha256": digest,
                               "generation_implementation_sha256": group["implementation_sha256"],
                               "selected_batch_directory": item["directory"],
                               "baseline_batch_directory": original[key]["directory"],
                               "baseline_master_details_sha256": original[key]["master_details_sha256"],
                               "selection_reason": "reparsed_with_same_frozen_implementation"}
            selected[key] = item
    if set(selected) != set(original):
        raise ValueError("새 그룹의 합집합이 정확한 81개가 아닙니다")
    result = {"contract_version": "joined-source-only-regression-v1", "inventory": baseline.get("inventory"),
            "inventory_sha256": baseline["inventory_sha256"], "implementation_sha256": next(iter(implementations)),
            "reference_masters_read": 0, "records_source": "organization_inputs_only",
            "source_excel_reads_during_join": 0, "reference_excel_reads_during_join": 0,
            "generation_inputs": inputs, "baseline_manifest": baseline_path, "baseline_manifest_sha256": baseline_hash,
            "generation_seconds": sum(group["generation_seconds"] for group in groups),
            "generation_seconds_scope": "sum_of_new_generation_group_wall_times_not_parallel_elapsed",
            "batches": [selected[(b["type"], b["closing_month"])] for b in baseline["batches"]]}
    policy = next(iter(policies))
    if policy is not None:
        result["publication_policy"] = policy
    return result


def _source_audit(directory: Path, batch: dict) -> dict[str, str]:
    """저장 execution의 모든 조직 파일 해시와 생성 중 불변성을 확인한다.

    Args: directory - 월 생성 폴더. batch - 기대 조직 목록.
    Returns: 원본 경로 문자열에서 SHA256으로의 대응.
    Raises: OSError, ValueError - 기록 누락·중복·변경·목록 불일치.
    Side Effects: execution.json만 읽고 조직 원본 경로는 열지 않는다.
    """
    target = _checked_path(directory / "execution.json", _allowed_root(), exists=True)
    before = _sha256(target)
    trials = json.loads(target.read_text(encoding="utf-8"))
    hashes = {}
    for trial in trials:
        source = trial.get("source", {}).get("path")
        integrity = trial.get("source_integrity", {})
        digest = integrity.get("sha256_before")
        if source in hashes or source not in batch["organization_files"] or not re.fullmatch(r"[0-9a-f]{64}", str(digest)):
            raise ValueError("저장 원본 감사의 경로·중복·해시 오류")
        if integrity.get("unchanged") is not True or integrity.get("sha256_after") != digest:
            raise ValueError("생성 중 원본 해시가 바뀌었습니다")
        hashes[source] = digest
    if set(hashes) != set(batch["organization_files"]) or _sha256(target) != before:
        raise ValueError("저장 원본 감사의 범위가 다르거나 읽는 중 변경되었습니다")
    return hashes


def _write_json(path: Path, value: dict) -> None:
    """새 승인 결과 JSON을 배타적으로 저장한다.

    Args: path - 아직 없는 출력 경로. value - JSON 객체.
    Returns: 없음.
    Raises: OSError, TypeError - 기존 파일·저장·직렬화 오류.
    Side Effects: 새 결과 파일 한 개만 작성한다.
    """
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")


def _write_csv(path: Path, rows: list[dict]) -> None:
    """열 구조가 같은 결과 행을 새 UTF-8 CSV로 저장한다.

    Args: path - 새 CSV 경로. rows - 비어 있지 않은 동형 사전 목록.
    Returns: 없음.
    Raises: OSError, ValueError - 저장 또는 열 구조 오류.
    Side Effects: 결과 CSV 한 개를 배타적으로 만든다.
    """
    with path.open("x", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def join_runs(groups: list[Path], baseline: Path, output: Path, *, write_output: bool = False) -> dict:
    """새 그룹과 고정 v2의 저장 해시를 검증하고 최종 81개 색인을 만든다.

    Args: groups - 서로 다른 새 생성 실행 폴더. baseline - v2 final.
        output - 새 v3 final. write_output - 명시 저장 요청 여부.
    Returns: 실제 원본 불변·상세 해시 검증이 포함된 generation 사전.
    Raises: OSError, ValueError - 경계·생성·동일 원본·해시 위반.
    Side Effects: 생성 산출물만 읽고 요청된 경우 새 final JSON/CSV를 쓴다.
    """
    started = time.perf_counter()
    allowed = _allowed_root()
    groups = [_checked_path(path, allowed, exists=True) for path in groups]
    baseline = _checked_path(baseline, allowed, exists=True)
    output = _checked_path(output, allowed, exists=False)
    if output.exists() or len(set(groups)) != len(groups) or baseline in groups:
        raise ValueError("새 출력과 중복 없는 독립 생성 그룹이 필요합니다")
    base, base_hash = _read_json(baseline / "generation.json")
    originals = _saved_index(base, "baseline")
    manifests, paths, hashes = [], [], []
    for directory in groups:
        manifest, digest = _read_json(directory / "generation.json")
        manifests.append(manifest)
        paths.append(str(directory / "generation.json"))
        hashes.append(digest)
    joined = join_manifest_data(manifests, base, group_paths=paths, group_hashes=hashes,
                                baseline_path=str(baseline / "generation.json"), baseline_hash=base_hash)
    index_rows = []
    for batch in joined["batches"]:
        directory = _checked_path(Path(batch["directory"]), allowed, exists=True)
        parent = Path(batch["lineage"]["manifest"]).parent
        if directory != parent / batch["type"] / batch["closing_month"]:
            raise ValueError("새 배치 경로가 해당 생성 그룹과 다릅니다")
        old = originals[(batch["type"], batch["closing_month"])]
        old_directory = _checked_path(Path(old["directory"]), allowed, exists=True)
        for folder, expected in ((directory, batch), (old_directory, old)):
            if _sha256(_checked_path(folder / "details.jsonl.gz", allowed, exists=True)) != expected["master_details_sha256"]:
                raise ValueError("고정 생성 상세 해시가 달라졌습니다")
        source_hashes = _source_audit(directory, batch)
        if source_hashes != _source_audit(old_directory, old):
            raise ValueError("v2와 재파싱한 조직 원본의 저장 해시가 다릅니다")
        batch["source_sha256_by_path"] = source_hashes
        batch["master_details_hash_verified"] = True
        batch["source_records_sha256"] = _sha256(_checked_path(directory / "source_records.jsonl.gz", allowed, exists=True))
        batch["master_metadata_sha256"] = _sha256(_checked_path(directory / "master.json", allowed, exists=True))
        index_rows.append({"type": batch["type"], "closing_month": batch["closing_month"],
                           "master_json": str(directory / "master.json"), "master_xlsx": str(directory / "master.xlsx"),
                           "selected_batch_directory": str(directory), "source_generation_manifest": batch["lineage"]["manifest"],
                           "generation_implementation_sha256": batch["generation_implementation_sha256"],
                           "master_details_sha256": batch["master_details_sha256"], "original_source_hashes_match_v2": True,
                           "xlsx_export_status": "not_checked_by_join"})
    if _sha256(baseline / "generation.json") != base_hash or any(_sha256(Path(p)) != h for p, h in zip(paths, hashes)):
        raise ValueError("join 도중 입력 manifest가 변경되었습니다")
    joined.update(created_at_utc=datetime.now(timezone.utc).isoformat(), command=sys.argv,
                  join_implementation_sha256=_sha256(Path(__file__).resolve()), join_seconds=time.perf_counter() - started,
                  original_source_hashes_match_v2=True, join_inputs_unchanged=True, written=write_output)
    if write_output:
        output.mkdir(exist_ok=False)
        _write_json(output / "generation.json", joined)
        _write_csv(output / "finalmaster_index.csv", index_rows)
    return joined


def _amount_signature(value: object) -> str | None:
    """유한 정규금액의 숫자 표현 차이를 제거한다.

    Args: value - 정규금액 또는 빈 값.
    Returns: 반올림 없는 십진 문자열 또는 None.
    Raises: ValueError - 비유한·잘못된 정규금액.
    Side Effects: 메모리 연산만 수행한다.
    """
    if value is None or value == "":
        return None
    try:
        parsed = Decimal(str(value))
    except InvalidOperation as error:
        raise ValueError("유한 정규금액이 아닙니다") from error
    if not parsed.is_finite():
        raise ValueError("유한 정규금액이 아닙니다")
    text = format(parsed, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return "0" if parsed == 0 else text


def semantic_payload(record: dict) -> dict:
    """원본 셀별 파서의 업무 의미·금액·상태를 회귀 비교용으로 투영한다.

    Args: record - 저장 source_records의 평탄 파서 출력.
    Returns: 진단 문구·실행 시각을 제외한 고정 핵심 필드 사전.
    Raises: ValueError - 정규금액 계약 오류.
    Side Effects: 입력을 변경하지 않는다. 새 검사 필드의 부재 자체는 차이가 아니다.
    """
    result = {field: deepcopy(record.get(field)) for field in SEMANTIC_FIELDS}
    result["normalized_value"] = _amount_signature(record.get("normalized_value"))
    return result


def compare_source_records(before: list[dict], after: list[dict]) -> dict:
    """같은 원본 셀의 의미·금액 출력을 다중집합으로 대조한다.

    Args: before/after - 저장된 이전·새 파서 출력 전체.
    Returns: 일치·추가·제거 수, 의미 변경 셀과 양쪽 원문 투영 전체.
    Raises: ValueError - 금액 계약 오류.
    Side Effects: 없음. 중복을 삭제하거나 입력 순서로 임의 연결하지 않는다.
    """
    indexes = []
    for records in (before, after):
        grouped = defaultdict(Counter)
        for record in records:
            location = tuple(record.get(field) for field in ("source_file", "sheet", "cell"))
            payload = json.dumps(semantic_payload(record), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            grouped[location][payload] += 1
        indexes.append(grouped)
    same = removed = added = 0
    changes = []
    for location in sorted(indexes[0].keys() | indexes[1].keys(), key=repr):
        left, right = indexes[0].get(location, Counter()), indexes[1].get(location, Counter())
        same += sum((left & right).values())
        removed += sum((left - right).values())
        added += sum((right - left).values())
        if left != right:
            changes.append({"source_file": location[0], "sheet": location[1], "cell": location[2],
                            "before": [{"record": json.loads(key), "count": count} for key, count in sorted(left.items())],
                            "after": [{"record": json.loads(key), "count": count} for key, count in sorted(right.items())]})
    return {"before_record_count": len(before), "after_record_count": len(after), "same_record_count": same,
            "removed_record_count": removed, "added_record_count": added, "changed_cell_count": len(changes),
            "identical": removed == added == 0, "changes": changes}


def _read_jsonl(path: Path) -> list[dict]:
    """저장 압축 상세를 읽고 읽는 동안의 해시 불변성을 확인한다.

    Args: path - 승인된 생성 또는 오류 JSONL.gz.
    Returns: 모든 상세 사전 목록.
    Raises: OSError, ValueError - 경계·압축·JSON·입력 변경 오류.
    Side Effects: 지정 산출물만 읽는다.
    """
    path = _checked_path(path, _allowed_root(), exists=True)
    before = _sha256(path)
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        rows = [json.loads(line) for line in stream]
    if _sha256(path) != before:
        raise ValueError("압축 상세를 읽는 중 파일이 바뀌었습니다")
    return rows


def metric_snapshot(summary: dict, errors: list[dict]) -> dict:
    """평가 요약과 오류 원문으로 요청된 before/after 지표를 확정한다.

    Args: summary - 월별 평가 요약. errors - 해당 월 전체 오류 상세.
    Returns: 건수·합계 일치·비율 지표. nonzero_FP는 extra와 금액 불일치의 비0 출력이다.
    Raises: ValueError - 정규금액 오류.
    Side Effects: 메모리 연산만 수행한다.
    """
    result = {field: summary.get(field, 0) for field in METRIC_FIELDS}
    result["nonzero_fp"] = sum(row.get("status") in {"extra", "amount_mismatch"}
                               and _amount_signature(row.get("output_value")) not in (None, "0") for row in errors)
    result["amount_mismatch"] = summary.get("amount_mismatch", summary.get("error_counts", {}).get("amount_mismatch", 0))
    result.update(all_totals_match=summary.get("all_totals_match"), generation_status=summary.get("generation_status"),
                  precision=summary.get("precision"), recall=summary.get("recall"), f1=summary.get("f1"))
    return result


def compare_evaluation_rows(before: list[dict], after: list[dict], *, totals: bool = False) -> dict:
    """평가 오류 또는 합계의 업무키·양쪽 값을 다중집합으로 대조한다.

    Args: before/after - 저장 평가 상세. totals - True이면 합계 계약 필드 사용.
    Returns: 일치 여부, 제거·추가 수 및 해당 상세 전체.
    Raises: ValueError - 정규금액 오류.
    Side Effects: 메모리만 사용하며 진단 문구 차이로 금액 회귀를 만들지 않는다.
    """
    if totals:
        fields = ("scope", "organization_code", "domain", "period_group", "year", "month", "scenario", "matching", "count_complete",
                  "truth_count", "output_count", "truth_total", "output_total", "difference_won")
        amount_fields = {"truth_total", "output_total", "difference_won"}
    else:
        fields = ("status", "type", "closing_month", "domain", "source_file", "sheet", "cell",
                  "truth_file", "truth_sheet", "truth_cell", "record_key", "truth_value", "output_value",
                  "duplicate", "truth_revision", "output_revision", "value_status")
        amount_fields = {"truth_value", "output_value"}
    counters = []
    for rows in (before, after):
        counter = Counter()
        for row in rows:
            payload = {field: _amount_signature(row.get(field)) if field in amount_fields else row.get(field) for field in fields}
            counter[json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))] += 1
        counters.append(counter)
    changes = []
    for side, difference in (("removed", counters[0] - counters[1]), ("added", counters[1] - counters[0])):
        for serialized, count in sorted(difference.items()):
            changes.append({"change": side, "count": count, "record": json.loads(serialized)})
    return {"identical": counters[0] == counters[1],
            "removed_count": sum((counters[0] - counters[1]).values()),
            "added_count": sum((counters[1] - counters[0]).values()), "changes": changes}


def _aggregate_matrix(rows: list[dict]) -> list[dict]:
    """월별 건수를 유형별 및 전체 마이크로 before/after로 합친다.

    Args: rows - 월별 before/after 및 회귀 결과.
    Returns: 유형과 ALL의 단일 행 목록.
    Raises: KeyError - 내부 비교 계약 위반.
    Side Effects: 메모리 연산만 수행한다.
    """
    grouped = defaultdict(list)
    for row in rows:
        grouped[row["type"]].append(row)
        grouped["ALL"].append(row)
    matrix = []
    for label, scoped in sorted(grouped.items()):
        out = {"type": label, "months": len(scoped)}
        for side in ("before", "after"):
            for field in METRIC_FIELDS:
                out[side + "_" + field] = sum(row[side][field] for row in scoped)
            out[side + "_all_totals_match_months"] = sum(row[side]["all_totals_match"] is True for row in scoped)
            matched, expected, confirmed = (out[side + "_" + field] for field in ("match_count", "truth_count", "confirmed_output_count"))
            out[side + "_precision"] = matched / confirmed if confirmed else 0.0
            out[side + "_recall"] = matched / expected if expected else None
            out[side + "_f1"] = 2 * matched / (expected + confirmed) if expected else None
        for field in METRIC_FIELDS:
            out["delta_" + field] = out["after_" + field] - out["before_" + field]
        out["source_semantic_changed_cells"] = sum(row["source_comparison"]["changed_cell_count"] for row in scoped)
        out["nonregression_failures"] = sum(row["nonregression_required"] and not row["nonregression_pass"] for row in scoped)
        matrix.append(out)
    return matrix


def compare_runs(baseline: Path, current: Path, output: Path, *, write_output: bool = False) -> dict:
    """완료된 두 평가와 실제 재파싱 출력을 81개 키별로 비교한다.

    Args: baseline - v2 final. current - 새 final. output - 새 회귀 결과 폴더.
        write_output - 명시 저장 요청 여부.
    Returns: 월별·유형별 before/after, 8유형 회귀 판정과 입력 해시.
    Raises: OSError, ValueError - 미완료·범위·동일 원본·해시 오류.
    Side Effects: 저장 산출물만 읽고 요청된 경우 새 JSON/CSV/의미 차이 JSONL을 쓴다.
    """
    allowed = _allowed_root()
    baseline, current = (_checked_path(path, allowed, exists=True) for path in (baseline, current))
    output = _checked_path(output, allowed, exists=False)
    if output.exists():
        raise ValueError("기존 회귀 결과를 덮어쓰지 않습니다")
    old_manifest, old_hash = _read_json(baseline / "generation.json")
    new_manifest, new_hash = _read_json(current / "generation.json")
    old_index, new_index = _saved_index(old_manifest, "baseline"), _saved_index(new_manifest, "current")
    if len(old_index) != 81 or set(old_index) != set(new_index) or old_manifest["inventory_sha256"] != new_manifest["inventory_sha256"]:
        raise ValueError("동일한 81개 생성·평가 범위가 필요합니다")
    evaluations, evaluation_hashes = [], []
    for root in (baseline, current):
        report, digest = _read_json(root / "evaluation/validation.json")
        indexed = {(row["type"], row["closing_month"]): row for row in report["rows"]}
        if len(report["rows"]) != 81 or set(indexed) != set(old_index):
            raise ValueError("81개 평가가 모두 완료되어야 합니다")
        evaluations.append(indexed)
        evaluation_hashes.append(digest)
    rows, changes, evaluation_changes = [], [], []
    for key in old_index:
        previous, revised = old_index[key], new_index[key]
        for field in ("organization_files", "organization_count", "master"):
            if previous[field] != revised[field]:
                raise ValueError("before/after 원본 대응이 다릅니다")
        old_dir, new_dir = (Path(batch["directory"]) for batch in (previous, revised))
        if old_dir == new_dir:
            raise ValueError("새 코드로 재파싱하지 않은 기존 출력 참조입니다")
        if _source_audit(old_dir, previous) != _source_audit(new_dir, revised):
            raise ValueError("before/after 원본 해시가 다릅니다")
        for folder, batch in ((old_dir, previous), (new_dir, revised)):
            if _sha256(_checked_path(folder / "details.jsonl.gz", allowed, exists=True)) != batch["master_details_sha256"]:
                raise ValueError("before/after 생성 상세 해시 오류")
            if batch.get("source_records_sha256") and _sha256(folder / "source_records.jsonl.gz") != batch["source_records_sha256"]:
                raise ValueError("join 이후 source_records 해시가 다릅니다")
        compared = compare_source_records(_read_jsonl(old_dir / "source_records.jsonl.gz"), _read_jsonl(new_dir / "source_records.jsonl.gz"))
        for change in compared.pop("changes"):
            changes.append({"type": key[0], "closing_month": key[1], **change})
        metrics, error_rows, total_rows = [], [], []
        for root, evaluation in zip((baseline, current), evaluations):
            errors = _read_jsonl(root / "evaluation" / key[0] / key[1] / "errors.jsonl.gz")
            metrics.append(metric_snapshot(evaluation[key], errors))
            error_rows.append(errors)
            target = _checked_path(root / "evaluation" / key[0] / key[1] / "totals.json", allowed, exists=True)
            digest = _sha256(target)
            total_rows.append(json.loads(target.read_text(encoding="utf-8")))
            if _sha256(target) != digest:
                raise ValueError("평가 합계를 읽는 중 파일이 바뀌었습니다")
        error_comparison = compare_evaluation_rows(error_rows[0], error_rows[1])
        total_comparison = compare_evaluation_rows(total_rows[0], total_rows[1], totals=True)
        for kind, comparison in (("error", error_comparison), ("total", total_comparison)):
            for change in comparison.pop("changes"):
                evaluation_changes.append({"type": key[0], "closing_month": key[1], "kind": kind, **change})
        required = key[0][:2] != "05"
        equal = metrics[0] == metrics[1] and error_comparison["identical"] and total_comparison["identical"]
        rows.append({"type": key[0], "closing_month": key[1], "before": metrics[0], "after": metrics[1],
                     "evaluation_identical": equal, "error_comparison": error_comparison, "total_comparison": total_comparison,
                     "source_comparison": compared, "original_source_hashes_match": True,
                     "nonregression_required": required, "nonregression_pass": equal and compared["identical"]})
        print(f"regression {len(rows)}/81 {key[0]}/{key[1]} source_changes={compared['changed_cell_count']}", flush=True)
    input_hashes = {str(baseline / "generation.json"): old_hash, str(current / "generation.json"): new_hash,
                    str(baseline / "evaluation/validation.json"): evaluation_hashes[0],
                    str(current / "evaluation/validation.json"): evaluation_hashes[1]}
    if any(_sha256(Path(path)) != digest for path, digest in input_hashes.items()):
        raise ValueError("회귀 비교 도중 입력 목록·평가가 변경되었습니다")
    matrix = _aggregate_matrix(rows)
    result = {"contract_version": "reparsed-source-regression-v1", "created_at_utc": datetime.now(timezone.utc).isoformat(),
              "command": sys.argv, "input_sha256": input_hashes, "inputs_unchanged": True,
              "source_excel_reads": 0, "reference_excel_reads": 0, "compared_batches": len(rows),
              "nonregression_batches": sum(row["nonregression_required"] for row in rows),
              "nonregression_pass": all(row["nonregression_pass"] for row in rows if row["nonregression_required"]),
              "nonzero_fp_definition": "unmatched confirmed nonzero outputs: extra or amount_mismatch",
              "semantic_fields": list(SEMANTIC_FIELDS) + ["normalized_value"], "rows": rows, "matrix": matrix,
              "semantic_changed_cell_count": len(changes), "evaluation_changed_records": len(evaluation_changes), "written": write_output}
    if write_output:
        output.mkdir(exist_ok=False)
        _write_json(output / "comparison.json", result)
        _write_csv(output / "before_after.csv", matrix)
        monthly = []
        for row in rows:
            entry = {"type": row["type"], "closing_month": row["closing_month"]}
            for side in ("before", "after"):
                entry.update({side + "_" + key: value for key, value in row[side].items()})
            entry.update(nonregression_required=row["nonregression_required"], nonregression_pass=row["nonregression_pass"],
                         source_semantic_changed_cells=row["source_comparison"]["changed_cell_count"])
            monthly.append(entry)
        _write_csv(output / "before_after_monthly.csv", monthly)
        with gzip.open(output / "source_semantic_changes.jsonl.gz", "xt", encoding="utf-8") as stream:
            for change in changes:
                stream.write(json.dumps(change, ensure_ascii=False, separators=(",", ":")) + "\n")
        with gzip.open(output / "evaluation_changes.jsonl.gz", "xt", encoding="utf-8") as stream:
            for change in evaluation_changes:
                stream.write(json.dumps(change, ensure_ascii=False, separators=(",", ":")) + "\n")
    return result


def main(argv: list[str] | None = None) -> int:
    """join 또는 compare를 실행하고 저장 여부와 완료 상태를 출력한다.

    Args: argv - CLI 인자. None이면 실제 명령행.
    Returns: 성공 0, compare의 8유형 회귀 실패 2.
    Raises: OSError, ValueError - 입력·경계·검증·저장 오류.
    Side Effects: 명시 --write-output에서만 새 회귀 산출물을 만든다.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    join = commands.add_parser("join")
    join.add_argument("--groups", nargs="+", type=Path, required=True)
    compare = commands.add_parser("compare")
    compare.add_argument("--current", type=Path, required=True)
    for command in (join, compare):
        command.add_argument("--baseline", type=Path, required=True)
        command.add_argument("--output", type=Path, required=True)
        command.add_argument("--write-output", action="store_true")
    options = parser.parse_args(argv)
    if options.command == "join":
        result = join_runs(options.groups, options.baseline, options.output, write_output=options.write_output)
        print(json.dumps({"joined_batches": len(result["batches"]), "source_hashes_match_v2": result["original_source_hashes_match_v2"], "written": result["written"]}))
        return 0
    result = compare_runs(options.baseline, options.current, options.output, write_output=options.write_output)
    print(json.dumps({"compared_batches": result["compared_batches"], "nonregression_pass": result["nonregression_pass"], "written": result["written"]}))
    return 0 if result["nonregression_pass"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
