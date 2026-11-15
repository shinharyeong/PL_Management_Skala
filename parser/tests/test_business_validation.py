"""업무필드 검증이 원본·금액을 보존하며 동일 원본 근거에만 의존하는지 검사한다."""

import unittest
from copy import deepcopy
from decimal import Decimal

from test_mapping_monthly import append_sheet, find_record, monthly_workbook

from excel_parser.business_validation import (
    build_business_contracts,
    validate_business_row,
)
from excel_parser.mapping import _index, _layout, map_workbook


def contract_workbook() -> dict:
    """코드표와 실제 SUMIFS 참조를 갖는 독립 메모리 원본을 준비한다.

    Args:
        없음. 합성 상수와 기존 메모리 도우미만 사용한다.
    Returns:
        매출·비용·동일 원본 계약을 가진 추출 형식 사전.
    Raises:
        ValueError: 합성 주소가 잘못된 경우.
    Side Effects:
        없음. 원본·마스터·임시 파일에 접근하지 않는다.
    """
    workbook = monthly_workbook()
    append_sheet(
        workbook,
        "등록표",
        [
            ("A1", "예산코드", "string"),
            ("B1", "예산명칭", "string"),
            ("A2", "77001", "number"),
            ("B2", "사용료", "string"),
        ],
    )
    append_sheet(
        workbook, "집계 근거", [("A1", "매출", "string"), ("B1", "0", "number")]
    )
    workbook["sheets"][-1]["cells"][-1]["formula"] = {
        "text": "=SUMIFS('검증'!G:G,'검증'!$B:$B,$A1)",
        "cached_status": "stored_unverified",
    }
    return workbook


