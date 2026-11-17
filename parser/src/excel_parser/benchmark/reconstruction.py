"""조직 원본만으로 월별 마스터를 생성한 뒤 독립 평가기를 실행한다."""

from __future__ import annotations

import csv
import gzip
import json
import re
import sys
import time
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

from ..extraction import file_fingerprint
from .adapters import run_rule_adapter
from .cli import json_value, validate_output_path
from .monthly import write_json
from .runner import implementation_fingerprint


def _group(period: dict, closing_month: str) -> str:
    """파서가 읽은 대상 연도·시나리오를 제출 기준연도와 구분하여 표시한다.

    Args:
        period: 파서의 대상 기간 사전.
        closing_month: 검증 목록의 제출 기준월.
    Returns:
        prior, plan, outlook, next_plan, next_projection 또는 unknown.
    Raises:
        ValueError: 잘못된 기준연도 형식.
    Side Effects:
        없음. 기간이나 시나리오를 보충하지 않는다.
    """
    year = period.get("year")
    scenario = period.get("scenario")
    closing_year = int(closing_month[:4])
    if year is None:
        return "unknown"
    if year < closing_year:
        return "prior"
    if year == closing_year:
        return "plan" if scenario == "plan" else "outlook"
    return "next_plan" if scenario == "plan" else "next_projection"


def _flat(record: dict, type_name: str, closing_month: str) -> dict:
    """파서가 확정한 의미와 출처를 월별 상세 계약으로 옮긴다.

    Args:
        record: 원본에서 생성한 표준 레코드.
        type_name: 파일 목록의 유형 식별자.
        closing_month: 대상 연월과 구분하는 제출 기준월.
    Returns:
        평가기와 조립기가 공유할 평탄한 상세 사전.
    Raises:
        KeyError: 파서 표준 계약 위반.
    Side Effects:
        없음. 정답이나 파일명을 이용해 의미를 보완하지 않는다.
    """
    source, item, period, value = (record[k] for k in ("source", "item", "period", "value"))
    domain = "revenue" if "MAP_REVENUE_DETAIL" in record["mapping"]["rule_ids"] else "expense"
    normalized = value.get("normalized_value")
    code = item.get("code")
    result = {
        "type": type_name, "closing_month": closing_month, "domain": domain,
        "organization_code": record["organization"].get("code"),
        "organization_name": record["organization"].get("name"),
        "organization_status": record["organization"].get("status"),
        "account_code": str(code) if code is not None else None,
        "account_name": item.get("name"), "year": period.get("year"),
        "month": period.get("month"), "scenario": period.get("scenario"),
        "aggregation": period.get("aggregation"), "period_group": _group(period, closing_month),
        "detail_id": item.get("detail_id"), "revision": item.get("detail_revision"),
        "role_kind": record["role"]["kind"], "source_file": source["path"],
        "sheet": source["sheet"], "cell": source["cell"],
        "source_row": int(re.search(r"\d+$", source["cell"]).group()),
        "raw_record_id": source.get("raw_record_id"),
        "normalized_value": str(normalized) if normalized is not None else None,
        "raw_value": record.get("raw_value"), "source_unit": value.get("source_unit"),
        "value_kind": value.get("value_kind"), "value_status": value.get("status"),
        "mapping_status": record["mapping"]["status"],
        "mapping_reasons": record["mapping"]["reasons"],
        "formula": record.get("formula"),
        "organization_provenance": record["organization"].get("provenance", []),
        "item_provenance": item.get("provenance", []),
        "period_provenance": period.get("provenance", []),
        "unit_provenance": record.get("unit_provenance", []),
        "style_reference": record.get("style_reference", {}),
        "business_validation": record.get("business_validation", {"status": "not_applicable", "issues": []}),
    }
    result["confirmed"] = value.get("status") == "confirmed" and value.get("value_kind") == "amount" and normalized is not None
    result["semantic_complete"] = all(result.get(k) not in (None, "", "unknown") for k in (
        "organization_code", "account_code", "year", "month", "scenario", "detail_id")) and result["scenario"] in {"actual", "plan", "estimate"} and result["organization_status"] == "resolved" and result["business_validation"].get("status") not in {"invalid", "unresolved"}
    return result


