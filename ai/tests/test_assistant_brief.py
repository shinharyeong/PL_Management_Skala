"""근거 설명·질문 충족·상세 읽기를 실제 그래프에서 검증하는 무네트워크 테스트."""
from __future__ import annotations

from copy import deepcopy
import json
import unittest
from unittest.mock import Mock, patch

from langchain_core.messages import ToolMessage

from test_assistant_guards import (native_call, observation_fixture, plan_arguments,
                                   request_fixture, selection_arguments)
from app.config import Settings
from app.decision_agent import assistant_graph as graph
from app.decision_agent.assistant_brief import resolve_brief, validate_plan, validate_prose
from app.decision_agent.assistant_core import FinishValidationError, normalize_arguments, tool_schemas
from app.decision_agent.assistant_presentation import citation_catalog, model_observation


class GroundedBriefTests(unittest.TestCase):
    """실제 근거 연결과 미해결 요구 항목을 강제하는 계약 검증."""

    def setUp(self) -> None:
        """테스트마다 메모리 사용량·합성 관찰·계획을 초기화한다.

        Args: 없음. Returns: 없음. Raises: 없음.
        Side Effects: 테스트 프로세스의 메모리만 초기화한다.
        """
        graph.CallLog.clear()
        self.observation = observation_fixture()
        self.plan = validate_plan(plan_arguments(), request_fixture()["message"])
        self.settings = Settings(openai_api_key="synthetic-key", ai_daily_call_limit=1000)

    def test_plan_requires_current_user_quote_and_unique_identifiers(self) -> None:
        """계획을 현재 요청에 연결하고 이전 질문·중복 항목을 거부한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 범위 이탈 수락.
        Side Effects: 합성 사전만 검증한다.
        """
        value = plan_arguments("이전 질문에서 가져온 문장")
        with self.assertRaises(FinishValidationError) as caught:
            validate_plan(value, request_fixture()["message"])
        self.assertEqual(caught.exception.code, "PLAN_QUOTE")
        value = plan_arguments()
        value["requirements"] *= 2
        with self.assertRaises(FinishValidationError):
            validate_plan(value, request_fixture()["message"])
        self.assertEqual(self.plan[0]["requestQuote"], request_fixture()["message"])

    def test_plan_rejects_omitted_third_sentence_without_echoing_request(self) -> None:
        """앞선 문장만 인용하고 마지막 요구를 누락하면 원문 없는 오류로 거부한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 요청 범위 누락 수락 또는 원문 노출.
        Side Effects: 합성 요청과 계획만 검사한다.
        """
        sentences = ("조직별 계획 차이를 확인해줘.", "목표와 전망을 비교해줘.", "가장 먼저 확인할 점도 근거와 함께 설명해줘.")
        plan = {"requirements": [{"id": f"r{index}", "requestQuote": sentence, "question": "무엇을 확인해야 하는가?"}
                                 for index, sentence in enumerate(sentences[:2])]}
        with self.assertRaises(FinishValidationError) as caught:
            validate_plan(plan, " ".join(sentences))
        self.assertEqual(caught.exception.code, "PLAN_COVERAGE")
        for sentence in sentences:
            self.assertNotIn(sentence, str(caught.exception))

    def test_plan_accepts_each_sentence_covered_by_literal_quotes(self) -> None:
        """모든 문장을 개별 인용하면 문자 범위 계약을 통과한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 완전한 인용 범위 거부.
        Side Effects: 합성 요청과 계획만 검사하며 의미 이해를 판정하지 않는다.
        """
        sentences = ("조직별 계획 차이를 확인해줘.", "목표와 전망을 비교해줘.", "가장 먼저 확인할 점도 근거와 함께 설명해줘.")
        plan = {"requirements": [{"id": f"r{index}", "requestQuote": sentence, "question": "무엇을 확인해야 하는가?"}
                                 for index, sentence in enumerate(sentences)]}
        self.assertEqual(validate_plan(plan, " ".join(sentences)), plan["requirements"])

    def test_plan_coverage_ignores_whitespace_and_unicode_punctuation(self) -> None:
        """공백·줄바꿈·유니코드 문장부호 사이의 인용되지 않은 부분은 허용한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 공백 또는 문장부호만으로 범위 실패.
        Side Effects: 합성 요청과 계획만 검사한다.
        """
        message = " \n 조직을 확인해줘…\t「목표도 비교해줘」！  "
        plan = {"requirements": [{"id": "r1", "requestQuote": "조직을 확인해줘", "question": "어떤 조직인가?"},
                                 {"id": "r2", "requestQuote": "목표도 비교해줘", "question": "목표와의 차이는 무엇인가?"}]}
        self.assertEqual(validate_plan(plan, message), plan["requirements"])

    def test_plan_coverage_includes_all_repeated_and_overlapping_occurrences(self) -> None:
        """반복·겹침 인용을 모두 덮되 다른 위치의 추가 문자를 같은 글자로 대체하지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 반복 인용 위치 처리 오류.
        Side Effects: 합성 요청과 계획만 검사한다.
        """
        repeated = plan_arguments("가설 확인")
        self.assertEqual(validate_plan(repeated, "가설 확인. 가설 확인!"), repeated["requirements"])
        overlapping = plan_arguments("가가")
        self.assertEqual(validate_plan(overlapping, "가가가"), overlapping["requirements"])
        with self.assertRaises(FinishValidationError) as caught:
            validate_plan(plan_arguments("매출"), "매출. 매출원가.")
        self.assertEqual(caught.exception.code, "PLAN_COVERAGE")

    def test_plan_coverage_requires_numbers_and_symbols(self) -> None:
        """문자뿐 아니라 요청에 있는 숫자와 의미 있는 기호도 인용 범위에 포함한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 숫자 또는 기호 누락 수락.
        Side Effects: 합성 요청과 계획만 검사한다.
        """
        for message, quotes in (("목표900억", ("목표", "억")), ("목표 + 추가분", ("목표", "추가분"))):
            with self.subTest(message=message):
                plan = {"requirements": [{"id": f"r{index}", "requestQuote": quote, "question": "무엇을 확인해야 하는가?"}
                                         for index, quote in enumerate(quotes)]}
                with self.assertRaises(FinishValidationError) as caught:
                    validate_plan(plan, message)
                self.assertEqual(caught.exception.code, "PLAN_COVERAGE")
                self.assertEqual(validate_plan(plan_arguments(message), message)[0]["requestQuote"], message)

    def test_plan_and_finish_use_strict_native_schema(self) -> None:
        """실제 SDK로 전달할 계획·종료 도구가 필수·nullable strict 계약을 보존한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 제공자 계약 또는 인용 하한 누락.
        Side Effects: JSON 구조를 메모리에서만 검사한다.
        """
        from langchain_core.utils.function_calling import convert_to_openai_tool
        plan = tool_schemas([], [])[0]
        finish = next(tool for tool in tool_schemas(["cite-obs-1-1"], self.plan)
                      if tool["function"]["name"] == "finish_answer")
        for tool in (plan, finish):
            converted = convert_to_openai_tool(tool)["function"]
            self.assertTrue(converted["strict"])
            schema = converted["parameters"]
            for node in [schema, *schema.get("$defs", {}).values()]:
                if node.get("type") == "object":
                    self.assertFalse(node["additionalProperties"])
                    self.assertEqual(set(node["required"]), set(node["properties"]))
        finding = finish["function"]["parameters"]["$defs"]["GroundedFinding"]["properties"]
        self.assertEqual(finding["citationIds"]["minItems"], 1)
        self.assertIn({"type": "null"}, finding["uncertainty"]["anyOf"])
        self.assertEqual(finding["text"]["pattern"], r"^[^0-9０-９%]*$")
        summary = finish["function"]["parameters"]["properties"]["summary"]
        self.assertEqual(summary["pattern"], finding["text"]["pattern"])

    def test_missing_requirement_cannot_be_silently_completed(self) -> None:
        """성공 관찰이 있어도 계획 항목 누락은 거부하고 부족 자료를 표시한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 거짓 완료 허용.
        Side Effects: 없음.
        """
        plan = [*self.plan, {"id": "r2", "requestQuote": "조직", "question": "개선 가능성의 근거는 무엇인가?"}]
        proposal = selection_arguments()
        with self.assertRaises(FinishValidationError) as caught:
            resolve_brief(proposal, [self.observation], plan)
        self.assertEqual(caught.exception.code, "BRIEF_COVERAGE")
        proposal["requirements"].append({"id": "r2", "status": "NEEDS_INPUT", "findingIds": [],
                                          "missingInformation": "조직이 확인한 개선 가능액과 실행 일정이 없습니다.",
                                          "followUpQuestion": "담당 조직에서 확인한 실행 일정과 근거를 제공해 주실 수 있나요?"})
        result = resolve_brief(proposal, [self.observation], plan)
        self.assertEqual(result["status"], "NEEDS_INPUT")
        self.assertEqual(len(result["brief"]["requirements"]), 2)
        self.assertEqual(result["brief"]["requirements"][0]["question"], self.plan[0]["question"])
        proposal["requirements"][1]["followUpQuestion"] = None
        with self.assertRaises(FinishValidationError) as caught:
            resolve_brief(proposal, [self.observation], plan)
        self.assertEqual(caught.exception.code, "BRIEF_MISSING")

    def test_schema_repair_names_fields_without_exposing_values(self) -> None:
        """형식 재시도에 필요한 위치만 알려 주고 입력값과 임의 키는 숨긴다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 복구 위치 누락 또는 원문 노출.
        Side Effects: 합성 사전만 검증한다.
        """
        proposal = selection_arguments()
        proposal["findings"][0]["uncertainty"] = ""
        proposal["private-original-marker"] = "secret-original-value"
        with self.assertRaises(FinishValidationError) as caught:
            resolve_brief(proposal, [self.observation], self.plan)
        self.assertEqual(caught.exception.code, "BRIEF_SCHEMA")
        self.assertIn("findings.0.uncertainty: string_too_short", str(caught.exception))
        self.assertNotIn("private-original-marker", str(caught.exception))
        self.assertNotIn("secret-original-value", str(caught.exception))
        proposal.pop("private-original-marker")
        proposal["findings"][0]["uncertainty"] = None
        self.assertEqual(resolve_brief(proposal, [self.observation], self.plan)["status"], "COMPLETED")

    def test_uncited_suggestion_recovers_as_unresolved_requirement(self) -> None:
        """근거 없는 제안을 사실로 허용하지 않고 구체적인 확인 질문으로 복구한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 무근거 결과 수락 또는 복구 실패.
        Side Effects: 합성 모델·도구 응답을 메모리에서만 실행한다.
        """
        invalid = selection_arguments()
        invalid["findings"][0]["citationIds"] = []
        repaired = {"summary": "현재 자료로는 개선 가능성을 판단할 수 없어 추가 확인이 필요합니다.",
                    "findings": [], "requirements": [{"id": self.plan[0]["id"], "status": "NEEDS_INPUT", "findingIds": [],
                    "missingInformation": "담당 조직의 실행 일정과 비용 근거가 필요합니다.",
                    "followUpQuestion": "담당 조직이 확인한 실행 일정과 추가 비용 자료를 제공할 수 있나요?"}], "nextActions": []}
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 1),
                                  native_call("company_pnl", {}, 2), native_call("finish_answer", invalid, 3),
                                  native_call("finish_answer", repaired, 4)])
        result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model,
                                     tool_caller=Mock(return_value=self.observation))
        self.assertEqual(result["status"], "NEEDS_INPUT")
        feedback = [json.loads(item.content) for item in model.call_args.args[1] if isinstance(item, ToolMessage)]
        self.assertIn("findings.0.citationIds: too_short", feedback[-1]["message"])
        self.assertIn("finding에서 제거", feedback[-1]["message"])
        self.assertEqual(result["brief"]["findings"], [])
        self.assertTrue(result["brief"]["requirements"][0]["followUpQuestion"])

    def test_hypotheses_require_evidence_and_explicit_uncertainty(self) -> None:
        """개선 가설의 근거와 검증 조건을 생략하거나 효과를 만들어 낼 수 없다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 무근거 가설 수락.
        Side Effects: 없음.
        """
        proposal = selection_arguments()
        finding = proposal["findings"][0]
        finding.update(kind="HYPOTHESIS", text="계획 차이가 있는 조직의 집행 시점을 재검토할 수 있습니다.")
        with self.assertRaises(FinishValidationError) as caught:
            resolve_brief(proposal, [self.observation], self.plan)
        self.assertEqual(caught.exception.code, "BRIEF_UNCERTAINTY")
        finding["uncertainty"] = "절감 효과는 아직 미확정이며 담당 조직의 비용과 일정 확인이 필요합니다."
        result = resolve_brief(proposal, [self.observation], self.plan)
        self.assertEqual(result["brief"]["findings"][0]["kind"], "HYPOTHESIS")
        for text in ("삼십억원을 절감할 수 있습니다.", "30억을 절감할 수 있습니다.", "목표를 반드시 달성합니다.", "추진 승인을 완료했습니다."):
            finding["text"] = text
            with self.subTest(text=text), self.assertRaises(FinishValidationError):
                resolve_brief(proposal, [self.observation], self.plan)

    def test_numeric_organization_name_requires_its_full_actual_name_citation(self) -> None:
        """숫자 포함 조직명 예외가 임의 숫자·부분 이름 인용으로 확장되지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 근거 밖 숫자 허용.
        Side Effects: 없음.
        """
        self.observation["data"]["name"] = "사업부1"
        option = next(item for item in citation_catalog([self.observation]) if item["path"] == "/data/name")
        proposal = selection_arguments(option["id"])
        proposal["summary"] = "사업부1의 전망을 확인했습니다."
        proposal["findings"][0]["text"] = "사업부1은 선택 기준의 조회 범위에 포함됩니다."
        self.assertEqual(resolve_brief(proposal, [self.observation], self.plan)["summary"], proposal["summary"])
        citation = {key: option[key] for key in ("observationId", "path", "quote")}
        citation["quote"] = "부1"
        with self.assertRaises(FinishValidationError):
            validate_prose("사업부1을 확인합니다.", [citation], [self.observation])
        proposal["summary"] = "사업부1은 20억 개선 가능성이 있습니다."
        with self.assertRaises(FinishValidationError) as caught:
            resolve_brief(proposal, [self.observation], self.plan)
        self.assertEqual(caught.exception.field_issues, ["summary"])
        self.assertNotIn("20억", str(caught.exception))

    def test_cautious_financial_language_is_not_a_guarantee(self) -> None:
        """불확실성을 설명하는 정상 문구를 결과 보장으로 오인하지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 정상 한계 문구 거부.
        Side Effects: 없음.
        """
        for text in ("확정 마스터를 기준으로 비교했습니다.", "개선 효과는 미확정입니다.",
                     "현재 자료만으로 효과를 확정할 수 없습니다.", "승인 소명은 미래 실현을 보장할 수 없습니다."):
            with self.subTest(text=text):
                validate_prose(text, [], [])

    def test_first_step_must_be_plan_and_invalid_finish_can_be_repaired(self) -> None:
        """계획 전 도구를 실행하지 않고 누락 항목의 종료 오류를 같은 근거로 고친다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 조회·계획 순서 우회.
        Side Effects: 모델·도구 대역을 메모리에서만 실행한다.
        """
        invalid = selection_arguments()
        invalid["requirements"][0]["id"] = "invented"
        model = Mock(side_effect=[native_call("company_pnl", {}, 1), native_call("plan_analysis", plan_arguments(), 2),
                                  native_call("company_pnl", {}, 3), native_call("finish_answer", invalid, 4),
                                  native_call("finish_answer", selection_arguments(), 5)])
        tool = Mock(return_value=self.observation)
        result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model, tool_caller=tool)
        self.assertEqual(result["status"], "COMPLETED")
        tool.assert_called_once()
        first_schemas = model.call_args_list[0].args[2]
        self.assertEqual([item["function"]["name"] for item in first_schemas], ["plan_analysis"])
        feedback = [json.loads(item.content) for item in model.call_args.args[1] if isinstance(item, ToolMessage)]
        self.assertEqual(feedback[0]["code"], "PLAN_REQUIRED")
        self.assertEqual(feedback[-1]["code"], "BRIEF_COVERAGE")
        self.assertEqual(result["answer"], result["brief"]["summary"])

    def test_long_explanation_context_exposes_detail_path_and_later_page(self) -> None:
        """축약된 소명은 부재로 처리하지 않고 상세 페이지를 읽어 원문 근거로 답한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 원문 누락 숨김·상세 근거 불일치.
        Side Effects: 실제 HTTP·DB 없이 합성 페이지와 native 메시지만 사용한다.
        """
        original = observation_fixture("approved_explanations")
        tail = "담당자는 고객 인수 일정이 정해지지 않아 회복 시점을 아직 판단하기 어렵다고 설명했습니다."
        original["data"] = {"items": [{"organizationName": "미디어사업부", "text": "초기 납품 검토 내용입니다. " * 100 + tail}]}
        before = deepcopy(original)
        view = model_observation(original, citation_catalog([original]))
        self.assertTrue(view["contextTruncated"])
        self.assertIn("context", view)
        self.assertTrue(any(item["path"] == "/data/items/0/text" for item in view["availableDetails"]))
        self.assertNotIn(tail, str(view["citationOptions"]))
        offset = len(original["data"]["items"][0]["text"]) - len(tail)
        detail = observation_fixture("observation_detail", 2)
        detail["data"] = {"sourceObservationId": original["id"], "path": "/data/items/0/text", "offset": offset,
                           "nextOffset": None, "total": len(original["data"]["items"][0]["text"]), "value": tail}
        option = next(item for item in citation_catalog([original, detail]) if item["path"] == "/data/value")
        proposal = selection_arguments(option["id"])
        proposal["summary"] = "소명에는 고객 인수 일정의 불확실성이 설명되어 있습니다."
        proposal["findings"][0]["text"] = "담당자는 고객 인수 일정이 정해지지 않아 회복 시점을 판단하기 어렵다고 설명했습니다."
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0), native_call("approved_explanations", {}, 1),
                                  native_call("observation_detail", {"observationId": "obs-1", "path": "/data/items/0/text", "offset": offset}, 2),
                                  native_call("finish_answer", proposal, 3)])
        tool = Mock(side_effect=[original, detail])
        result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model, tool_caller=tool)
        self.assertEqual(result["status"], "COMPLETED")
        self.assertEqual(result["citations"][0]["quote"], tail)
        self.assertEqual(result["brief"]["findings"][0]["citations"][0]["observationId"], "obs-2")
        self.assertEqual(tool.call_count, 2)
        self.assertEqual(original, before)

    def test_detail_schema_rejects_scope_injection_and_exposes_continuation(self) -> None:
        """상세 도구의 범위 인자와 페이지 상한을 검증하고 실제 다음 위치를 알린다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 범위 우회·페이지 누락.
        Side Effects: 없음.
        """
        for args in ({"observationId": "obs-1", "path": "/secret"},
                     {"observationId": "obs-1", "path": "/data", "limit": 11},
                     {"observationId": "obs-1", "path": "/data", "offset": -1},
                     {"observationId": "obs-1", "path": "/data", "masterId": "other"}):
            with self.subTest(args=args), self.assertRaises(ValueError):
                normalize_arguments("observation_detail", args)
        detail = observation_fixture("observation_detail", 2)
        detail["data"] = {"sourceObservationId": "obs-1", "path": "/data/items", "offset": 0, "nextOffset": 5,
                           "total": 20, "value": [{"name": "합성 조직"}]}
        view = model_observation(detail, citation_catalog([detail]))
        self.assertEqual(view["availableDetails"][0]["observationId"], "obs-1")
        self.assertEqual(view["availableDetails"][0]["offset"], 5)
        self.assertEqual([item["function"]["name"] for item in tool_schemas(plan=[])], ["plan_analysis"])

    def test_detail_citations_exclude_page_metadata(self) -> None:
        """상세 페이지의 위치·길이 정보만으로 업무 사실을 만들 수 없다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 메타데이터를 업무 근거로 수락.
        Side Effects: 메모리 카탈로그 대역만 사용한다.
        """
        detail = observation_fixture("observation_detail", 2)
        detail["data"] = {"sourceObservationId": "obs-1", "path": "/data/items", "offset": 4,
                           "nextOffset": None, "total": 5, "value": [{"text": "고객 일정 확인이 필요합니다."}]}
        catalog = citation_catalog([detail])
        self.assertTrue(catalog)
        self.assertTrue(all(item["path"].startswith("/data/value/") for item in catalog))
        fake_option = {"id": "cite-metadata", "label": "페이지 위치", "observationId": "obs-2", "path": "/data/offset", "quote": "4"}
        with patch("app.decision_agent.assistant_brief.citation_catalog", return_value=[fake_option]):
            with self.assertRaises(FinishValidationError) as caught:
                resolve_brief(selection_arguments("cite-metadata"), [detail], self.plan)
        self.assertEqual(caught.exception.code, "BRIEF_FINDING")

    def test_long_detail_restarts_at_actual_visible_character_without_skipping(self) -> None:
        """서버 페이지가 커도 모델에 숨긴 중간 구간부터 작은 페이지로 이어 읽는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 다음 offset이 표시하지 않은 내용을 건너뜀.
        Side Effects: 합성 문자열과 원문 페이지 사본만 사용한다.
        """
        full = "가나다라마바사" * 220
        detail = observation_fixture("observation_detail", 2)
        detail["data"] = {"sourceObservationId": "obs-1", "path": "/data/items/0/text", "offset": 0,
                           "nextOffset": None, "total": len(full), "value": full}
        view = model_observation(detail, citation_catalog([detail]))
        visible = view["context"]["value"]
        self.assertEqual(len(visible), 480)
        self.assertEqual(view["availableDetails"][0]["offset"], len(visible))
        self.assertEqual(view["availableDetails"][0]["limit"], 1)
        combined = visible
        while view["availableDetails"]:
            instruction = view["availableDetails"][0]
            offset = instruction["offset"]
            chunk = full[offset:offset + 400]
            end = offset + len(chunk)
            detail["data"].update(offset=offset, value=chunk, nextOffset=end if end < len(full) else None)
            view = model_observation(detail, citation_catalog([detail]))
            combined += view["context"]["value"]
        self.assertEqual(combined, full)

    def test_detail_array_continuation_and_nested_text_keep_original_source_paths(self) -> None:
        """페이지 안 생략 행과 긴 필드에 detail ID가 아닌 최초 관찰의 정확한 경로를 준다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 배열 offset 또는 하위 경로 유실.
        Side Effects: 없음.
        """
        detail = observation_fixture("observation_detail", 2)
        detail["data"] = {"sourceObservationId": "obs-1", "path": "/data/items", "offset": 5,
                           "nextOffset": None, "total": 10,
                           "value": [{"name": "조직", "text": "설명" * 500}, {"name": "조직나"}, {"name": "조직다"},
                                     {"name": "조직라"}, {"name": "조직마"}]}
        view = model_observation(detail, citation_catalog([detail]))
        count = len(view["context"]["value"])
        self.assertEqual(view["availableDetails"][0]["offset"], 5 + count)
        child = next(item for item in view["availableDetails"] if item["path"] == "/data/items/5/text")
        self.assertEqual(child["observationId"], "obs-1")
        self.assertEqual(child["offset"], 0)
        self.assertEqual(child["limit"], 1)


if __name__ == "__main__":
    unittest.main()
