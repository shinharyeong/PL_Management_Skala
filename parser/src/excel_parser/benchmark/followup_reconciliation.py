"""저장 평가와 조직 원본만으로 한 기간의 전사 합계 차이를 원본 행까지 추적한다.

기준 마스터 Excel은 읽지 않는다. 제외 금액은 이미 저장 합계에 없는 별도
잠재 영향으로 보존하며 차액 설명에 다시 더하지 않는다.
"""

from __future__ import annotations

import argparse
import gzip
import json
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from typing import Iterator

from ..extraction import extract_workbook, file_fingerprint
from .cli import validate_output_path


def read_rows(path: Path) -> Iterator[dict]:
    """저장된 압축 JSONL의 모든 행을 순서대로 읽는다.

    Args:
        path: 허용된 생성 또는 평가 산출물 경로.
    Returns:
        저장 레코드 반복자.
    Raises:
        OSError, ValueError: 파일·압축·JSON 오류.
    Side Effects:
        지정 산출물만 읽는다.
    """
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        for line in stream:
            yield json.loads(line)


def location(record: dict) -> tuple:
    """조직 원본의 파일·시트·셀 식별자를 만들되 파일을 열지 않는다.

    Args:
        record: 생성 상세 또는 평가 오류.
    Returns:
        원래 출처를 보존한 튜플.
    Raises:
        KeyError: 출처 계약 위반.
    Side Effects:
        없음.
    """
    return tuple(record[field] for field in ("source_file", "sheet", "cell"))


def in_period(record: dict, year: int, month: int, scenario: str) -> bool:
    """저장된 기간 필드가 요청한 기간과 같은지 확인한다.

    Args:
        record: 생성 레코드 또는 오류의 record_key.
        year: 대상 연도.
        month: 대상 월.
        scenario: actual, plan 또는 estimate.
    Returns:
        세 필드가 정확히 일치하면 True.
    Raises:
        없음.
    Side Effects:
        없음. 기준월이나 ID로 기간을 보완하지 않는다.
    """
    return (record.get("year"), record.get("month"), record.get("scenario")) == (year, month, scenario)


def extra_category(record: dict, blank_rows: set) -> str:
    """저장 상세의 관찰 가능한 속성으로 포함 extra를 구분한다.

    Args:
        record: 실제 합계 포함 extra 레코드.
        blank_rows: 필수 빈 금액이 있는 원본행 출처 집합.
    Returns:
        진단 분류 코드. 기준 마스터의 제외 정책을 추측하지 않는다.
    Raises:
        ValueError: 확정 정규금액이 십진수가 아닌 경우.
    Side Effects:
        없음. 조직·계정·금액·평가 상태를 변경하지 않는다.
    """
    if Decimal(record["normalized_value"]) == 0:
        return "confirmed_zero"
    if record.get("account_code") == "999999":
        return "literal_account_999999"
    if record.get("detail_id") == "None":
        return "literal_detail_id_None"
    if record.get("submission_owner") != record.get("organization_code"):
        return "source_row_organization_differs_from_submission_owner"
    row = (record["source_file"], record["sheet"], record.get("source_row"))
    if row in blank_rows:
        return "other_confirmed_months_of_required_blank_row"
    return "requires_source_classification"