def _has_source_detail_identity(row: int, index: dict, layout: dict) -> bool:
    """오류 자리표시를 제외한 실제 항목·코드·거래 설명이 있는지 확인한다.

    Args:
        row: 빈 월별 금액을 보완하려는 원본 행 번호.
        index: 같은 시트의 원본 셀 색인.
        layout: 실제 헤더에서 확인한 열 대응.
    Returns:
        유효한 항목·계정·거래 설명 원본이 하나라도 있으면 True.
        오류 타입, 수식 오류·누락 캐시와 Excel 오류 문자열만 있으면 False.
    Raises:
        KeyError: 내부 색인·레이아웃 계약 위반.
    Side Effects:
        없음. 원본 셀이나 기존 생산 레코드를 변경하지 않는다.
    """
    error_labels = {"#N/A", "#REF!", "#VALUE!", "#DIV/0!", "#NAME?", "#NUM!", "#NULL!"}
    cells = index["rows"].get(row, {})
    for name in ("중분류", "소분류", "예산Code", "예산명칭", "거래내역 및 산출근거"):
        cell = cells.get(layout["columns"].get(name))
        if not cell or cell.get("data_type") not in {"string", "number"}:
            continue
        if (cell.get("formula") or {}).get("cached_status") in {"missing", "error"}:
            continue
        value = cell.get("value")
        text = str(value).strip() if value is not None else ""
        if text and text.upper() not in error_labels:
            return True
    return False


