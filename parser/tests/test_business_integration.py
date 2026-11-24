"""업무키 검증 실패가 원문·엄격 채점·행 보존에 미치는 경계를 검사한다."""

import unittest
from copy import deepcopy

from test_mapping_monthly import find_record, monthly_workbook
from test_master import detail, submission

from excel_parser.benchmark.monthly_evaluation import score_monthly_records
from excel_parser.benchmark.reconstruction import _flat
from excel_parser.mapping import map_workbook
from excel_parser.master import assemble_master


class BusinessIntegrationTests(unittest.TestCase):
    """금액 확정과 업무키 확정을 분리하고 진단 때문에 FP를 숨기지 않는다."""

    def test_flat_keeps_amount_and_observed_key_when_business_invalid(self) -> None:
        """업무키 오류가 확인돼도 원문 키와 확정 0원을 투영에서 지우지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 값·키 보존 또는 의미 상태 계약 위반.
        Side Effects:
            메모리 예시만 만든다.
        """
        record = deepcopy(find_record(map_workbook(monthly_workbook()), "검증", "G5"))
        record["item"]["code"] = "999999"
        record["item"]["detail_id"] = "None"
        record["business_validation"] = {"status": "invalid", "issues": [{"code": "INVALID_KEY"}]}
        before = deepcopy(record)
        flat = _flat(record, "synthetic", "2031-09")
        self.assertTrue(flat["confirmed"])
        self.assertFalse(flat["semantic_complete"])
        self.assertEqual(flat["normalized_value"], "0")
        self.assertEqual(flat["account_code"], "999999")
        self.assertEqual(flat["detail_id"], "None")
        self.assertEqual(flat["business_validation"], record["business_validation"])
        self.assertEqual(record, before)

    def test_invalid_business_key_excludes_totals_but_remains_fp_fn(self) -> None:
        """같은 키·금액도 업무 검증 실패 시 합계와 TP에 포함하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 금액 또는 엄격 FP/FN 기준을 바꾼 경우.
        Side Effects:
            메모리만 사용하며 실제 평가 산출물을 읽지 않는다.
        """
        truth = detail(confirmed=True)
        invalid = detail(confirmed=True, semantic_complete=False,
                         business_validation={"status": "invalid", "account_code": {"status": "invalid"}})
        master = assemble_master([submission([invalid])], ["F10"], "2026-01", "sample")
        result = master["records"][0]
        self.assertTrue(result["confirmed"])
        self.assertEqual(result["normalized_value"], "10")
        self.assertFalse(result["included_in_totals"])
        self.assertEqual(master["totals"], [])
        self.assertEqual(master["issues"][0]["code"], "business_key_unresolved")
        score = score_monthly_records(master["records"], [truth])["summary"]
        self.assertEqual((score["confirmed_output_count"], score["false_positive"], score["false_negative"]), (1, 1, 1))

    def test_invalid_literal_id_does_not_merge_physical_rows(self) -> None:
        """None 문자열 ID의 서로 다른 행을 수정본이나 동일 재전송으로 합치지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 물리행·정규값·출처가 손실된 경우.
        Side Effects:
            메모리 상세 두 개만 사용한다.
        """
        validation = {"status": "invalid", "detail_id": {"status": "invalid", "observed_value": "None"}}
        records = [detail(detail_id="None", confirmed=True, semantic_complete=False,
                          business_validation=validation, cell="I" + str(row), revision=revision)
                   for row, revision in ((5, 1), (6, 2))]
        master = assemble_master([submission(records)], ["F10"], "2026-01", "sample")
        self.assertEqual(len(master["records"]), 2)
        self.assertEqual({r["cell"] for r in master["records"]}, {"I5", "I6"})
        self.assertTrue(all(r["detail_id"] == "None" and r["normalized_value"] == "10" for r in master["records"]))
        self.assertEqual(master["excluded_records"], [])
        self.assertEqual(master["totals"], [])

    def test_source_verified_foreign_organization_is_not_rejected(self) -> None:
        """원본 행 조직이 제출 소유자와 다르더라도 유효 업무키는 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 소유자 차이만으로 값이 제외되거나 조직이 바뀐 경우.
        Side Effects:
            메모리 예시만 사용한다.
        """
        foreign = detail(organization_code="F20", confirmed=True, business_validation={"status": "valid"})
        master = assemble_master([submission([foreign])], ["F10"], "2026-01", "sample")
        self.assertTrue(master["records"][0]["included_in_totals"])
        self.assertEqual(master["records"][0]["organization_code"], "F20")
        self.assertEqual(master["status"], "complete")
        self.assertEqual(master["issues"][0]["code"], "record_organization_differs_from_submission_owner")
