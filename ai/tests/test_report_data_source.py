"""보고서 채우기 응답의 기준 정보를 읽은 결과에서만 내보내는지 검증한다."""
from __future__ import annotations

from copy import deepcopy
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app import contract
from app.config import Settings
from app.main import app


class ReportDataSourceTests(unittest.TestCase):
    """모델·DB·파일 접근 없이 실제 채우기 결과의 출처 전달 계약을 검사한다."""

    def test_returns_actual_fill_source_without_changing_existing_blocks(self) -> None:
        """확정 조회 결과의 실제 버전·기준을 전달하며 금액·블록은 변경하지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 출처 누락·결과 변경.
        Side Effects: fill_report를 메모리 대역으로 교체한다.
        """
        base = {"year": 2026, "month": 11}
        blocks = [{"id": "profit", "kind": "metric_table"}]
        filled = {"base": base, "version": "master-2026-11", "state": "확정",
                  "blocks": [{"id": "profit", "kind": "metric_table", "rows": [[{"won": "100.125"}]]}]}
        before = deepcopy(filled)
        with patch("app.pdf_structure.data_select.fill_report", return_value=filled) as fill:
            result = contract.data(base, blocks)
        self.assertEqual(result, {"dataStatus": "CONFIRMED", "blocks": filled["blocks"],
                                  "source": {"version": "master-2026-11", "year": 2026, "month": 11}})
        self.assertEqual(fill.call_args.args, (base, blocks, None))
        self.assertEqual(filled, before)

    def test_reports_returned_period_instead_of_copying_requested_period(self) -> None:
        """요청 연월로 원천을 꾸미지 않아 호출자가 기준 불일치를 탐지할 수 있다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 잘못된 기준 은폐.
        Side Effects: 합성 채우기 결과만 사용한다.
        """
        filled = {"base": {"year": 2026, "month": 10}, "version": "master-2026-10", "state": "확정", "blocks": []}
        with patch("app.pdf_structure.data_select.fill_report", return_value=filled):
            result = contract.data({"year": 2026, "month": 11}, [])
        self.assertEqual(result["source"], {"version": "master-2026-10", "year": 2026, "month": 10})

    def test_missing_source_is_null_without_guessing_from_request(self) -> None:
        """DB·버전·반환 기준이 없으면 입력 연월을 출처라고 채우지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 원천 없는 출처 생성.
        Side Effects: 합성 사전과 대역만 사용한다.
        """
        for filled in ({"base": {"year": 2026, "month": 11}, "blocks": []},
                       {"version": "master-2026-11", "state": "확정", "blocks": []},
                       {"base": {"year": 2026, "month": 11}, "version": "", "blocks": []}):
            with self.subTest(filled=filled), patch("app.pdf_structure.data_select.fill_report", return_value=filled):
                self.assertIsNone(contract.data({"year": 2026, "month": 11}, [])["source"])

    def test_invalid_returned_source_shape_is_not_accepted(self) -> None:
        """불완전하거나 정수 범위를 벗어난 원천 기간을 유효한 출처로 내보내지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 비정상 원천 수락.
        Side Effects: 메모리 합성 결과만 검사한다.
        """
        invalid = [{"year": 2026, "month": True}, {"year": "2026", "month": 11}, {"year": 2026, "month": 13},
                   {"year": 1999, "month": 11}, {"year": 2026}, None]
        for base in invalid:
            with self.subTest(base=base), patch("app.pdf_structure.data_select.fill_report",
                    return_value={"base": base, "version": "master-2026-11", "blocks": []}):
                self.assertIsNone(contract.data({"year": 2026, "month": 11}, [])["source"])

    def test_http_contract_preserves_request_and_returns_nullable_source(self) -> None:
        """기존 HTTP 입력·응답 필드를 유지한 채 실제 source와 원천 없는 null을 전달한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 기존 계약 변경·출처 직렬화 유실.
        Side Effects: ASGI 메모리 요청만 실행하고 내부 인증·DB 조회는 합성 대역으로 제공한다.
        """
        base = {"year": 2026, "month": 11}
        request = {"base": base, "blocks": [{"id": "title", "kind": "title", "text": "경영 보고"}]}
        filled = {"base": base, "version": "master-2026-11", "state": "확정", "blocks": request["blocks"]}
        settings = Settings(require_internal_token=True, report_service_token="synthetic-source-check")
        with patch("app.api.contract.get_settings", return_value=settings), patch(
                "app.pdf_structure.data_select.fill_report", side_effect=[filled, {"base": base, "blocks": []}]):
            client = TestClient(app)
            value = client.post("/v1/data", json=request, headers={"X-Internal-Token": "synthetic-source-check"})
            empty = client.post("/v1/data", json=request, headers={"X-Internal-Token": "synthetic-source-check"})
        self.assertEqual(value.status_code, 200)
        self.assertEqual(value.json(), {"dataStatus": "CONFIRMED", "blocks": request["blocks"],
                                        "source": {"version": "master-2026-11", "year": 2026, "month": 11}})
        self.assertEqual(empty.status_code, 200)
        self.assertEqual(empty.json(), {"dataStatus": "NONE", "blocks": [], "source": None})


if __name__ == "__main__":
    unittest.main()