class BusinessValidationTests(unittest.TestCase):
    """숫자 읽기 성공과 업무필드 유효성을 분리하는 원본 계약을 확인한다."""

    def test_registered_fields_preserve_workbook_and_confirmed_amount(self) -> None:
        """등록 근거가 있는 정상 필드는 입력과 금액을 바꾸지 않고 통과한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 유효 필드·원문·금액 또는 출처를 잘못 처리한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        raw = contract_workbook()
        before = deepcopy(raw)
        mapped = map_workbook(raw)
        for sheet, cell, field in (
            ("검증", "H5", "management_category"),
            ("비용", "H5", "account_code"),
        ):
            record = find_record(mapped, sheet, cell)
            self.assertEqual(record["business_validation"]["status"], "valid")
            self.assertEqual(record["business_validation"][field]["status"], "valid")
            self.assertEqual(record["value"]["status"], "confirmed")
            self.assertTrue(record["business_validation"][field]["contract_digest"])
        self.assertEqual(raw, before)

    def test_unregistered_account_keeps_observed_code_and_amount(self) -> None:
        """미등록 계정을 실패로 표시해도 원문 코드와 정규금액은 유지한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 코드 삭제·금액 억제 또는 업무계약 실패 누락이 있는 경우.
        Side Effects:
            메모리만 사용한다.
        """
        raw = contract_workbook()
        next(c for c in raw["sheets"][1]["cells"] if c["address"] == "E5")["value"] = (
            "999999"
        )
        record = find_record(map_workbook(raw), "비용", "H5")
        self.assertEqual(record["item"]["code"], "999999")
        self.assertEqual(record["value"]["normalized_value"], Decimal("-1200000"))
        self.assertEqual(record["value"]["status"], "confirmed")
        validation = record["business_validation"]
        self.assertEqual(validation["status"], "invalid")
        self.assertEqual(validation["account_code"]["observed_value"], "999999")
        self.assertEqual(
            validation["issues"][0]["code"], "BUSINESS_ACCOUNT_CODE_INVALID"
        )
        self.assertLessEqual(len(validation["account_code"]["provenance"]), 2)

    def test_none_id_remains_observed_without_code_or_organization_inference(
        self,
    ) -> None:
        """None 식별자를 관측 문자열로 유지하되 업무 식별자로 인정하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 문자열을 삭제하거나 다른 업무필드를 추론한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        raw = contract_workbook()
        next(c for c in raw["sheets"][0]["cells"] if c["address"] == "E5")["value"] = (
            "계약 [ID:None; 수정:3]"
        )
        record = find_record(map_workbook(raw), "검증", "H5")
        self.assertEqual(record["item"]["detail_id"], "None")
        self.assertEqual(
            record["business_validation"]["detail_id"]["status"], "invalid"
        )
        self.assertEqual(
            record["business_validation"]["detail_id"]["observed_value"], "None"
        )
        self.assertEqual(record["organization"]["code"], "ORG-1")
        self.assertEqual(record["item"]["code"], "3100")
        self.assertEqual(record["value"]["normalized_value"], Decimal("1200000"))

    def test_management_category_uses_sumifs_criterion_not_item_label(self) -> None:
        """항목명이 정상이어도 실제 집계 조건에 없는 관리분류는 실패로 남긴다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 소분류를 관리분류로 대체하거나 원본 수식 근거를 누락한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        raw = contract_workbook()
        next(c for c in raw["sheets"][0]["cells"] if c["address"] == "B5")["value"] = (
            "미등록"
        )
        record = find_record(map_workbook(raw), "검증", "H5")
        field = record["business_validation"]["management_category"]
        self.assertEqual(field["status"], "invalid")
        self.assertEqual(field["observed_value"], "미등록")
        self.assertIn(
            ("집계 근거", "B1"), {(r["sheet"], r["cell"]) for r in field["provenance"]}
        )
        self.assertEqual(record["item"]["name"], "월정액")
        self.assertEqual(record["value"]["status"], "confirmed")

    def test_missing_contract_and_dynamic_criterion_do_not_invent_validity(
        self,
    ) -> None:
        """등록표나 해석 가능한 집계 계약이 없으면 검증 불가를 명시한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 계약 부재나 동적 조건을 유효·무효로 추정한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        raw = monthly_workbook()
        record = find_record(map_workbook(raw), "비용", "H5")
        self.assertEqual(
            record["business_validation"]["account_code"]["status"], "unverified"
        )
        raw = contract_workbook()
        raw["sheets"][-1]["cells"][-1]["formula"]["text"] = (
            '=SUMIFS(검증!G:G,검증!B:B,$A1&"*")'
        )
        record = find_record(map_workbook(raw), "검증", "H5")
        self.assertEqual(
            record["business_validation"]["management_category"]["status"], "unverified"
        )

    def test_all_blank_amount_row_uses_same_field_validation(self) -> None:
        """월 금액이 모두 없어도 행 API는 잘못된 ID를 동일하게 검증한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 금액 존재 여부 때문에 필드 검사가 생략된 경우.
        Side Effects:
            메모리만 사용한다.
        """
        raw = contract_workbook()
        sheet = raw["sheets"][0]
        next(c for c in sheet["cells"] if c["address"] == "E5")["value"] = (
            "계약 [ID:None; 수정:3]"
        )
        for cell in sheet["cells"]:
            if cell["row"] == 5 and cell["column_index"] >= 6:
                cell.update(value=None, data_type="blank", has_content=False)
        index = _index(sheet)
        validation = validate_business_row(
            index["rows"][5], _layout(sheet, index), build_business_contracts(raw)
        )
        self.assertEqual(validation["status"], "invalid")
        self.assertEqual(validation["detail_id"]["observed_value"], "None")

    def test_partial_column_ranges_do_not_define_whole_column_contract(self) -> None:
        """일부 행만 합산하는 수식을 전체 상세열의 허용 목록으로 확장하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 부분 범위 계약을 모든 행에 적용한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        raw = contract_workbook()
        raw["sheets"][-1]["cells"][-1]["formula"]["text"] = (
            "=SUMIFS(검증!G6:G9,검증!B6:B9,$A1)"
        )
        record = find_record(map_workbook(raw), "검증", "H5")
        self.assertEqual(
            record["business_validation"]["management_category"]["status"], "unverified"
        )


if __name__ == "__main__":
    unittest.main()
