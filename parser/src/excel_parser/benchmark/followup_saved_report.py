"""기존 평가·생성 JSON만으로 v2/v3의 합계 차이와 제외 잠재 영향을 대조한다."""

from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import json
from collections import Counter, defaultdict
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from typing import Iterator

FIELDS = ("scope", "organization_code", "domain", "period_group", "year", "month", "scenario")


def checked(path: Path, roots: tuple[Path, ...], *, exists: bool = True) -> Path:
    """읽기·쓰기를 명시한 실행 산출물 루트로 제한한다.

    Args:
        path: 검사할 산출물.
        roots: 허용된 v2/v3 실행 루트.
        exists: 읽기 대상이면 True.
    Returns:
        실제 허용 경로.
    Raises:
        ValueError, OSError: 범위·Data·심볼릭 링크·부재 문제.
    Side Effects:
        경로 메타데이터만 조회한다.
    """
    original = path.absolute()
    if any(part.lower() == "data" for part in original.parts):
        raise ValueError("원본 Excel 경로는 열지 않습니다")
    resolved = original.resolve(strict=exists)
    if not any(resolved.is_relative_to(root) and resolved != root for root in roots) or original != resolved:
        raise ValueError("실행 산출물의 실제 경로만 허용합니다")
    if original.is_symlink() or any(parent.is_symlink() for parent in original.parents):
        raise ValueError("심볼릭 링크 산출물은 읽거나 쓰지 않습니다")
    return resolved


def sha(path: Path) -> str:
    """검사된 산출물의 보존 해시를 구한다.

    Args:
        path: 검사된 파일 경로.
    Returns:
        SHA256 문자열.
    Raises:
        OSError: 파일 읽기 오류.
    Side Effects:
        지정 산출물만 읽는다.
    """
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def rows(path: Path) -> Iterator[dict]:
    """저장된 gzip JSONL을 순서대로 읽는다.

    Args:
        path: 검사된 산출물 경로.
    Returns:
        JSON 사전 반복자.
    Raises:
        OSError, ValueError: 압축·JSON 오류.
    Side Effects:
        지정 산출물만 읽는다.
    """
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        for line in stream:
            yield json.loads(line)


def amount(value: object) -> Decimal | None:
    """빈 값과 실제 0을 구분하여 저장 정규금액만 읽는다.

    Args:
        value: 저장 금액 또는 None.
    Returns:
        유한 Decimal 또는 None.
    Raises:
        ValueError: 비유한 금액.
    Side Effects:
        없음.
    """
    if value is None or value == "":
        return None
    number = Decimal(str(value))
    if not number.is_finite():
        raise ValueError("유한 금액만 분석합니다")
    return number