def reconcile_period(details: list[dict], errors: list[dict], excluded: list[dict], totals: list[dict],
                     *, year: int, month: int, scenario: str) -> dict:
    """한 기간 합계 차이를 포함 extra로 연결하고 제외 금액을 별도로 남긴다.

    Args:
        details: 저장 생성 상세 전체.
        errors: 저장 평가 오류 전체. 정답 마스터를 다시 읽지 않는다.
        excluded: 저장 생성 제외 상세 전체.
        totals: 저장 평가의 조직·전사 합계 대조.
        year: 대상 연도.
        month: 대상 월.
        scenario: 대상 실적·계획·추정 구분.
    Returns:
        전사 영역별 금액 연결, 실제 포함 extra, 제외 미확정, 원본 확인 대상.
        수치 잔차와 원인 미설명 extra 금액은 다른 필드다.
    Raises:
        ValueError: 출처 중복·합계 불일치·확정 금액 계약 위반.
        KeyError: 필수 저장 레코드 부재.
    Side Effects:
        없음. 파일·원본·채점 결과를 수정하지 않는다.
    """
    index, blank_rows = {}, set()
    included_totals = defaultdict(Decimal)
    for record in details:
        key = location(record)
        if key in index:
            raise ValueError("같은 출처의 생성 상세가 여러 개여서 임의 후보를 선택하지 않습니다")
        index[key] = record
        if record.get("assembly_status") == "unresolved" and record.get("raw_value") is None and not record.get("formula"):
            blank_rows.add((record["source_file"], record["sheet"], record.get("source_row")))
        if in_period(record, year, month, scenario) and record.get("included_in_totals"):
            included_totals[record["domain"]] += Decimal(record["normalized_value"])
    selected_extras, unresolved, inspect = [], [], []
    extra_totals, excluded_totals = defaultdict(Decimal), defaultdict(Decimal)
    categories = defaultdict(lambda: defaultdict(Decimal))
    category_counts = defaultdict(Counter)
    for error in errors:
        if error["status"] != "extra" or not in_period(error["record_key"], year, month, scenario):
            continue
        record = index[location(error)]
        if not record.get("included_in_totals"):
            continue
        code = extra_category(record, blank_rows)
        amount = Decimal(record["normalized_value"])
        extra_totals[record["domain"]] += amount
        categories[record["domain"]][code] += amount
        category_counts[record["domain"]][code] += 1
        selected_extras.append({"diagnostic_category": code, "record": record, "original_error": error})
        if code == "requires_source_classification":
            inspect.append(record)
    for record in excluded:
        if not in_period(record, year, month, scenario) or record.get("exclusion_reason") in {"superseded_detail_revision", "outside_monthly_detail_scope"}:
            continue
        unresolved.append(record)
        if record.get("normalized_value") is not None:
            excluded_totals[record["domain"]] += Decimal(record["normalized_value"])
    bridges = []
    for total in totals:
        if total.get("scope") != "company" or not in_period(total, year, month, scenario):
            continue
        domain = total["domain"]
        output_amount, truth_amount = Decimal(total["output_total"]), Decimal(total["truth_total"])
        if output_amount != included_totals[domain]:
            raise ValueError("저장된 전사 합계와 included_in_totals 상세 합계가 다릅니다")
        difference = output_amount - truth_amount
        bridges.append({"domain": domain, "year": year, "month": month, "scenario": scenario,
                        "truth_total_won": str(truth_amount), "output_total_won": str(output_amount),
                        "difference_won": str(difference), "included_extra_total_won": str(extra_totals[domain]),
                        "numeric_residual_won": str(difference - extra_totals[domain]),
                        "unexplained_extra_before_source_inspection_won": str(categories[domain].get("requires_source_classification", Decimal(0))),
                        "excluded_known_amount_separate_won": str(excluded_totals[domain]),
                        "excluded_count": sum(record["domain"] == domain for record in unresolved),
                        "excluded_amount_is_part_of_difference": False,
                        "extra_category_amounts_won": {key: str(value) for key, value in categories[domain].items()},
                        "extra_category_counts": dict(category_counts[domain]), "original_total_check": total})
    if {bridge["domain"] for bridge in bridges} != {"revenue", "expense"}:
        raise ValueError("요청 기간의 전사 매출·비용 대조가 모두 필요합니다")
    return {"bridges": bridges, "included_extras": selected_extras,
            "excluded_unresolved": unresolved, "source_inspection_targets": inspect}