def project_source_details(mapped: dict, workbook: dict, type_name: str, closing_month: str) -> list[dict]:
    """표준 월별 상세와 같은 원본 표의 빈 금액 슬롯을 구분하여 보존한다.

    Args:
        mapped: 기존 룰 파서의 출력.
        workbook: 같은 조직 원본의 추출 객체.
        type_name: 목록의 유형.
        closing_month: 목록의 제출 기준월.
    Returns:
        월별 상세 목록. 빈 셀은 금액을 만들지 않고 missing으로 보존한다.
    Raises:
        KeyError: 원본 또는 파서 계약 위반.
    Side Effects:
        원본 메모리만 조회한다. 마스터·정답·파일을 읽지 않는다.
    """
    from ..business_validation import build_business_contracts, validate_business_row
    from ..mapping import (
        _index,
        _layout,
        _organization_registry,
        _periods_with_cross_evidence,
        _row_context,
        _unit,
        _workbook_organization,
    )
    from ..normalization import normalize_number

    records = [r for r in mapped["records"] if r["role"]["kind"] == "detail" and r["period"]["aggregation"] == "month"]
    result = [_flat(r, type_name, closing_month) for r in records]
    existing = {(r["source"]["sheet"], r["source"]["cell"]) for r in records}
    templates = {}
    for record in records:
        row = int(re.search(r"\d+$", record["source"]["cell"]).group())
        templates.setdefault((record["source"]["sheet"], row), record)
    indices = [_index(s) for s in workbook["sheets"]]
    layouts = [_layout(s, i) for s, i in zip(workbook["sheets"], indices)]
    _periods_with_cross_evidence(layouts)
    registry = _organization_registry(indices)
    common_org = _workbook_organization(layouts, indices, registry)
    business_contracts = build_business_contracts(workbook)
    for sheet, index, layout in zip(workbook["sheets"], indices, layouts):
        if layout["kind"] not in {"revenue_detail", "expense_detail"}:
            continue
        monthly_columns = [column for column, period in layout["periods"].items() if period["aggregation"] == "month"]
        if not monthly_columns:
            continue
        source_unit, unit_sources = _unit(index)
        for row in index["rows"]:
            if row <= layout["header_row"] or (sheet["name"], row) in templates:
                continue
            context = _row_context({"row": row, "column_index": monthly_columns[0]}, index, layout, common_org, registry, {})
            if context["role"] != "detail" or not _has_source_detail_identity(row, index, layout):
                continue
            business_validation = validate_business_row(index["rows"][row], layout, business_contracts)
            templates[(sheet["name"], row)] = {
                "source": {"path": workbook["file"]["path"], "sheet": sheet["name"], "cell": "A" + str(row)},
                "organization": context["organization"], "item": context["item"],
                "role": {"kind": "detail"}, "period": {}, "value": {"source_unit": source_unit},
                "unit_provenance": unit_sources,
                "business_validation": business_validation,
                "mapping": {"status": "partial", "reasons": ["원본 월별 금액이 비어 있음"],
                            "rule_ids": ["MAP_" + layout["kind"].upper()]},
            }
            templates[(sheet["name"], row)]["mapping"]["reasons"].extend(issue["message"] for issue in business_validation.get("issues", []))
        for (sheet_name, row), template in templates.items():
            if sheet_name != sheet["name"]:
                continue
            for column, period in layout["periods"].items():
                if period["aggregation"] != "month":
                    continue
                number, label = column, ""
                while number:
                    number, remainder = divmod(number - 1, 26)
                    label = chr(65 + remainder) + label
                address = label + str(row)
                cell = index["rows"].get(row, {}).get(column)
                if (sheet_name, address) in existing:
                    continue
                source_value = (cell or {}).get("value")
                synthetic = {**template, "source": {**template["source"], "cell": address,
                              "raw_record_id": (cell or {}).get("record_id")}, "period": dict(period),
                             "raw_value": source_value, "formula": (cell or {}).get("formula")}
                synthetic["value"] = normalize_number(source_value, data_type=(cell or {}).get("data_type", "blank"),
                                                       value_kind="amount", unit=template["value"].get("source_unit"))
                if source_value not in (None, "") or synthetic["formula"]:
                    synthetic["value"].update(status="unresolved", normalized_value=None)
                    synthetic["mapping"] = {**template["mapping"], "status": "partial", "reasons": ["원본 월별 상세 셀이 생산 파서 출력에서 누락됨"]}
                flat = _flat(synthetic, type_name, closing_month)
                flat["input_presence"] = "absent_xml_cell" if cell is None else "stored_nonblank" if source_value not in (None, "") or synthetic["formula"] else "stored_blank"
                result.append(flat)
    return result


