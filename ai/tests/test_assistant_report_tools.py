"""보고서·전사 소명 도구의 범위와 근거 연결을 외부 접근 없이 검증한다."""
from __future__ import annotations

from copy import deepcopy
import unittest
from unittest.mock import Mock

from test_assistant_guards import native_call, observation_fixture, plan_arguments, request_fixture, selection_arguments
from app.config import Settings
from app.decision_agent import assistant_graph as graph
from app.decision_agent.assistant_core import exact_json, normalize_arguments, pointer_scalar, tool_schemas
from app.decision_agent.assistant_presentation import citation_catalog, model_observation, projected_data


def report_observation(tool: str, index: int) -> dict:
    """실제 도구 응답 계약을 따르는 보고서 합성 관찰을 만든다.

    Args: tool 보고서 도구명, index 관찰 순번.
    Returns: 기준·자료 틀·원천을 가진 합성 응답.
    Raises: KeyError: 지원하지 않는 테스트 도구.
    Side Effects: 메모리 사전만 생성한다.
    """
    value = observation_fixture(tool, index)
    data = {
        "report_templates": {"items": [{"templateId": "5", "name": "경영회의 보고", "version": 2,
            "blocks": [{"blockId": "profit", "kind": "metric_table", "title": "영업이익"}], "blockCount": 1}], "nextCursor": None},
        "report_preview": {"templateId": "5", "templateVersion": 2, "templateName": "경영회의 보고",
            "blockId": None, "masterId": "3", "year": 2026, "month": 11, "sourceVersion": "master-2026-11",
            "sourceFingerprint": "synthetic-source-fingerprint", "templateFingerprint": "synthetic-template-fingerprint",
            "capturedAt": "2026-11-26T00:00:00Z",
            "dataStatus": "CONFIRMED", "blocks": [{"id": "profit", "kind": "metric_table",
                "rows": [[{"text": "영업이익"}, {"won": "100.125", "text": "표시 값"}]]}], "saved": False},
        "report_export": {"artifactId": "synthetic-artifact", "filename": "경영회의 보고.xlsx", "contentType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "byteSize": 4000, "expiresAt": "2026-11-26T00:10:00Z", "ready": True, "masterId": "3",
            "templateId": "5", "templateVersion": 2, "format": "XLSX", "saved": False},
    }
    value["data"] = data[tool]
    return value


def overview_observation() -> dict:
    """금액 상쇄가 있어도 미분류인 전사 소명 합성 응답을 만든다.

    Args: 없음. Returns: 전사 합계·선택 상세·다음 페이지를 가진 관찰.
    Raises: 없음. Side Effects: 합성 사전만 생성한다.
    """
    value = observation_fixture("explanation_overview")
    value["data"] = {"masterId": "3", "sourceVersion": "master-2026-11", "year": 2026, "month": 11,
        "comparison": "ACTUAL_VS_PLAN", "scope": "ALL_MASTER_ORGANIZATIONS", "selectedOrganizationId": "7",
        "summary": {"acceptedCount": 4, "reviewingCount": 1, "unclassifiedAcceptedCount": 2, "fullyClassified": False},
        "categoryTotals": [{"categoryId": "8", "name": "집행 시점", "gapAmountWon": "100.125", "subtotal": False}],
        "gapTotalWon": "100.125", "classifiedTotalWon": "100.125", "matchesGapTotal": True,
        "items": [{"explanationId": "9", "organization": {"id": "7", "name": "합성 조직"},
            "item": "광고비", "gapAmount": "100.125", "answer": "집행 시점 이동 사유입니다.",
            "categoryId": "8", "suggestedCategoryId": None, "suggestionSource": None}],
        "reviewing": [], "categories": {"tableVersion": 1, "items": []}, "offset": 0, "limit": 1, "nextOffset": 1, "hasMore": True}
    return value