def cell_evidence(cell: dict | None) -> dict | None:
    """원본 셀의 값·유형·수식·서식 출처를 최소 사전으로 남긴다.

    Args:
        cell: 기존 추출기의 원본 셀 또는 None.
    Returns:
        원본 근거 사전 또는 None. 빈 셀을 숫자 0으로 만들지 않는다.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    if cell is None:
        return None
    return {key: cell.get(key) for key in ("address", "row", "column_index", "value", "data_type", "formula", "style_id", "record_id")}


def inspect_source_contract(path: Path, requests: list[dict], expected_sha256: str) -> dict:
    """허용된 조직 원본의 요청 행과 실제 SUMIFS 관리분류 계약을 읽는다.

    Args:
        path: 호출자가 조직 입력 목록과 대조한 원본 파일.
        requests: saved source_file·sheet·cell·source_row로 지정한 확인 행.
        expected_sha256: v2 생성 당시 저장한 원본 SHA256.
    Returns:
        원본행·헤더·단위·관리분류 라벨·SUMIFS 근거와 원본 불변 확인.
        조직·계정은 ID나 정답 후보로 보완하지 않는다.
    Raises:
        ValueError: 마스터 경로·원본 변경·중복 좌표 또는 요청 시트 부재.
        OSError: 허용 조직 원본 읽기 오류.
    Side Effects:
        해당 조직 Excel만 기존 extract_workbook으로 읽는다. 저장·재계산 없음.
    """
    if "마스터" in path.name:
        raise ValueError("기준 마스터는 원본 계약 조사 입력이 아닙니다")
    before = file_fingerprint(path)
    if before != expected_sha256:
        raise ValueError("조직 원본이 v2 생성 시점과 달라졌습니다")
    workbook = extract_workbook(path)
    rows = []
    for request in requests:
        sheet = next((sheet for sheet in workbook["sheets"] if sheet["name"] == request["sheet"]), None)
        if sheet is None:
            raise ValueError("저장 출처 시트가 원본에 없습니다")
        cells = {cell["address"]: cell for cell in sheet["cells"]}
        row = request["source_row"]
        business = {letter: cell_evidence(cells.get(letter + str(row))) for letter in "ABCDE"}
        labels = {}
        for summary_sheet in workbook["sheets"]:
            summary_cells = {cell["address"]: cell for cell in summary_sheet["cells"]}
            for cell in summary_sheet["cells"]:
                formula = (cell.get("formula") or {}).get("text") or ""
                if "SUMIFS(" not in formula.upper() or sheet["name"] + "!$B:$B" not in formula:
                    continue
                criterion = re.search(r",\s*\$D(\d+)\s*,", formula)
                if not criterion:
                    continue
                address = "D" + criterion.group(1)
                label = summary_cells.get(address)
                if label and label.get("value") not in (None, ""):
                    key = (summary_sheet["name"], address)
                    labels.setdefault(key, {"sheet": summary_sheet["name"], "label": cell_evidence(label), "formula_cell": cell_evidence(cell)})
        values = {str(entry["label"]["value"]).strip() for entry in labels.values()}
        management = (business.get("B") or {}).get("value")
        rows.append({"source_file": str(path), "sheet": sheet["name"], "source_row": row,
                     "amount_cell": cell_evidence(cells.get(request["cell"])),
                     "unit_title": cell_evidence(cells.get("A1")),
                     "business_headers": {letter: cell_evidence(cells.get(letter + "3")) for letter in "ABCDE"},
                     "source_business_cells": business, "formula_bound_management_labels": list(labels.values()),
                     "management_class_value": management,
                     "management_class_in_formula_label_set": str(management).strip() in values if labels else None,
                     "source_contract_assessment": "management_class_not_in_source_formula_labels" if labels and str(management).strip() not in values else "source_contract_not_disproved",
                     "used_detail_id_for_organization_or_account": False})
    after = file_fingerprint(path)
    if after != before:
        raise ValueError("조사 중 조직 원본이 변경되었습니다")
    return {"source_file": str(path), "sha256_before": before, "sha256_after": after,
            "source_unchanged": True, "rows": rows}


def run_followup(baseline: Path, output: Path, *, type_code: str = "05", closing_month: str = "2026-01",
                 year: int = 2025, month: int = 1, scenario: str = "actual", write_output: bool = False) -> dict:
    """저장 v2의 한 기간 차이를 분석하고 새 실행에 원본 근거를 저장한다.

    Args:
        baseline: v2 final/generation.json과 evaluation이 있는 폴더.
        output: v3 아래 아직 없는 새 분석 폴더.
        type_code: 조사할 유형 코드.
        closing_month: 제출 기준월.
        year: 금액 대상 연도.
        month: 금액 대상 월.
        scenario: 금액 구분.
        write_output: 허용된 새 산출물을 저장할 때 True.
    Returns:
        전사 금액 연결과 직접 원본 확인 결과를 담은 요약.
    Raises:
        OSError, ValueError, FileExistsError: 입력·경계·해시·계약·저장 실패.
    Side Effects:
        v2 산출물과 허용 조직 원본만 읽고 선택 시 새 JSON/GZ/Markdown을 만든다.
        기준 마스터 Excel과 기존 생성·평가·원본은 변경하지 않는다.
    """
    repo = Path(__file__).resolve().parents[4]
    allowed = (repo / "parser/output/comparisons").resolve()
    baseline = baseline.resolve(strict=True)
    if baseline == allowed or not baseline.is_relative_to(allowed):
        raise ValueError("비교 산출물 안의 저장 실행만 입력으로 받습니다")
    output = validate_output_path(output, repo)
    manifest_path = baseline / "generation.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    matches = [batch for batch in manifest["batches"] if batch["type"].startswith(type_code + "_") and batch["closing_month"] == closing_month]
    if len(matches) != 1:
        raise ValueError("유형·기준월 배치가 유일하지 않습니다")
    batch = matches[0]
    directory = Path(batch["directory"]).resolve(strict=True)
    if not directory.is_relative_to(allowed):
        raise ValueError("생성 배치가 비교 산출물 밖입니다")
    evaluation = baseline / "evaluation" / batch["type"] / closing_month
    inputs = [manifest_path, directory / "details.jsonl.gz", directory / "excluded_records.jsonl.gz",
              directory / "master.json", evaluation / "errors.jsonl.gz", evaluation / "totals.json"]
    protected = {str(path): file_fingerprint(path) for path in inputs}
    if protected[str(directory / "details.jsonl.gz")] != batch["master_details_sha256"]:
        raise ValueError("저장 생성 상세 SHA가 다릅니다")
    result = reconcile_period(list(read_rows(directory / "details.jsonl.gz")), list(read_rows(evaluation / "errors.jsonl.gz")),
                              list(read_rows(directory / "excluded_records.jsonl.gz")),
                              json.loads((evaluation / "totals.json").read_text(encoding="utf-8")),
                              year=year, month=month, scenario=scenario)
    metadata = json.loads((directory / "master.json").read_text(encoding="utf-8"))
    source_hashes = {item["source_file"]: item["source_sha256"] for item in metadata["selected_submissions"]}
    allowed_sources = set(batch["organization_files"])
    requests = defaultdict(list)
    for record in result["source_inspection_targets"]:
        requests[record["source_file"]].append(record)
    source_evidence = []
    for path, records in sorted(requests.items()):
        if path not in allowed_sources or path == batch["master"] or path not in source_hashes:
            raise ValueError("고정된 조직 입력 목록에 없는 원본은 조사하지 않습니다")
        source_evidence.append(inspect_source_contract(Path(path), records, source_hashes[path]))
    if {str(path): file_fingerprint(path) for path in inputs} != protected:
        raise ValueError("분석 중 v2 생성 또는 평가 산출물이 변경되었습니다")
    source_assessments = {(row["source_file"], row["sheet"], row["source_row"]): row for item in source_evidence for row in item["rows"]}
    for bridge in result["bridges"]:
        explained = sum((Decimal(record["normalized_value"]) for record in result["source_inspection_targets"]
                         if record["domain"] == bridge["domain"]
                         and source_assessments[(record["source_file"], record["sheet"], record["source_row"])]["management_class_in_formula_label_set"] is False), Decimal(0))
        bridge["source_contract_explained_extra_won"] = str(explained)
        bridge["unexplained_extra_after_source_inspection_won"] = str(Decimal(bridge["unexplained_extra_before_source_inspection_won"]) - explained)
    summary = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "command": sys.argv,
               "baseline": str(baseline), "selected_generation_directory": str(directory),
               "type": batch["type"], "closing_month": closing_month, "year": year, "month": month,
               "scenario": scenario, "bridges": result["bridges"], "reference_master_excel_reads": 0,
               "organization_excel_reads": len(source_evidence), "source_and_v2_preserved": True,
               "used_detail_id_for_organization_or_account": False, "protected_v2_sha256": protected,
               "source_inspection_row_count": sum(len(item["rows"]) for item in source_evidence),
               "source_unregistered_management_count": sum(row["management_class_in_formula_label_set"] is False for item in source_evidence for row in item["rows"]),
               "source_evidence": source_evidence}
    if write_output:
        output.mkdir(parents=True, exist_ok=False)
        with (output / "reconciliation.json").open("x", encoding="utf-8") as stream:
            json.dump(summary, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        for name in ("included_extras", "excluded_unresolved"):
            with gzip.open(output / (name + ".jsonl.gz"), "xt", encoding="utf-8") as stream:
                for record in result[name]:
                    stream.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
        lines = ["# v2 전사 합계 차이의 원본 추적", "", f"유형 {batch['type']}, 제출 {closing_month}, 대상 {year}-{month:02d} {scenario}. 금액 단위는 원.", "",
                 "| 영역 | 저장 정답 | 생성 합계 | 차이 | 포함 extra 합 | 수치 잔차 | 제외된 알려진 금액(별도) |", "|---|---:|---:|---:|---:|---:|---:|"]
        for bridge in summary["bridges"]:
            lines.append("| " + " | ".join(str(bridge[field]) for field in ("domain", "truth_total_won", "output_total_won", "difference_won", "included_extra_total_won", "numeric_residual_won", "excluded_known_amount_separate_won")) + " |")
        lines += ["", "제외된 알려진 금액은 생성 합계에 들어 있지 않으므로 위 차액에 다시 더하지 않는다. 수치 잔차 0과 개별 extra의 업무상 유효성은 다른 판단이다.", "",
                  f"저장 결과만으로 원인이 설명되지 않았던 {summary['source_inspection_row_count']}개 행을 조직 원본에서 확인했다. 그중 {summary['source_unregistered_management_count']}개는 실제 SUMIFS 관리분류 라벨 집합에 원본 B열 값이 없었다. 조직·계정을 ID 후보로 보완하지 않았다.", "",
                  "reconciliation.json은 원본 A~E 셀, 헤더·단위·금액 셀과 관리분류를 참조하는 실제 SUMIFS·라벨 출처를 보존한다. included_extras.jsonl.gz와 excluded_unresolved.jsonl.gz는 차액 포함 여부를 분리한 건별 상세다. 기준 마스터를 직접 읽거나 v2 점수를 변경하지 않았다.", ""]
        (output / "report.md").write_text("\n".join(lines), encoding="utf-8")
    return summary


def main(argv: list[str] | None = None) -> int:
    """원본 행 대조 분석을 독립 CLI로 실행한다.

    Args:
        argv: 명령 인자. None이면 실제 명령행 사용.
    Returns:
        정상 완료 시 0.
    Raises:
        OSError, ValueError, FileExistsError: 분석·읽기·저장 실패.
    Side Effects:
        허용된 산출물·조직 원본을 읽고 --write-output일 때 새 분석 결과를 쓴다.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--type", default="05")
    parser.add_argument("--closing-month", default="2026-01")
    parser.add_argument("--year", type=int, default=2025)
    parser.add_argument("--month", type=int, default=1)
    parser.add_argument("--scenario", choices=("actual", "plan", "estimate"), default="actual")
    parser.add_argument("--write-output", action="store_true")
    options = parser.parse_args(argv)
    result = run_followup(options.baseline, options.output, type_code=options.type,
                          closing_month=options.closing_month, year=options.year, month=options.month,
                          scenario=options.scenario, write_output=options.write_output)
    print(json.dumps({"bridges": result["bridges"], "source_inspection_row_count": result["source_inspection_row_count"],
                      "source_unregistered_management_count": result["source_unregistered_management_count"],
                      "reference_master_excel_reads": result["reference_master_excel_reads"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