def _source_declared_owner(workbook: dict) -> dict:
    """재무표 상단의 일치하는 조직 선언과 원본 코드표로 제출 소유자를 확인한다.

    Args:
        workbook: 같은 조직 원본의 추출 객체.
    Returns:
        status, code, name, provenance, reason을 담은 근거 사전.
        확인된 재무표의 첫 값 열 왼쪽·데이터 시작 전 영역에서
        '▣ 조직명' 제목과 별도 동일 조직명 셀이 모두 존재하고,
        같은 원본 코드표에 이름과 코드가 유일하게 대응할 때만 resolved이다.
    Raises:
        KeyError: 추출·레이아웃 계약 위반.
    Side Effects:
        없음. 파일명·다수결·다른 파일을 사용하지 않고 행 조직도 변경하지 않는다.
    """
    from ..mapping import _index, _layout, _organization_registry, _ref

    indices = [_index(sheet) for sheet in workbook["sheets"]]
    registry = _organization_registry(indices)
    declarations, provenance, problems = [], [], []
    for sheet, index in zip(workbook["sheets"], indices):
        layout = _layout(sheet, index)
        if layout["kind"] != "financial_statement" or not layout["periods"]:
            continue
        first_value_column = min(layout["periods"])
        labels = []
        for cell in sheet["cells"]:
            if cell["row"] > layout["header_row"] or cell["column_index"] >= first_value_column:
                continue
            if cell.get("data_type") != "string" or (cell.get("formula") or {}).get("cached_status") in {"missing", "error"}:
                continue
            value = str(cell.get("value") or "").strip()
            if value:
                labels.append((cell, value))
        for title_cell, title in labels:
            match = re.fullmatch(r"▣\s*(\S(?:.*\S)?)", title)
            if not match:
                continue
            name = match.group(1)
            independent = [cell for cell, value in labels if value == name and cell["address"] != title_cell["address"]]
            entries = [(code, entry) for code, entry in registry.items() if name in entry["names"]]
            if not independent and not entries:
                continue
            provenance.append(_ref(title_cell))
            provenance.extend(_ref(cell) for cell in independent)
            provenance.extend(ref for _, entry in entries for ref in entry["provenance"])
            if not independent:
                problems.append("별도 동일 조직명 셀이 없는 상단 제목")
            elif len(entries) != 1 or entries[0][1]["name"] != name:
                problems.append("조직명과 원본 코드표의 대응이 없거나 유일하지 않음")
            else:
                declarations.append((entries[0][0], name))
    unique = set(declarations)
    refs = {}
    for ref in provenance:
        if ref:
            refs[(ref.get("sheet"), ref.get("cell"), ref.get("raw_record_id"))] = ref
    if len(unique) == 1 and not problems:
        code, name = next(iter(unique))
        return {"status": "resolved", "code": code, "name": name,
                "provenance": list(refs.values()),
                "reason": "재무표 상단 제목·독립 조직명과 동일 원본의 유일한 조직 코드표 대응이 일치함"}
    return {"status": "conflict" if len(unique) > 1 else "unresolved", "code": None,
            "name": None, "provenance": list(refs.values()),
            "reason": "; ".join(problems) if problems else "상단 조직 선언이 없거나 서로 충돌함"}


def parse_submission(job: tuple[str, str, str]) -> dict:
    """조직 원본 하나를 기존 추출·매핑·검증 경로로 읽는다.

    Args:
        job: 조직 파일 경로, 유형, 제출 기준월.
    Returns:
        조립기용 제출본과 원본 검증 감사 내역. 복수 행 조직일 때만
        별도 상단 선언으로 제출 소유자를 보완하며 행 조직은 유지한다.
        제출 경로는 추출 레코드와 같은 절대 경로로 정규화한다.
    Raises:
        KeyError: 파서 출력 계약 위반.
    Side Effects:
        지정 조직 파일만 읽는다. 쓰기·API·마스터 읽기는 없다.
    """
    path, type_name, closing_month = job
    path = str(Path(path).resolve())
    result = run_rule_adapter(Path(path), metadata={"submission_month": closing_month})
    records = project_source_details(result["mapped"], result["workbook"], type_name, closing_month) if result.get("mapped") else []
    organizations = {r["organization_code"] for r in records if r["organization_code"]}
    owner = next(iter(organizations)) if len(organizations) == 1 else None
    owner_source = "unique_row_organization" if owner is not None else "unresolved"
    declared_owner = {"status": "not_attempted", "code": None, "name": None,
                      "provenance": [], "reason": "복수 행 조직인 경우에만 상단 조직 선언을 확인함"}
    if len(organizations) > 1:
        declared_owner = _source_declared_owner(result["workbook"])
        if declared_owner["status"] == "resolved":
            owner, owner_source = declared_owner["code"], "source_declared_owner"
    trial = {k: v for k, v in result.items() if k not in {"workbook", "mapped"}}
    trial["submission_owner"] = {"organization_code": owner, "identity_source": owner_source,
                                 "row_organization_codes": sorted(organizations),
                                 "source_declared_owner": declared_owner}
    return {"submission": {"organization_code": owner, "source_file": path,
            "organization_identity_source": owner_source, "source_declared_owner": declared_owner,
            "source_sha256": result["source_integrity"]["sha256_before"],
            "submission_version": None, "submission_revision": None,
            "approval_status": None, "records": records}, "trial": trial}


