"""제출 어댑터의 보고 수식·월 빈칸·근거 보존을 메모리 합성 입력으로 검증한다."""

import unittest
from copy import deepcopy
from decimal import Decimal
from unittest.mock import patch

from excel_parser.mapping import map_workbook
from excel_parser.service_bridge import parse_bytes
from excel_parser.submission_adapter import build_submission_data, _sumifs_contract
from test_mapping import memory_workbook
from test_mapping_monthly import append_sheet, inactive_expense_workbook
from test_service_bridge import _package


def _column(number: int) -> str:
    """합성 원본의 열 번호를 독립적으로 주소 문자로 만든다.

    Args:
        number: 양수 열 번호.
    Returns:
        Excel 열 문자.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    text = ""
    while number:
        number, digit = divmod(number - 1, 26)
        text = chr(65 + digit) + text
    return text


def _cell(workbook: dict, sheet: str, address: str) -> dict:
    """합성 추출 계약에서 검사할 원본 셀을 찾는다.

    Args:
        workbook: 메모리 워크북.
        sheet: 합성 시트명.
        address: 합성 주소.
    Returns:
        수정 또는 검사할 메모리 셀 참조.
    Raises:
        StopIteration: fixture 주소가 존재하지 않을 때.
    Side Effects:
        없음. 파일에 접근하지 않는다.
    """
    return next(cell for value in workbook["sheets"] if value["name"] == sheet for cell in value["cells"] if cell["address"] == address)


def submission_workbook() -> dict:
    """동일 계정의 두 매출 분류와 비용 E:E 보고 기준을 합성한다.

    Args:
        없음.
    Returns:
        12개월 PLAN, 원본 상세·보고·조직·계정표가 있는 추출 계약.
    Raises:
        ValueError: 합성 주소가 잘못된 경우.
    Side Effects:
        메모리만 사용하며 샘플·캐시·임시 파일을 만들지 않는다.
    """
    rows = [
        ("A1", "단위: 천원", "string"), ("A3", "조직명", "string"),
        ("B3", "관리분류", "string"), ("C3", "중분류", "string"),
        ("D3", "소분류", "string"), ("E3", "거래내역 및 산출근거", "string"),
        ("F3", "2031년 계획", "string"), ("F4", "연간", "string"),
    ]
    for row, category, amount in ((5, "분류가", "1.25"), (6, "분류나", "2.50")):
        rows += [(f"A{row}", "합성조직", "string"), (f"B{row}", category, "string"),
                 (f"C{row}", "2200 공통계정", "string"), (f"D{row}", category, "string"),
                 (f"E{row}", f"계약 [ID:revenue-{row}; 수정:1]", "string")]
        rows += [(f"{_column(month + 6)}{row}", amount, "number") for month in range(1, 13)]
    rows += [(f"{_column(month + 6)}4", f"{month}월", "string") for month in range(1, 13)]
    workbook = memory_workbook(rows)
    expense = [("A1", "단위: 천원", "string"), ("Q4", "2031년 계획", "string")]
    headers = ["대분류", "중분류", "소분류", "조직명", "본부명", "팀Code", "팀명", "실행과제Code", "실행과제명", "예산Code", "예산명칭", "실적집계기준", "비용구분", "담당자", "거래내역 및 산출근거"]
    expense += [(f"{_column(index)}5", label, "string") for index, label in enumerate(headers, 1)]
    expense += [("D6", "합성조직", "string"), ("F6", "TEAM-1", "string"),
                ("H6", "TASK-1", "string"), ("J6", "62020", "number"),
                ("K6", "합성비용", "string"), ("L6", "합성비용", "string"),
                ("M6", "고정", "string"), ("N6", "담당", "string"),
                ("O6", "근거 [ID:expense-6; 수정:2]", "string"), ("Q5", "연간", "string")]
    for month in range(1, 13):
        expense += [(f"{_column(month + 17)}5", f"{month}월", "string"),
                    (f"{_column(month + 17)}6", "0" if month == 1 else "0.5", "number")]
    append_sheet(workbook, "비용", expense)
    append_sheet(workbook, "조직", [("A1", "사업부코드", "string"), ("B1", "사업부명", "string"), ("A2", "ORG-1", "string"), ("B2", "합성조직", "string")])
    append_sheet(workbook, "계정", [("A1", "예산코드", "string"), ("B1", "예산명칭", "string"), ("A2", "62020", "number"), ("B2", "합성비용", "string")])
    report = [("A5", "합성조직", "string"), ("C9", "매출", "string"),
              ("D10", "분류가", "string"), ("D11", "분류나", "string"),
              ("C12", "영업비용", "string"), ("E13", "합성비용", "string"),
              ("C14", "영업이익", "string")]
    for month in range(1, 13):
        letter = _column(month + 9)
        report += [(f"{letter}5", "2031년", "string"), (f"{letter}6", "계획", "string"), (f"{letter}8", f"{month}월", "string")]
        expense_amount = Decimal("0") if month == 1 else Decimal("0.5")
        for row, amount in ((9, Decimal("3.75")), (10, Decimal("1.25")), (11, Decimal("2.5")), (12, expense_amount), (13, expense_amount), (14, Decimal("3.75") - expense_amount)):
            report.append((f"{letter}{row}", str(amount / Decimal(100000)), "number"))
    append_sheet(workbook, "보고", report)
    for month in range(1, 13):
        letter, revenue, expense = _column(month + 9), _column(month + 6), _column(month + 17)
        formulas = {
            9: f"SUM({letter}10:{letter}11)",
            10: f"(SUMIFS(검증!{revenue}:{revenue},검증!$B:$B,$D10,검증!$A:$A,$A$5))*0.00001",
            11: f"(SUMIFS(검증!{revenue}:{revenue},검증!$B:$B,$D11,검증!$A:$A,$A$5))*0.00001",
            12: f"SUM({letter}13:{letter}13)",
            13: f"SUMIFS(비용!{expense}:{expense},비용!$D:$D,보고!$A$5,비용!$L:$L,보고!$E:$E)/100000",
            14: f"{letter}9-{letter}12",
        }
        for row, expression in formulas.items():
            _cell(workbook, "보고", f"{letter}{row}")["formula"] = {"text": "=" + expression, "raw_text": expression, "cached_status": "stored_unverified"}
    return workbook


class SubmissionAdapterTests(unittest.TestCase):
    """추정 없는 보고 매핑·완전성 경계·원본 후보 불변을 확인한다."""

    def test_formula_mapping_controls_and_input_are_preserved(self) -> None:
        """계정이 같은 매출도 분류별로 나누고 직접 수식으로 원 환산한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 매핑·원 환산·원본 보존 계약이 다를 때.
        Side Effects:
            합성 메모리만 읽고 비교한다.
        """
        workbook = submission_workbook()
        mapped = map_workbook(workbook)
        original, candidates = deepcopy(workbook), deepcopy(mapped)
        data = build_submission_data(workbook, mapped)
        self.assertEqual((workbook, mapped), (original, candidates))
        self.assertEqual((len(data["lines"]), len(data["reportItems"])), (3, 3))
        self.assertEqual(data["diagnostics"], [])
        revenue = [line for line in data["lines"] if line["domain"] == "REVENUE"]
        self.assertEqual({line["account"]["code"] for line in revenue}, {"2200"})
        self.assertEqual(len({line["reportItemKey"] for line in revenue}), 2)
        self.assertEqual({len(line["values"]) for line in data["lines"]}, {12})
        self.assertEqual({value["valueStatus"] for value in data["controlValues"]}, {"AVAILABLE"})
        control = next(value for value in data["controlValues"] if value.get("calculationCode") == "OP" and value["month"] == 1)
        self.assertEqual(control["amount"], Decimal(3750))
        source = next(value for value in data["sourceCells"] if value["sheet"] == "보고" and value["cell"] == "J14")
        self.assertEqual((source["sourceUnit"], source["normalizedAmount"], source["cacheStatus"]), ("억원", Decimal(3750), "STORED_UNVERIFIED"))

    def test_month_slots_distinguish_zero_blank_missing_and_formula_error(self) -> None:
        """월 빈칸·저장된 빈 셀·실제 0·캐시 오류를 구별한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 누락이 0이나 정상 금액으로 변한 경우.
        Side Effects:
            합성 메모리만 수정한다.
        """
        workbook = submission_workbook()
        _cell(workbook, "비용", "S6").update(value=None, data_type="blank", has_content=False)
        expense = next(sheet for sheet in workbook["sheets"] if sheet["name"] == "비용")
        expense["cells"] = [cell for cell in expense["cells"] if cell["address"] != "T6"]
        _cell(workbook, "비용", "U6").update(value="#N/A", data_type="error", formula={"text": "=NA()", "cached_status": "error"})
        data = build_submission_data(workbook, map_workbook(workbook))
        line = next(line for line in data["lines"] if line["domain"] == "COST")
        self.assertEqual(len(line["values"]), 12)
        first = line["values"][:4]
        self.assertEqual([value["valueStatus"] for value in first], ["AVAILABLE", "MISSING", "MISSING", "UNRESOLVED"])
        self.assertEqual([value["amount"] for value in first], [Decimal(0), None, None, None])
        self.assertEqual([value["inputPresence"] for value in first], ["PRESENT", "STORED_BLANK", "ABSENT_XML_CELL", "PRESENT"])
        self.assertTrue(all(value["sourceRecordId"] for value in first))
        sources = {value["cell"]: value for value in data["sourceCells"] if value["sheet"] == "비용"}
        self.assertEqual(sources["T6"]["presence"], "EMPTY")
        self.assertEqual(sources["U6"]["cacheStatus"], "ERROR")

    def test_missing_header_and_unknown_report_are_not_filled(self) -> None:
        """없는 월 헤더와 잘못된 분류는 추정하지 않고 검증 근거로 남긴다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 누락 월·보고 항목을 임의 보충했을 때.
        Side Effects:
            합성 메모리만 수정한다.
        """
        workbook = submission_workbook()
        _cell(workbook, "검증", "R4").update(value=None, data_type="blank", has_content=False)
        _cell(workbook, "검증", "B5")["value"] = "미등록분류"
        data = build_submission_data(workbook, map_workbook(workbook))
        revenue = next(line for line in data["lines"] if line["source"] == {"sheet": "검증", "row": 5})
        self.assertEqual(len(revenue["values"]), 11)
        self.assertIsNone(revenue["reportItemKey"])
        self.assertEqual(revenue["reportMappingStatus"], "UNRESOLVED")
        self.assertIn("REPORT_MAPPING_UNRESOLVED", {issue["code"] for issue in data["diagnostics"]})
        self.assertTrue(any(control["valueStatus"] == "UNRESOLVED" for control in data["controlValues"] if control["month"] == 12))

    def test_shared_totals_and_unsupported_arithmetic_are_guarded(self) -> None:
        """공유 합계의 원문을 유지하고 추가 연산을 보고 항목으로 오인하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 공유 참조·추가 연산의 허용 범위가 달라질 때.
        Side Effects:
            합성 메모리만 수정한다.
        """
        workbook = submission_workbook()
        follower = _cell(workbook, "보고", "K9")
        follower["formula"] = {"text": None, "raw_text": None, "attributes": {"t": "shared", "si": "3"}, "shared_base": {"address": "J9", "raw_text": "SUM(J10:J11)", "attributes": {"t": "shared", "si": "3", "ref": "J9:U9"}}, "cached_status": "stored_unverified"}
        data = build_submission_data(workbook, map_workbook(workbook))
        total = next(value for value in data["controlValues"] if value.get("calculationCode") == "REVENUE_TOTAL" and value["month"] == 2)
        self.assertEqual(total["amount"], Decimal(3750))
        self.assertIsNone(follower["formula"]["text"])
        for expression in ("SUMIFS(A:A,B:B,C1,D:D,E1)+NOW()", "SUMIFS(A:A,B:B,C1,D:D,E1)+1", "SUMIFS(A:A,B:B,C1,D:D,E1)*0"):
            self.assertIsNone(_sumifs_contract(expression))
        _cell(workbook, "보고", "J14")["formula"]["text"] = "=J9+J12"
        data = build_submission_data(workbook, map_workbook(workbook))
        control = next(value for value in data["controlValues"] if value.get("calculationCode") == "OP" and value["month"] == 1)
        self.assertEqual((control["valueStatus"], control["amount"]), ("UNRESOLVED", None))

    def test_inactive_rows_stay_out_but_active_lookup_error_is_preserved(self) -> None:
        """미사용 양식만 제외하고 실제 입력의 조직 오류와 금액은 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 미사용·활성 오류 경계가 바뀌었을 때.
        Side Effects:
            합성 메모리만 수정한다.
        """
        workbook = inactive_expense_workbook()
        data = build_submission_data(workbook, map_workbook(workbook))
        self.assertEqual([line["source"]["row"] for line in data["lines"]], [6])
        workbook = submission_workbook()
        _cell(workbook, "비용", "D6").update(value="#N/A", data_type="error", formula={"text": "=NA()", "cached_status": "error"})
        data = build_submission_data(workbook, map_workbook(workbook))
        cost = next(line for line in data["lines"] if line["domain"] == "COST")
        self.assertEqual(cost["organization"]["status"], "UNRESOLVED")
        self.assertEqual(cost["values"][0]["amount"], Decimal(0))
        self.assertIn({"sheet": "비용", "cell": "D6"}, cost["organization"]["evidence"])

    def test_bridge_adds_separate_adapter_and_keeps_preview_records(self) -> None:
        """브리지에 별도 제출 근거를 추가해도 후보·금액·수식 투영은 유지한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 브리지 버전·분리 계약·후보 불변 검증이 실패할 때.
        Side Effects:
            메모리 추출을 패치하며 파일이나 환경을 바꾸지 않는다.
        """
        workbook = submission_workbook()
        workbook["diagnostics"] = []
        with patch("excel_parser.service_bridge.extract_workbook_bytes", return_value=workbook):
            response = parse_bytes(_package(), "ORG-1_2031-PLAN.xlsx")
        self.assertEqual(response["parserVersion"], "stdlib-bridge/1.3")
        self.assertEqual(response["submissionData"]["adapterVersion"], "financial-submission/1.0")
        mapped = map_workbook(workbook)
        self.assertEqual(response["summary"]["recordCount"], len(mapped["records"]))
        for actual, original in zip(response["records"], mapped["records"]):
            for field in ("value", "formula", "raw_value", "data_type"):
                self.assertEqual(actual[field], original[field])

    def test_report_reference_budget_keeps_details_and_blocks_controls(self) -> None:
        """총계 참조 작업량을 넘으면 상세를 보존하고 대조값을 미확정으로 남긴다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 상한 초과를 정상 대조값으로 표시했을 때.
        Side Effects:
            테스트 동안 메모리 상수만 패치한다.
        """
        workbook = submission_workbook()
        with patch("excel_parser.submission_adapter._MAX_REPORT_REFERENCE_VISITS", 1):
            data = build_submission_data(workbook, map_workbook(workbook))
        self.assertEqual(len(data["lines"]), 3)
        self.assertIn("REPORT_REFERENCE_LIMIT", {issue["code"] for issue in data["diagnostics"]})
        self.assertTrue(all(value["valueStatus"] == "UNRESOLVED" for value in data["controlValues"] if value.get("calculationCode")))