def location(record: dict) -> tuple:
    """저장 출처의 파일·시트·셀 튜플을 구한다.

    Args:
        record: 저장 레코드 또는 오류.
    Returns:
        원본을 열지 않는 출처 튜플.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    return tuple(record.get(field) for field in ("source_file", "sheet", "cell"))


def issue_category(record: dict) -> str:
    """필수 미확정의 조직·업무·금액 원인을 서로 구분한다.

    Args:
        record: 선택 또는 제외 상세.
    Returns:
        단일 주원인. 선택 빈칸은 별도 범주다.
    Raises:
        없음.
    Side Effects:
        없음. 필드가 없는 이유를 추측하지 않는다.
    """
    if record.get("assembly_status") == "missing_optional":
        return "optional_blank"
    if record.get("organization_code") in (None, "", "unknown") or record.get("organization_status") not in (None, "resolved"):
        return "organization_invalid"
    if (record.get("business_validation") or {}).get("status") == "invalid":
        return "business_invalid"
    if record.get("account_code") in (None, "", "unknown"):
        return "account_unresolved"
    if record.get("detail_id") in (None, "", "unknown"):
        return "detail_id_unresolved"
    if record.get("raw_value") is None and not record.get("formula"):
        return "amount_blank"
    if record.get("formula") and record["formula"].get("cached_status") not in {"stored_unverified", "confirmed"}:
        return "formula_cache_unresolved"
    return "other_unresolved"


def group_keys(record: dict) -> list[tuple]:
    """같은 상세를 전사·원래 조직 기준 합계 키로 표시한다.

    Args:
        record: 원래 조직·기간을 보존한 상세.
    Returns:
        전사와 조직 두 키. 조직 미확정이면 None 그대로 남긴다.
    Raises:
        없음.
    Side Effects:
        없음. 제출 소유자·ID·파일명으로 조직을 보완하지 않는다.
    """
    tail = tuple(record.get(field) for field in FIELDS[2:])
    return [("company", None, *tail), ("organization", record.get("organization_code"), *tail)]


def extra_reason(record: dict, blank_rows: set, current_record: dict | None) -> str:
    """포함 extra의 출처 속성과 현재 원본 검증 근거를 구분해 표시한다.

    Args:
        record: 분석 중인 저장 상세.
        blank_rows: 필수 빈 금액이 있는 원본행 집합.
        current_record: 같은 원본 셀의 현재 저장 상세. ID 연결을 하지 않는다.
    Returns:
        진단 사유이며 기존 평가 상태는 그대로다.
    Raises:
        ValueError: 금액 계약 위반.
    Side Effects:
        없음.
    """
    if amount(record.get("normalized_value")) == 0:
        return "actual_zero_extra"
    if record.get("account_code") == "999999":
        return "literal_account_999999"
    if record.get("detail_id") == "None":
        return "literal_detail_id_None"
    issues = ((current_record or {}).get("business_validation") or {}).get("issues", [])
    if any(issue.get("code") == "BUSINESS_MANAGEMENT_CATEGORY_INVALID" for issue in issues):
        return "source_management_category_unregistered"
    if record.get("submission_owner") != record.get("organization_code"):
        return "source_row_organization_differs_from_submission_owner"
    if (record.get("source_file"), record.get("sheet"), record.get("source_row")) in blank_rows:
        return "other_months_of_source_row_with_required_blank"
    return "unexplained_extra"


def load_batch(run: Path, batch: dict, roots: tuple[Path, ...]) -> dict:
    """한 월의 저장 생성·평가 자료와 보호 해시를 읽는다.

    Args:
        run: 완료된 평가 실행 폴더.
        batch: 고정 생성 manifest의 배치.
        roots: 허용 산출물 루트.
    Returns:
        상세·제외·오류·합계·메타데이터·원본 0 건수·해시.
    Raises:
        OSError, ValueError: 경계·JSON·생성 상세 해시 오류.
    Side Effects:
        저장 JSON/GZ만 읽고 원본 Excel 경로는 문자열로만 보존한다.
    """
    directory = checked(Path(batch["directory"]), roots)
    evaluation = checked(run / "evaluation" / batch["type"] / batch["closing_month"], roots)
    paths = {"details": directory / "details.jsonl.gz", "excluded": directory / "excluded_records.jsonl.gz",
             "metadata": directory / "master.json", "source": directory / "source_records.jsonl.gz",
             "errors": evaluation / "errors.jsonl.gz", "totals": evaluation / "totals.json", "summary": evaluation / "summary.json"}
    protected = {str(checked(path, roots)): sha(path) for path in paths.values()}
    if protected[str(paths["details"])] != batch["master_details_sha256"]:
        raise ValueError("고정된 생성 상세 해시가 다릅니다")
    result = {key: list(rows(paths[key])) for key in ("details", "excluded", "errors")}
    result.update({key: json.loads(paths[key].read_text(encoding="utf-8")) for key in ("metadata", "totals", "summary")})
    result["source_actual_zero_count"] = sum(record.get("confirmed") is True and amount(record.get("normalized_value")) == 0 for record in rows(paths["source"]))
    result["protected"] = protected
    return result


def analyze_batch(data: dict, current_index: dict) -> dict:
    """저장 합계의 차액을 오류 기여분으로 연결하고 제외 잠재 영향을 분리한다.

    Args:
        data: load_batch의 결과.
        current_index: 같은 원본 해시를 가진 현재 상세의 출처 색인.
    Returns:
        그룹별 연결, 건별 오류 기여, 제외 잠재 영향, 필수 식별·금액 미확정 감사.
        원본상 선택적인 next_projection 빈칸은 업무 식별 오류 여부와 별도로 센다.
    Raises:
        ValueError: 출처 중복·그룹 모호성·합계 불일치.
    Side Effects:
        없음. 금액·키·strict FP/FN을 바꾸지 않는다.
    """
    index, blank_rows = {}, set()
    for record in data["details"]:
        key = location(record)
        if key in index:
            raise ValueError("같은 생성 출처를 임의로 중복 제거하지 않습니다")
        index[key] = record
        if record.get("assembly_status") == "unresolved" and issue_category(record) == "amount_blank":
            blank_rows.add((record.get("source_file"), record.get("sheet"), record.get("source_row")))
    totals = {tuple(row.get(field) for field in FIELDS): row for row in data["totals"]}
    if len(totals) != len(data["totals"]):
        raise ValueError("저장 합계 키가 중복됩니다")
    included_sums, contributions, buckets = defaultdict(Decimal), defaultdict(Decimal), defaultdict(lambda: defaultdict(Decimal))
    potentials, unknowns = defaultdict(Decimal), Counter()
    audit, reasons = Counter(), Counter()
    for name in ("selected_raw_optional_blank", "excluded_raw_optional_blank",
                 "business_invalid_known_amount_slots", "business_invalid_unknown_amount_slots",
                 "business_invalid_optional_amount_blank", "organization_invalid_known_amount_slots",
                 "organization_invalid_unknown_amount_slots", "organization_invalid_optional_amount_blank",
                 "amount_blank_unknown_amount_slots", "selected_formula_cache_missing_or_error"):
        audit[name] = 0
    group_has_value = defaultdict(bool)
    for record in data["details"] + data["excluded"]:
        if record.get("exclusion_reason") == "outside_monthly_detail_scope":
            continue
        group = (record.get("domain"), record.get("period_group"))
        group_has_value[group] |= (record.get("raw_value") not in (None, "")
                                   or amount(record.get("normalized_value")) is not None
                                   or bool(record.get("formula")))
    contribution_rows, potential_rows = [], []
    for record in data["details"]:
        value = amount(record.get("normalized_value"))
        if record.get("formula") and record["formula"].get("cached_status") not in {"stored_unverified", "confirmed"}:
            audit["selected_formula_cache_missing_or_error"] += 1
        if record.get("confirmed") is True and value == 0:
            audit["selected_actual_zero"] += 1
        if record.get("included_in_totals"):
            for key in group_keys(record):
                included_sums[key] += value
            if value == 0:
                audit["included_actual_zero"] += 1
    for state, source in (("selected", data["details"]), ("excluded", data["excluded"])):
        for record in source:
            if state == "excluded" and record.get("exclusion_reason") in {"superseded_detail_revision", "outside_monthly_detail_scope"}:
                audit["routine_excluded_slots"] += 1
                continue
            value = amount(record.get("normalized_value"))
            raw_optional = (record.get("period_group") == "next_projection"
                            and not group_has_value[(record.get("domain"), record.get("period_group"))]
                            and record.get("raw_value") in (None, "")
                            and not record.get("formula") and value is None)
            if raw_optional:
                audit[state + "_raw_optional_blank"] += 1
            if state == "selected" and record.get("included_in_totals"):
                continue
            category = issue_category(record)
            if category == "optional_blank":
                audit["optional_blank"] += 1
                continue
            audit[state + "_required_unresolved"] += 1
            audit[category] += 1
            audit["excluded_known_amount_slots" if value is not None else "unknown_potential_slots"] += 1
            audit[category + ("_known_amount_slots" if value is not None else "_unknown_amount_slots")] += 1
            if raw_optional:
                audit[category + "_optional_amount_blank"] += 1
            for issue in (record.get("business_validation") or {}).get("issues", []):
                reasons[issue["code"]] += 1
            for key in group_keys(record):
                if value is None:
                    unknowns[key] += 1
                else:
                    potentials[key] += value
            potential_rows.append({"record_state": state, "category": category, "known_excluded_won": str(value) if value is not None else None,
                                   "unknown_potential": value is None, "raw_optional_amount_blank": raw_optional,
                                   "included_in_difference": False, "record": record})
    for error in data["errors"]:
        status = error["status"]
        record = None if status == "missing" else index.get(location(error))
        key_record = dict(record or error["record_key"])
        included = bool(record and record.get("included_in_totals"))
        output_value = amount(error.get("output_value"))
        truth_value = amount(error.get("truth_value"))
        positive = output_value if included and output_value is not None else Decimal(0)
        negative = truth_value if status in {"missing", "amount_mismatch", "unresolved", "duplicate_matched"} and truth_value is not None else Decimal(0)
        contribution = positive - negative
        if status == "extra":
            category = "included_extra" if included else "extra_excluded_from_totals"
        elif status == "unresolved" and truth_value is not None:
            category = "unresolved_truth_missing"
        else:
            category = status
        resolved_keys = []
        for key in group_keys(key_record):
            if key[3] is None:
                candidates = [saved for saved in totals if saved[:3] == key[:3] and saved[4:] == key[4:]]
                if len(candidates) != 1 and contribution != 0:
                    raise ValueError("정답-only 기여분의 저장 기간 그룹이 유일하지 않습니다")
                key = candidates[0] if len(candidates) == 1 else key
            contributions[key] += contribution
            buckets[key][category] += contribution
            resolved_keys.append(dict(zip(FIELDS, key)))
        reason = extra_reason(record, blank_rows, current_index.get(location(record))) if record and status == "extra" else category
        contribution_rows.append({"category": category, "diagnostic_reason": reason, "contribution_won": str(contribution),
                                  "included_output_won": str(positive), "truth_deduction_won": str(negative),
                                  "included_in_totals": included, "assembly_status": (record or {}).get("assembly_status"),
                                  "source_file": (record or {}).get("source_file") or error.get("organization_file"),
                                  "groups": resolved_keys, "original_error": error})
    bridges = []
    for key in sorted(totals.keys() | potentials.keys() | unknowns.keys() | contributions.keys() | included_sums.keys(), key=repr):
        total = totals.get(key)
        if total and included_sums[key] != Decimal(total["output_total"]):
            raise ValueError("included_in_totals 상세 합계와 저장 합계가 다릅니다")
        difference = Decimal(total["output_total"]) - Decimal(total["truth_total"]) if total else None
        if total is None and contributions[key] != 0:
            raise ValueError("금액 기여가 있는데 저장 합계 그룹이 없습니다")
        bridges.append({**dict(zip(FIELDS, key)), "has_saved_total": total is not None,
                        "truth_total_won": total["truth_total"] if total else None,
                        "output_total_won": total["output_total"] if total else None,
                        "difference_won": str(difference) if difference is not None else None,
                        "included_error_contribution_won": str(contributions[key]),
                        "numeric_residual_won": str(difference - contributions[key]) if difference is not None else None,
                        "contribution_categories_won": {k: str(v) for k, v in buckets[key].items()},
                        "excluded_known_amount_separate_won": str(potentials[key]),
                        "unknown_potential_count": unknowns[key], "excluded_is_part_of_difference": False})
    audit["source_actual_zero"] = data["source_actual_zero_count"]
    audit["required_unresolved_total"] = audit["selected_required_unresolved"] + audit["excluded_required_unresolved"]
    return {"bridges": bridges, "contributions": contribution_rows, "excluded_potential": potential_rows,
            "audit": dict(audit), "business_issue_counts": dict(reasons)}


def save_rows(path: Path, records: list[dict]) -> None:
    """새 분석 JSONL을 압축하여 배타적으로 저장한다.

    Args:
        path: 아직 없는 출력 경로.
        records: 보존할 모든 분석 행.
    Returns:
        없음.
    Raises:
        OSError, TypeError: 저장·직렬화 오류.
    Side Effects:
        지정 새 gzip 파일 한 개만 생성한다.
    """
    with gzip.open(path, "xt", encoding="utf-8") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")


def run_saved_report(baseline: Path, current: Path, output: Path, *, type_prefix: str = "05",
                     write_output: bool = False) -> dict:
    """완료된 두 실행의 지정 유형을 저장 자료만으로 대조한다.

    Args:
        baseline: comparisons 안의 이전 완료 실행 폴더.
        current: 별도 실행 루트 안의 현재 완료 실행 폴더.
        output: 현재 실행 루트 안의 아직 없는 사후 분석 폴더.
        type_prefix: 목록에서 선택할 두 자리 유형 접두어.
        write_output: 사용자가 요청한 새 산출물 저장 여부. 기본은 읽기 전용이다.
    Returns:
        건별 감사·점수·보존 해시·전체 잔차 확인과 저장 여부를 가진 요약.
    Raises:
        OSError, ValueError, FileExistsError: 범위·동일 원본·합계·기존 출력 오류.
    Side Effects:
        생성·평가 산출물만 읽고 진행 상황을 출력한다. write_output=True에서만
        명시한 새 분석 폴더에 JSON/GZ/CSV/Markdown을 쓴다.
    """
    allowed = (Path(__file__).resolve().parents[3] / "output/comparisons").resolve()
    baseline = checked(baseline, (allowed,))
    current = checked(current, (allowed,))
    baseline_parts, current_parts = baseline.relative_to(allowed).parts, current.relative_to(allowed).parts
    if min(len(baseline_parts), len(current_parts)) < 2 or baseline_parts[0] == current_parts[0]:
        raise ValueError("comparisons의 서로 다른 실행 루트 아래 완료 폴더만 허용합니다")
    baseline_root, current_root = allowed / baseline_parts[0], allowed / current_parts[0]
    roots = (baseline_root, current_root)
    output = checked(output, (current_root,), exists=False)
    if output.exists():
        raise FileExistsError("기존 분석 결과를 덮어쓰지 않습니다")
    baseline_path, current_path = checked(baseline / "generation.json", roots), checked(current / "generation.json", roots)
    old = json.loads(baseline_path.read_text(encoding="utf-8"))
    new = json.loads(current_path.read_text(encoding="utf-8"))
    if old["inventory_sha256"] != new["inventory_sha256"]:
        raise ValueError("inventory 해시가 다릅니다")
    validation_paths = [checked(run / "evaluation/validation.json", roots) for run in (baseline, current)]
    protected = {str(path): sha(path) for path in (baseline_path, current_path, *validation_paths)}
    previous = {(batch["type"], batch["closing_month"]): batch for batch in old["batches"]}
    selected = [batch for batch in new["batches"] if batch["type"].startswith(type_prefix + "_")]
    if not selected:
        raise ValueError("현재 평가에 선택 유형이 없습니다")
    if write_output:
        output.mkdir(parents=True, exist_ok=False)
    summary_rows, group_rows, source_rows, excluded_source_rows = [], [], [], []
    audits = {"before": Counter(), "after": Counter()}
    for batch in selected:
        key = (batch["type"], batch["closing_month"])
        old_batch = previous[key]
        if any(old_batch[field] != batch[field] for field in ("organization_files", "organization_count", "master")):
            raise ValueError("동일 조직 원본·기준 마스터 경로 대응이 아닙니다")
        before, after = load_batch(baseline, old_batch, roots), load_batch(current, batch, roots)
        before_hashes = {row["source_file"]: row["source_sha256"] for row in before["metadata"]["selected_submissions"]}
        after_hashes = {row["source_file"]: row["source_sha256"] for row in after["metadata"]["selected_submissions"]}
        if before_hashes != after_hashes:
            raise ValueError("before/after의 저장 조직 원본 해시가 다릅니다")
        current_index = {location(row): row for row in after["details"]}
        target = checked(output / batch["type"] / batch["closing_month"], (output,), exists=False)
        if write_output:
            target.mkdir(parents=True)
        outcomes = {}
        for side, data in (("before", before), ("after", after)):
            result = analyze_batch(data, current_index)
            outcomes[side] = result
            audits[side].update(result["audit"])
            protected.update(data["protected"])
            if write_output:
                for name in ("bridges", "contributions", "excluded_potential"):
                    save_rows(target / (side + "_" + name + ".jsonl.gz"), result[name])
            source_sums, source_counts = defaultdict(Decimal), Counter()
            for row in result["contributions"]:
                for group in row["groups"]:
                    source_key = (tuple(group.get(field) for field in FIELDS), row["source_file"], row["category"], row["diagnostic_reason"])
                    source_sums[source_key] += Decimal(row["contribution_won"])
                    source_counts[source_key] += 1
            for (group_key, source_file, category, reason), value in source_sums.items():
                source_rows.append({"type": key[0], "closing_month": key[1], "side": side, **dict(zip(FIELDS, group_key)),
                                    "source_file": source_file, "category": category, "diagnostic_reason": reason,
                                    "record_count": source_counts[(group_key, source_file, category, reason)], "contribution_won": str(value)})
            excluded_sums, excluded_counts, excluded_unknowns, excluded_optional = defaultdict(Decimal), Counter(), Counter(), Counter()
            for row in result["excluded_potential"]:
                record = row["record"]
                for group in group_keys(record):
                    source_key = (group, record.get("source_file"), row["category"])
                    excluded_counts[source_key] += 1
                    excluded_unknowns[source_key] += row["unknown_potential"]
                    excluded_optional[source_key] += row["raw_optional_amount_blank"]
                    excluded_sums[source_key] += amount(row["known_excluded_won"]) or Decimal(0)
            for (group, source_file, category), count in excluded_counts.items():
                source_key = (group, source_file, category)
                excluded_source_rows.append({"type": key[0], "closing_month": key[1], "side": side, **dict(zip(FIELDS, group)),
                                             "source_file": source_file, "category": category, "record_count": count,
                                             "known_excluded_won": str(excluded_sums[source_key]),
                                             "unknown_amount_slots": excluded_unknowns[source_key],
                                             "optional_amount_blank_slots": excluded_optional[source_key],
                                             "included_in_difference": False})
        old_groups = {tuple(row.get(field) for field in FIELDS): row for row in outcomes["before"]["bridges"]}
        new_groups = {tuple(row.get(field) for field in FIELDS): row for row in outcomes["after"]["bridges"]}
        for group in sorted(old_groups.keys() | new_groups.keys(), key=repr):
            row = {"type": key[0], "closing_month": key[1], **dict(zip(FIELDS, group))}
            for side, groups in (("before", old_groups), ("after", new_groups)):
                value = groups.get(group, {})
                for field in ("has_saved_total", "truth_total_won", "output_total_won", "difference_won", "numeric_residual_won", "excluded_known_amount_separate_won", "unknown_potential_count"):
                    row[side + "_" + field] = value.get(field)
            group_rows.append(row)
        summary_rows.append({"type": key[0], "closing_month": key[1], "before_audit": outcomes["before"]["audit"],
                             "after_audit": outcomes["after"]["audit"], "before_business_issue_counts": outcomes["before"]["business_issue_counts"],
                             "after_business_issue_counts": outcomes["after"]["business_issue_counts"],
                             "before_strict_score": before["summary"], "after_strict_score": after["summary"],
                             "before_residual_nonzero_groups": sum(amount(row["numeric_residual_won"]) not in (None, 0) for row in outcomes["before"]["bridges"]),
                             "after_residual_nonzero_groups": sum(amount(row["numeric_residual_won"]) not in (None, 0) for row in outcomes["after"]["bridges"])})
        print(f"bridged {len(summary_rows)}/{len(selected)} {key[0]}/{key[1]}", flush=True)
    if any(sha(Path(path)) != value for path, value in protected.items()):
        raise ValueError("분석 중 저장 생성·평가 산출물이 변경되었습니다")
    result = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "baseline": str(baseline), "current": str(current),
              "postprocessing_code_sha256": sha(Path(__file__).resolve()), "postprocessing_only": True,
              "current_generation_implementation_sha256": new.get("implementation_sha256"),
              "written": write_output,
              "batch_count": len(selected), "source_excel_reads": 0, "reference_excel_reads": 0, "strict_scores_changed": False,
              "inputs_unchanged": True, "protected_sha256": protected, "audits": {side: dict(values) for side, values in audits.items()},
              "rows": summary_rows, "all_numeric_residuals_zero": all(row[side + "_residual_nonzero_groups"] == 0 for row in summary_rows for side in ("before", "after")),
              "excluded_amounts_are_separate": True}
    if not write_output:
        return result
    with (output / "summary.json").open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    for filename, records in (("remaining_difference_groups.csv", group_rows), ("source_contributions.csv", source_rows),
                              ("excluded_source_potential.csv", excluded_source_rows)):
        with (output / filename).open("x", encoding="utf-8-sig", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(records[0]))
            writer.writeheader()
            writer.writerows(records)
    lines = ["# 저장 결과의 합계 차이와 제외 잠재 영향", "",
             f"{len(selected)}개 제출월을 저장 JSON/GZ만으로 대조했다. 원본·기준 마스터 Excel 읽기는 0건이며 기존 점수는 변경하지 않았다.", "",
             f"모든 전사·조직별 저장 합계 그룹의 수치 잔차가 0인지: {result['all_numeric_residuals_zero']}", "",
             "합계 차액에는 실제 included_in_totals 출력과 오류에 연결된 정답 차감만 반영했다. 숫자가 확정되어 strict FP로 남더라도 business invalid로 합계에서 빠진 extra는 차액에 가산하지 않았다. 제외된 알려진 금액과 금액을 알 수 없는 슬롯은 별도 잠재 영향이며 차액에 다시 더하지 않는다.", "",
             "필수 미확정 슬롯은 업무·조직 식별과 금액을 구분한다. selected_raw_optional_blank는 원본상 선택적인 next_projection 빈칸이며 업무 키 검증 실패로 assembly_status가 unresolved가 된 빈칸도 포함한다. optional_blank는 조립기가 missing_optional로 분류한 수다. 따라서 두 수의 차이는 새 필수 금액 누락을 뜻하지 않는다. 조직 식별 불가로 상세 선택에서 제외된 optional 빈칸은 excluded_raw_optional_blank에 따로 남긴다.", "",
             "원본행 조직과 제출 소유자의 차이, 필수 빈칸이 있는 원본행의 다른 기간 등은 저장 자료에서 관측한 출처 패턴이다. 수치 잔차 0이나 이런 분류가 기준 마스터의 생략 이유 또는 업무 정책을 확정하지 않는다. 유효한 원본 출력을 정답에 맞추어 삭제하지 않으며 원래 생성 상태 partial과 strict FP를 유지한다.", "",
             "remaining_difference_groups.csv는 제출월·대상 연월·구분·조직/전사별 before/after, source_contributions.csv는 원본별 기여를 보존한다. excluded_source_potential.csv의 원본별 알려진 금액·금액 미상·선택적 빈칸은 실제 차액에 더하지 않는 별도 잠재 영향이다. 월별 압축 상세는 포함 extra·금액 불일치·정답 누락·미확정 정답 누락을 구분하고 정답 좌표와 원본 키를 남긴다.", ""]
    (output / "report.md").write_text("\n".join(lines), encoding="utf-8")
    return result


def main(argv: list[str] | None = None) -> int:
    """명시한 산출물 경로의 사후 분석 CLI를 실행한다.

    Args:
        argv: CLI 인자. None이면 실제 명령행을 사용한다.
    Returns:
        정상 분석 완료 시 종료 코드 0.
    Raises:
        OSError, ValueError, FileExistsError: 입력·범위·보존·기존 출력 오류.
    Side Effects:
        분석 요약을 출력한다. --write-output을 명시한 경우에만 새 산출물을 저장한다.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--current", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--type", default="05")
    parser.add_argument("--write-output", action="store_true")
    options = parser.parse_args(argv)
    result = run_saved_report(options.baseline, options.current, options.output,
                              type_prefix=options.type, write_output=options.write_output)
    print(json.dumps({"batch_count": result["batch_count"], "all_numeric_residuals_zero": result["all_numeric_residuals_zero"],
                      "written": result["written"], "audits": result["audits"], "output": str(options.output)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