def write_jsonl(path: Path, rows: list[dict]) -> None:
    """현재 새 실행 폴더에 압축 상세 목록을 배타적으로 저장한다.

    Args:
        path: 아직 없는 gzip JSONL 경로.
        rows: 상세 레코드 또는 오류 목록.
    Returns:
        없음.
    Raises:
        FileExistsError, OSError: 기존 경로 또는 저장 실패.
    Side Effects:
        결과 파일 하나를 생성한다.
    """
    with gzip.open(path, "xt", encoding="utf-8", compresslevel=5) as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, default=json_value, separators=(",", ":")) + "\n")


def read_jsonl(path: Path) -> list[dict]:
    """생성 완료된 상세 산출물을 메모리로 읽는다.

    Args:
        path: 기존 gzip JSONL.
    Returns:
        저장 순서의 사전 목록.
    Raises:
        OSError, ValueError: 읽기 또는 JSON 오류.
    Side Effects:
        지정 산출물만 읽는다.
    """
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        return [json.loads(line) for line in stream]


def generate(inventory_path: Path, output: Path, *, types: list[str], months: list[str], workers: int,
             publication_policy: str | None = None) -> dict:
    """최초 목록의 조직 원본만 읽어 새 유형·기준월 마스터들을 만든다.

    Args:
        inventory_path: 최초 실행의 입력 목록 JSON.
        output: 새 생성 실행 폴더.
        types: 선택 유형 번호, 빈 목록은 전체.
        months: 선택 YYYY-MM, 빈 목록은 전체.
        workers: 독립 원본 읽기 프로세스 수.
        publication_policy: 명시한 원본 생성 계약의 발행 정책. None이면 기존 v3 동작.
    Returns:
        생성 목록과 원본·구현 해시 및 실행 조건.
    Raises:
        ValueError, OSError: 목록·경로 오류 또는 원본/결과 접근 실패.
    Side Effects:
        조직 원본만 읽고 상세·합계·선택 이력·검증 파일을 새로 저장한다.
        기존 마스터 파일은 이름을 보관할 뿐 열지 않는다.
    """
    from ..master import assemble_master, replace_organization

    if publication_policy not in (None, "source-contract-v4"):
        raise ValueError("지원하지 않는 발행 정책입니다")
    repo = Path(__file__).resolve().parents[4]
    output = validate_output_path(output, repo)
    inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
    batches = [b for b in inventory["batches"] if (not types or b["type"][:2] in types) and (not months or b["closing_month"] in months)]
    if not batches:
        raise ValueError("선택한 유형·기준월이 목록에 없습니다")
    masters = {str(Path(b["master"]).resolve()) for b in inventory["batches"]}
    for batch in batches:
        sources = batch["organization_files"]
        if len(set(sources)) != len(sources) or any(str(Path(p).resolve()) in masters or "마스터" in Path(p).name for p in sources):
            raise ValueError("생성 입력은 중복 없는 조직 원본 목록이어야 하며 마스터를 포함할 수 없습니다")
    output.mkdir(parents=True, exist_ok=False)
    started = time.perf_counter()
    manifest = {"created_at_utc": datetime.now(timezone.utc).isoformat(), "command": sys.argv,
                "inventory": str(inventory_path.resolve()), "inventory_sha256": file_fingerprint(inventory_path),
                "implementation_sha256": implementation_fingerprint(), "reference_masters_read": 0,
                "records_source": "organization_inputs_only", "batches": []}
    if publication_policy is not None:
        manifest["publication_policy"] = publication_policy
    with ProcessPoolExecutor(max_workers=workers) as pool:
        for index, batch in enumerate(batches, 1):
            jobs = [(p, batch["type"], batch["closing_month"]) for p in batch["organization_files"]]
            parsed = list(pool.map(parse_submission, jobs))
            submissions = [r["submission"] for r in parsed]
            expected = [re.match(r"\d+_(F\d+)_", Path(p).name).group(1) for p in batch["organization_files"]]
            master = assemble_master(submissions, expected_organizations=expected,
                                     closing_month=batch["closing_month"], type_name=batch["type"],
                                     publication_policy=publication_policy)
            replay = list(submissions)
            replacement_checked = None
            for submission in submissions:
                if submission["organization_code"]:
                    replay = replace_organization(replay, submission)
                    replacement_checked = submission["source_file"]
                    break
            repeated = assemble_master(replay, expected_organizations=expected,
                                       closing_month=batch["closing_month"], type_name=batch["type"],
                                       publication_policy=publication_policy)
            idempotent = master["content_sha256"] == repeated["content_sha256"]
            directory = output / batch["type"] / batch["closing_month"]
            directory.mkdir(parents=True)
            write_jsonl(directory / "details.jsonl.gz", master["records"])
            write_jsonl(directory / "excluded_records.jsonl.gz", master["excluded_records"])
            write_jsonl(directory / "source_records.jsonl.gz", [r for s in submissions for r in s["records"]])
            summary = {k: v for k, v in master.items() if k not in {"records", "details", "excluded_records"}}
            summary.update(idempotent_rerun=idempotent, generated_before_reference_read=True,
                           idempotency_scope="full_reassembly_after_one_representative_organization_replacement",
                           replacement_checked_source=replacement_checked,
                           source_integrity=all(r["trial"]["source_integrity"]["unchanged"] for r in parsed),
                           generated_at_utc=datetime.now(timezone.utc).isoformat())
            selected_files = {s["source_file"] for s in summary["selected_submissions"]}
            summary["unselected_input_files"] = sorted(set(batch["organization_files"]) - selected_files)
            if summary["unselected_input_files"] or not idempotent or not summary["source_integrity"]:
                summary["status"] = "partial"
            write_json(directory / "master.json", summary)
            write_json(directory / "execution.json", [r["trial"] for r in parsed])
            manifest["batches"].append({**batch, "directory": str(directory), "status": summary["status"],
                                        "idempotent_rerun": idempotent, "master_details_sha256": file_fingerprint(directory / "details.jsonl.gz")})
            print(f"generated {index}/{len(batches)} {batch['type']}/{batch['closing_month']} {summary['status']}", flush=True)
    manifest["generation_seconds"] = time.perf_counter() - started
    write_json(output / "generation.json", manifest)
    return {"generated_masters": len(batches), "output": str(output), "reference_masters_read": 0}