class AssistantReportToolTests(unittest.TestCase):
    """새 도구도 기존 native 선택·검증 경계를 통과해야 하는지 검사한다."""

    def test_arguments_preserve_defaults_and_reject_scope_or_payload_injection(self) -> None:
        """모델이 기간·사용자·SQL·직접 파일 정의를 도구에 주입할 수 없다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 잘못된 인자 수락.
        Side Effects: 합성 인자 검증만 수행한다.
        """
        self.assertEqual(normalize_arguments("report_templates", {"cursor": None}), {"limit": 10})
        self.assertEqual(normalize_arguments("report_preview", {"templateId": "5", "version": 2, "blockId": None}),
                         {"templateId": "5", "version": 2})
        self.assertEqual(normalize_arguments("report_export", {"previewObservationId": "obs-2"}), {"previewObservationId": "obs-2"})
        self.assertEqual(normalize_arguments("explanation_overview", {}),
                         {"comparison": "ACTUAL_VS_PLAN", "offset": 0, "limit": 10})
        good = {"report_templates": {}, "report_preview": {"templateId": "5", "version": 2},
                "report_export": {"previewObservationId": "obs-2"}, "explanation_overview": {}}
        for name, args in good.items():
            for extra in ({"masterId": "4"}, {"year": 2027}, {"ownerId": "8"}, {"sql": "SELECT 1"},
                          {"definition": {}}, {"url": "https://example.invalid"}):
                with self.subTest(name=name, extra=extra), self.assertRaises(ValueError):
                    normalize_arguments(name, {**args, **extra})
        invalid = [("report_templates", {"limit": 21}), ("report_templates", {"cursor": ""}),
                   ("report_preview", {"templateId": "05", "version": 2}), ("report_preview", {"templateId": "5", "version": True}),
                   ("report_preview", {"templateId": "5", "version": 2147483648}),
                   ("report_preview", {"templateId": "5", "version": 0}), ("report_preview", {"templateId": "5", "version": 2, "blockId": ""}),
                   ("report_export", {"previewObservationId": "previous-conversation"}), ("report_export", {"previewObservationId": "obs-0"}),
                   ("explanation_overview", {"comparison": "ALL"}), ("explanation_overview", {"organizationId": "007"}),
                   ("explanation_overview", {"offset": -1}), ("explanation_overview", {"limit": True})]
        for name, args in invalid:
            with self.subTest(name=name, args=args), self.assertRaises(ValueError):
                normalize_arguments(name, args)

    def test_catalog_exposes_new_tools_after_plan_without_changing_control_contract(self) -> None:
        """계획 후 새 도구를 노출하되 첫 계획과 마지막 종료 도구는 기존대로 제한한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 단계별 허용 목록 변경.
        Side Effects: 스키마 사전만 생성한다.
        """
        names = {tool["function"]["name"] for tool in tool_schemas([], plan_arguments()["requirements"])}
        self.assertTrue({"report_templates", "report_preview", "report_export", "explanation_overview"} <= names)
        self.assertEqual([tool["function"]["name"] for tool in tool_schemas(plan=[])], ["plan_analysis"])
        self.assertEqual([tool["function"]["name"] for tool in tool_schemas([], plan_arguments()["requirements"], finalization_only=True)],
                         ["finish_answer"])

    def test_report_paths_and_decimal_values_are_original_and_not_generated(self) -> None:
        """보고서 미리보기·파일 준비의 인용이 실제 scalar 경로와 정밀 문자열을 유지한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 원문 변경·파일 정보 발명.
        Side Effects: 합성 관찰의 투영·인용만 생성한다.
        """
        values = [report_observation("report_preview", 2), report_observation("report_export", 3)]
        before = deepcopy(values)
        catalog = citation_catalog(values)
        preview = projected_data(values[0])
        for key in ("sourceFingerprint", "templateFingerprint", "capturedAt"):
            self.assertNotIn(key, preview)
            self.assertFalse(any(item["path"].endswith("/" + key) for item in catalog))
        amount = next(item for item in catalog if item["path"] == "/data/blocks/0/rows/0/1/won")
        self.assertEqual(amount["quote"], "100.125")
        ready = next(item for item in catalog if item["observationId"] == "obs-3" and item["path"] == "/data/ready")
        self.assertEqual(ready["quote"], "true")
        for item in catalog:
            original = next(value for value in values if value["id"] == item["observationId"])
            scalar = pointer_scalar(original, item["path"])
            self.assertEqual(item["quote"], scalar if isinstance(scalar, str) else exact_json(scalar))
        self.assertEqual(values, before)

    def test_overview_keeps_full_scope_unclassified_and_page_limits(self) -> None:
        """합계가 맞아도 미분류·수락 전 상태와 다음 페이지가 모델 문맥에 남는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 부분 상세를 전사 전체로 바꿈.
        Side Effects: 메모리에서 투영과 인용을 검증한다.
        """
        observation = overview_observation()
        before = deepcopy(observation)
        projected = projected_data(observation)
        view = model_observation(observation, citation_catalog([observation]))
        self.assertEqual(projected["scope"], "ALL_MASTER_ORGANIZATIONS")
        self.assertTrue(projected["matchesGapTotal"])
        self.assertFalse(view["context"]["summary"]["fullyClassified"])
        self.assertEqual(view["context"]["summary"]["unclassifiedAcceptedCount"], 2)
        self.assertEqual(view["context"]["nextOffset"], 1)
        self.assertTrue(view["context"]["hasMore"])
        catalog = citation_catalog([observation])
        self.assertTrue(any(item["path"] == "/data/items/0/answer" for item in catalog))
        self.assertEqual(observation, before)

    def test_native_report_sequence_finishes_with_actual_export_readiness(self) -> None:
        """모델이 자료 틀·미리보기·XLSX 도구를 선택한 뒤 준비 결과로만 종료한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 경로 누락·가짜 파일 완료.
        Side Effects: 모델·백엔드 대역을 호출하며 실제 파일·DB·모델에는 접근하지 않는다.
        """
        graph.CallLog.clear()
        request = request_fixture()
        request["message"] = "경영회의 보고서를 엑셀로 준비해줘"
        plan = plan_arguments(request["message"])
        plan["requirements"][0]["question"] = "다운로드할 경영회의 보고서가 준비되어 있는가?"
        values = [report_observation("report_templates", 1), report_observation("report_preview", 2), report_observation("report_export", 3)]
        option = next(item for item in citation_catalog(values) if item["observationId"] == "obs-3" and item["path"] == "/data/ready")
        finish = selection_arguments(option["id"])
        finish["summary"] = finish["findings"][0]["text"] = "다운로드할 보고서를 확인할 수 있습니다."
        model = Mock(side_effect=[native_call("plan_analysis", plan, 0), native_call("report_templates", {}, 1),
            native_call("report_preview", {"templateId": "5", "version": 2}, 2),
            native_call("report_export", {"previewObservationId": "obs-2"}, 3), native_call("finish_answer", finish, 4)])
        callback = Mock(side_effect=values)
        result = graph.run_assistant(request, settings=Settings(openai_api_key="synthetic-key", ai_daily_call_limit=1000),
                                     model_caller=model, tool_caller=callback)
        self.assertEqual(result["status"], "COMPLETED")
        self.assertEqual([call.args[0] for call in callback.call_args_list], ["report_templates", "report_preview", "report_export"])
        self.assertEqual(result["citations"][0]["path"], "/data/ready")
        self.assertEqual(callback.call_args_list[-1].args[1], {"previewObservationId": "obs-2"})

    def test_export_failure_has_no_download_citation_or_completed_claim(self) -> None:
        """내보내기 실패 관찰을 파일 준비 근거로 인용하지 않고 요구를 미해결로 남긴다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 실패 파일의 거짓 완료 처리.
        Side Effects: 합성 모델·백엔드 대역만 실행한다.
        """
        graph.CallLog.clear()
        request = request_fixture()
        request["message"] = "보고서를 엑셀로 준비해줘"
        plan = plan_arguments(request["message"])
        error = observation_fixture("report_export", status="ERROR")
        error["warnings"] = ["미리보기 기준을 다시 확인해 주세요."]
        finish = {"summary": "다운로드할 보고서 기준을 다시 확인해야 합니다.", "findings": [],
            "requirements": [{"id": "r1", "status": "NEEDS_INPUT", "findingIds": [],
                "missingInformation": "기준이 일치하는 보고서 미리보기가 필요합니다.", "followUpQuestion": "사용할 자료 틀과 기준을 확인해 주세요."}],
            "nextActions": []}
        model = Mock(side_effect=[native_call("plan_analysis", plan, 0), native_call("report_export", {"previewObservationId": "obs-2"}, 1),
                                  native_call("finish_answer", finish, 2)])
        result = graph.run_assistant(request, settings=Settings(openai_api_key="synthetic-key", ai_daily_call_limit=1000),
                                     model_caller=model, tool_caller=Mock(return_value=error))
        self.assertEqual(result["status"], "NEEDS_INPUT")
        self.assertEqual(result["citations"], [])
        self.assertEqual(citation_catalog([error]), [])


if __name__ == "__main__":
    unittest.main()