def evaluate(output: Path) -> dict:
    """이미 저장된 생성 상세를 확인한 뒤 기존 마스터를 평가기에서만 읽는다.

    Args:
        output: generation.json을 가진 생성 완료 실행 폴더.
    Returns:
        유형·기준월별 상세 및 합계 검증 요약.
    Raises:
        FileExistsError: 이미 채점한 실행인 경우.
        OSError, ValueError: 저장 자료·독립 마스터 접근 또는 해시 오류.
    Side Effects:
        생성 결과와 기존 마스터를 읽고 새 evaluation 폴더에 채점을 저장한다.
        생성 마스터와 최초 실행 산출물을 변경하지 않는다.
    """
    from ..master import _totals
    from .monthly_evaluation import _aggregate_checks, score_monthly_records
    from .monthly_truth import build_master_truth

    output = output.resolve()
    allowed = Path(__file__).resolve().parents[4] / "parser/output/comparisons"
    if output == allowed or not output.is_relative_to(allowed.resolve()):
        raise ValueError("평가 대상은 승인된 comparisons 하위 실행 폴더여야 합니다")
    manifest = json.loads((output / "generation.json").read_text(encoding="utf-8"))
    evaluation = output / "evaluation"
    evaluation.mkdir(exist_ok=False)
    rows = []
    for index, batch in enumerate(manifest["batches"], 1):
        directory = Path(batch["directory"])
        if file_fingerprint(directory / "details.jsonl.gz") != batch["master_details_sha256"]:
            raise ValueError("생성 뒤 상세 파일 해시가 변경되었습니다")
        details = read_jsonl(directory / "details.jsonl.gz")
        master = json.loads((directory / "master.json").read_text(encoding="utf-8"))
        regenerated_totals = [{"scope": "organization", **g} for g in _totals(details, by_organization=True)]
        regenerated_totals += [{"scope": "company", "organization_code": None, **g} for g in _totals(details, by_organization=False)]
        if regenerated_totals != master["totals"]:
            raise ValueError("저장한 마스터 합계와 포함 상세의 합계가 다릅니다")
        total_details = [{**r, "confirmed": bool(r.get("included_in_totals"))} for r in details]
        reference = Path(batch["master"])
        before = file_fingerprint(reference)
        truth = build_master_truth(reference, type_name=batch["type"], closing_month=batch["closing_month"])
        score = score_monthly_records(details, truth["records"])
        extras_zero = sum(d["status"] == "extra" and d.get("output_value") is not None and Decimal(d["output_value"]) == 0 for d in score["details"])
        sums = [{"scope": "company", "organization_code": None, **g} for g in _aggregate_checks(total_details, truth["records"])]
        organizations = {r.get("organization_code") for r in details + truth["records"]}
        for organization in sorted(organizations, key=str):
            scoped_output = [r for r in total_details if r.get("organization_code") == organization]
            scoped_truth = [r for r in truth["records"] if r.get("organization_code") == organization]
            sums.extend({"scope": "organization", "organization_code": organization, **g}
                        for g in _aggregate_checks(scoped_output, scoped_truth))
        for group in sums:
            group["difference_won"] = str(Decimal(group["output_total"]) - Decimal(group["truth_total"]))
        totals_match = all(g["matching"] for g in sums)
        unresolved_required = sum(r.get("assembly_status") == "unresolved" for r in details)
        row = {"type": batch["type"], "closing_month": batch["closing_month"], "generation_status": master["status"],
               "expected_organizations": master["expected_organization_count"], "included_organizations": master["selected_organization_count"],
               **score["summary"], "source_zero_absent_from_reference": extras_zero,
               "unresolved_required": unresolved_required, "optional_blank_count": master["optional_blank_count"],
               "stored_totals_verified": True,
               "total_groups": len(sums), "total_matching_groups": sum(g["matching"] for g in sums),
               "all_totals_match": totals_match, "detailed_and_total_compared": True,
               "strict_detail_and_total_match": score["summary"]["false_positive"] == 0 and score["summary"]["false_negative"] == 0 and totals_match,
               "reconciled_detail_and_total_match": score["summary"]["false_positive"] == extras_zero and score["summary"]["false_negative"] == 0 and totals_match and unresolved_required == 0 and master["status"] == "complete",
               "reference_unchanged": before == file_fingerprint(reference), "idempotent_rerun": master["idempotent_rerun"]}
        target = evaluation / batch["type"] / batch["closing_month"]
        target.mkdir(parents=True)
        write_jsonl(target / "errors.jsonl.gz", score["details"])
        write_json(target / "totals.json", sums)
        write_json(target / "truth_metadata.json", {k: v for k, v in truth.items() if k != "records"})
        write_json(target / "summary.json", row)
        write_json(target / "by_domain.json", score["by_domain"])
        rows.append(row)
        print(f"evaluated {index}/{len(manifest['batches'])} {batch['type']}/{batch['closing_month']} match={row['match_count']}/{row['truth_count']} totals={row['total_matching_groups']}/{row['total_groups']}", flush=True)
    report = {"command": sys.argv, "rows": rows, "generated_count": len(rows),
              "detailed_and_total_compared_count": len(rows),
              "strict_detail_and_total_match_count": sum(r["strict_detail_and_total_match"] for r in rows),
              "reconciled_detail_and_total_match_count": sum(r["reconciled_detail_and_total_match"] for r in rows),
              "all_totals_match_count": sum(r["all_totals_match"] for r in rows)}
    write_json(evaluation / "validation.json", report)
    with (evaluation / "validation.csv").open("x", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=[k for k in rows[0] if k != "error_counts"])
        writer.writeheader()
        writer.writerows({k: v for k, v in r.items() if k != "error_counts"} for r in rows)
    return {k: v for k, v in report.items() if k != "rows"}
