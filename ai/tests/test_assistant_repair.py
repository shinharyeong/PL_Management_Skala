"""전체 답변·근거를 고정한 문장 수정과 목표 기간 경계를 무네트워크로 검증한다."""
from __future__ import annotations

from copy import deepcopy
import json
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from langchain_core.messages import HumanMessage, SystemMessage

from test_assistant_guards import (missing_arguments, native_call, observation_fixture,
                                   plan_arguments, request_fixture, selection_arguments)
from app.config import Settings
from app.decision_agent import assistant_graph as graph
from app.decision_agent.assistant_brief import apply_prose_repair, numeric_repair_fields, resolve_brief, validate_plan
from app.decision_agent.assistant_core import FinishValidationError, normalize_arguments, tool_schemas
from app.services import llm, openai_client


def repair_arguments(text: str = "계획 대비 영업이익 차이가 확인되었습니다.", path: str = "findings.0.text") -> dict:
    """허용 문장 한 곳만 수정하는 합성 모델 인자를 만든다.

    Args: text 모델이 다시 작성할 문장, path 서버가 허용할 경로.
    Returns: 로컬 repair_answer 인자. Raises: 없음. Side Effects: 없음.
    """
    return {"updates": [{"path": path, "text": text}]}


class AssistantProseRepairTests(unittest.TestCase):
    """일부 문장 수정이 근거·계획·예산 검증의 우회 경로가 되지 않는지 확인한다."""

    def setUp(self) -> None:
        """실제 환경과 무관한 합성 설정·관찰·거절될 종료 제안을 준비한다.

        Args: 없음. Returns: 없음. Raises: 없음.
        Side Effects: 테스트 프로세스의 메모리 호출 집계만 비운다.
        """
        graph.CallLog.clear()
        self.settings = Settings(openai_api_key="synthetic-key", ai_daily_call_limit=1000)
        self.observation = observation_fixture()
        self.proposal = selection_arguments()
        self.proposal["findings"][0]["text"] = "삼십억원의 계획 차이가 확인되었습니다."
        self.plan = validate_plan(plan_arguments(), request_fixture()["message"])

    def test_patch_only_changes_allowed_text_and_keeps_original_untouched(self) -> None:
        """수정 필드 외 인용·식별자·상태·계획 연결과 원본 객체가 그대로 유지된다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 허용 외 변경 또는 숫자 검증 우회.
        Side Effects: 합성 제안 사본만 생성한다.
        """
        before = deepcopy(self.proposal)
        patched = apply_prose_repair(self.proposal, repair_arguments(), ["findings.0.text"])
        expected = deepcopy(before)
        expected["findings"][0]["text"] = repair_arguments()["updates"][0]["text"]
        self.assertEqual(patched, expected)
        self.assertEqual(self.proposal, before)
        self.assertEqual(resolve_brief(patched, [self.observation], self.plan)["status"], "COMPLETED")
        still_numeric = apply_prose_repair(self.proposal, repair_arguments("수억 원의 차이가 있습니다."), ["findings.0.text"])
        with self.assertRaises(FinishValidationError) as caught:
            resolve_brief(still_numeric, [self.observation], self.plan)
        self.assertEqual(caught.exception.code, "BRIEF_NUMERIC")

    def test_patch_rejects_other_paths_duplicates_and_extra_fields(self) -> None:
        """인용·상태·다른 문장·중복·추가 필드 변경을 원문 없는 오류로 거부한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 허용 범위 이탈 수락 또는 원문 노출.
        Side Effects: 합성 입력만 검증한다.
        """
        cases = [repair_arguments(path=path) for path in ("findings.0.citationIds", "requirements.0.status", "summary", "findings.9.text")]
        cases += [{"updates": repair_arguments()["updates"] * 2}, {**repair_arguments(), "secret-extra": "private-value"}]
        for value in cases:
            with self.subTest(value=value), self.assertRaises(FinishValidationError) as caught:
                apply_prose_repair(self.proposal, value, ["findings.0.text"])
            self.assertEqual(caught.exception.code, "BRIEF_REPAIR")
            self.assertNotIn("private-value", str(caught.exception))

    def test_only_numeric_errors_with_existing_prose_paths_enable_repair(self) -> None:
        """정확한 설명 필드의 숫자 오류만 좁은 수정 모드로 전환한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 다른 계약 오류 또는 임의 경로 수락.
        Side Effects: 없음.
        """
        self.assertEqual(numeric_repair_fields(self.proposal, FinishValidationError("BRIEF_NUMERIC", ["findings.0.text"])), ["findings.0.text"])
        for code, path in (("BRIEF_SCHEMA", "findings.0.text"), ("BRIEF_NUMERIC", "findings.0.citationIds"),
                           ("BRIEF_NUMERIC", "requirements.0.followUpQuestion"), ("BRIEF_NUMERIC", "findings.-1.text")):
            self.assertEqual(numeric_repair_fields(self.proposal, FinishValidationError(code, [path])), [])
        schema = tool_schemas([], self.plan, ["findings.0.text"])
        self.assertEqual([tool["function"]["name"] for tool in schema], ["repair_answer"])
        update = schema[0]["function"]["parameters"]["$defs"]["ProseRepairUpdate"]
        self.assertEqual(update["properties"]["path"]["enum"], ["findings.0.text"])
        self.assertFalse(update["additionalProperties"])

    def test_repair_schema_matches_exact_update_count_and_errors_hide_values(self) -> None:
        """허용 경로 수를 모델 schema와 맞추고 수정 실패 원인을 원문 없이 구분한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 개수 계약 불일치·원문 오류 노출.
        Side Effects: 합성 schema와 제안만 메모리에서 검증한다.
        """
        for paths in (["findings.0.text"], ["findings.0.text", "summary"]):
            schema = tool_schemas([], self.plan, paths)[0]["function"]["parameters"]
            self.assertEqual(schema["properties"]["updates"]["minItems"], len(paths))
            self.assertEqual(schema["properties"]["updates"]["maxItems"], len(paths))
        cases = [({"updates": repair_arguments()["updates"] * 2}, "duplicate_path"),
                 (repair_arguments(path="summary"), "path_set_mismatch"),
                 ({"updates": [{"path": "findings.0.text", "text": None}]}, "string_type"),
                 ({"updates": [{"path": "findings.0.text", "text": "합성", "synthetic-private-key": "synthetic-private-value"}]}, "extra_forbidden")]
        for value, kind in cases:
            with self.subTest(kind=kind), self.assertRaises(FinishValidationError) as caught:
                apply_prose_repair(self.proposal, value, ["findings.0.text"])
            self.assertEqual(caught.exception.code, "BRIEF_REPAIR")
            self.assertIn(kind, str(caught.exception.field_issues))
            self.assertNotIn("synthetic-private", str(caught.exception))

    def test_all_numeric_findings_are_repaired_in_one_model_call(self) -> None:
        """여러 설명의 숫자 오류를 한 번에 고치면서 관찰·인용·조건·요구를 보존한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 문장별 반복 호출·원본 변경·조건 유실.
        Side Effects: 합성 모델·조회와 메모리 로그만 사용한다.
        """
        proposal = selection_arguments()
        original = proposal["findings"][0]
        proposal["findings"] = [{**deepcopy(original), "id": f"f{index}", "text": text}
                                for index, text in enumerate(("삼십억원의 차이가 있습니다.", "수억 원의 차이가 있습니다.", "절반 수준입니다."), 1)]
        proposal["findings"][1]["kind"] = "INTERPRETATION"
        proposal["findings"][1]["uncertainty"] = "동일한 비교 범위인지 추가 확인이 필요합니다."
        proposal["requirements"][0]["findingIds"] = ["f1", "f2", "f3"]
        before = deepcopy(proposal)
        with self.assertRaises(FinishValidationError) as caught:
            resolve_brief(proposal, [self.observation], self.plan)
        paths = [f"findings.{index}.text" for index in range(3)]
        self.assertEqual(caught.exception.code, "BRIEF_NUMERIC")
        self.assertEqual(caught.exception.field_issues, paths)
        updates = {"updates": [{"path": path, "text": "조회한 차이를 바탕으로 검토가 필요합니다."} for path in paths]}
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0), native_call("company_pnl", {}, 1),
                                  native_call("finish_answer", proposal, 2), native_call("repair_answer", updates, 3)])
        tool = Mock(return_value=self.observation)
        with self.assertLogs(graph.logger, level="WARNING"):
            result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model, tool_caller=tool)
        self.assertEqual((result["status"], model.call_count), ("COMPLETED", 4))
        self.assertEqual(proposal, before)
        self.assertEqual(result["observations"], [self.observation])
        self.assertEqual(result["brief"]["requirements"][0]["findingIds"], ["f1", "f2", "f3"])
        self.assertEqual(result["brief"]["findings"][1]["uncertainty"], before["findings"][1]["uncertainty"])
        schema = model.call_args.args[2][0]["function"]["parameters"]
        self.assertEqual(schema["$defs"]["ProseRepairUpdate"]["properties"]["path"]["enum"], paths)
        self.assertEqual(schema["properties"]["updates"]["maxItems"], 3)
        self.assertEqual(result["citations"][0]["path"], "/data/amountWon")
        tool.assert_called_once()

    def test_requirement_numeric_path_uses_original_proposal_order(self) -> None:
        """모델이 요구 항목을 역순으로 반환해도 실제 원본 문장만 수정한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 다른 요구의 문장 수정·계획 순서 유실.
        Side Effects: 합성 제안 사본만 검증한다.
        """
        plan = [*self.plan, {"id": "r2", "requestQuote": "확인", "question": "추가 근거는 무엇인가?"}]
        proposal = selection_arguments()
        proposal["requirements"].insert(0, {"id": "r2", "status": "NEEDS_INPUT", "findingIds": [],
                                          "missingInformation": "2027년 반영 기간의 확인이 필요합니다.",
                                          "followUpQuestion": "반영 기간을 확인할 수 있는 자료를 제공해 주세요."})
        before = deepcopy(proposal)
        with self.assertRaises(FinishValidationError) as caught:
            resolve_brief(proposal, [self.observation], plan)
        self.assertEqual(caught.exception.field_issues, ["requirements.0.missingInformation"])
        replacement = "목표 기간의 반영 범위를 확인할 자료가 필요합니다."
        patched = apply_prose_repair(proposal, repair_arguments(replacement, "requirements.0.missingInformation"), caught.exception.field_issues)
        self.assertEqual(patched["requirements"][0]["id"], "r2")
        self.assertEqual(patched["requirements"][0]["missingInformation"], replacement)
        self.assertEqual(patched["requirements"][1], before["requirements"][1])
        self.assertEqual(proposal, before)
        result = resolve_brief(patched, [self.observation], plan)
        self.assertEqual(result["status"], "NEEDS_INPUT")
        self.assertEqual([item["id"] for item in result["brief"]["requirements"]], ["r1", "r2"])
        self.assertEqual(result["brief"]["requirements"][1]["missingInformation"], replacement)

    def test_non_numeric_contract_error_takes_priority_over_collected_numbers(self) -> None:
        """앞선 숫자 오류가 있어도 뒤의 잘못된 근거·불확실성·보장을 즉시 거부한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 숫자 수집으로 다른 검증 우회.
        Side Effects: 합성 제안만 검사한다.
        """
        base = deepcopy(self.proposal)
        clean = selection_arguments()["findings"][0]
        cases = [({**clean, "id": "f2", "citationIds": ["missing-citation"]}, "CITATION_OPTION_UNKNOWN"),
                 ({**clean, "id": "f2", "kind": "INTERPRETATION", "uncertainty": None}, "BRIEF_UNCERTAINTY"),
                 ({**clean, "id": "f2", "text": "반드시 달성합니다."}, "BRIEF_ASSERTED")]
        for finding, expected in cases:
            proposal = {**base, "findings": [base["findings"][0], finding]}
            with self.subTest(expected=expected), self.assertRaises(FinishValidationError) as caught:
                resolve_brief(proposal, [self.observation], self.plan)
            self.assertEqual(caught.exception.code, expected)

    def test_numeric_collection_still_limits_repair_fields_to_eight(self) -> None:
        """숫자 오류가 많아도 기존 수정 경로 여덟 개 상한을 유지한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 수정 계약 상한 증가.
        Side Effects: 합성 제안만 검사한다.
        """
        proposal = selection_arguments()
        finding = proposal["findings"][0]
        proposal["findings"] = [{**deepcopy(finding), "id": f"f{index}", "text": "수억 원 차이입니다.",
                                "kind": "INTERPRETATION", "uncertainty": "2027년 반영은 검토가 필요합니다."} for index in range(8)]
        proposal["requirements"][0]["findingIds"] = ["f0"]
        with self.assertRaises(FinishValidationError) as caught:
            resolve_brief(proposal, [self.observation], self.plan)
        self.assertEqual(caught.exception.code, "BRIEF_NUMERIC")
        self.assertEqual(len(caught.exception.field_issues), 8)

    def test_graph_repairs_one_sentence_without_new_tools_or_full_regeneration(self) -> None:
        """실제 그래프가 좁은 수정 도구로 기존 답변을 복구하고 전체 계약을 재검증한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 추가 조회·전체 답변 재생성·근거 변경.
        Side Effects: 모델·업무 조회 대역과 메모리 로그만 사용한다.
        """
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0), native_call("company_pnl", {}, 1),
                                  native_call("finish_answer", self.proposal, 2), native_call("repair_answer", repair_arguments(), 3)])
        tool = Mock(return_value=self.observation)
        with self.assertLogs(graph.logger, level="WARNING") as captured:
            result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model, tool_caller=tool)
        self.assertEqual(result["status"], "COMPLETED")
        self.assertEqual(result["brief"]["findings"][0]["text"], repair_arguments()["updates"][0]["text"])
        self.assertEqual(result["observations"], [self.observation])
        self.assertEqual(result["brief"]["requirements"][0]["findingIds"], ["f1"])
        self.assertEqual(result["citations"][0]["path"], "/data/amountWon")
        self.assertEqual([item["function"]["name"] for item in model.call_args.args[2]], ["repair_answer"])
        self.assertLess(model.call_args.kwargs["max_tokens"], 3000)
        self.assertNotIn(self.proposal["findings"][0]["text"], str(captured.output))
        tool.assert_called_once()
        self.assertEqual(model.call_count, 4)

    def test_repair_rejects_further_lookup_and_scope_changes_within_same_budget(self) -> None:
        """수정 중 새 조회와 인용 변경을 거부하고 같은 호출 예산 안에서만 복구한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 조회 실행·근거 변경·호출 상한 이탈.
        Side Effects: 합성 모델·조회 대역만 실행한다.
        """
        for blocked in (native_call("company_pnl", {}, 3), native_call("plan_analysis", plan_arguments(), 3)):
            with self.subTest(blocked=blocked.tool_calls[0]["name"]):
                model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0), native_call("company_pnl", {}, 1),
                                          native_call("finish_answer", self.proposal, 2), blocked,
                                          native_call("repair_answer", repair_arguments(path="findings.0.citationIds"), 4),
                                          native_call("repair_answer", repair_arguments(), 5)])
                tool = Mock(return_value=self.observation)
                with self.assertLogs(graph.logger, level="WARNING"):
                    result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model, tool_caller=tool)
                self.assertEqual(result["status"], "COMPLETED")
                self.assertEqual(model.call_count, 6)
                self.assertIn("repair_answer", model.call_args.args[1][-1].content)
                tool.assert_called_once()

    def test_repair_only_strict_schema_survives_real_sdk_adapter(self) -> None:
        """실제 SDK 어댑터가 수정 도구 하나와 경로 enum·strict 계약을 보존한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 일반 도구 추가·경로 제약 유실·native 인자 손실.
        Side Effects: SDK 전송만 메모리 대역으로 바꾸며 외부 호출을 하지 않는다.
        """
        call = SimpleNamespace(id="repair-call", function=SimpleNamespace(name="repair_answer",
                               arguments=json.dumps(repair_arguments(), ensure_ascii=False)))
        response = SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=None, tool_calls=[call]))],
                                   usage=SimpleNamespace(prompt_tokens=3, completion_tokens=2))
        create = Mock(return_value=response)
        client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create)))
        with patch.object(openai_client, "make_client", return_value=client):
            reply = llm.ask_tools(self.settings, [SystemMessage(content="합성 수정 지시"), HumanMessage(content="문장 수정")],
                                  tool_schemas([], self.plan, ["findings.0.text"]), max_tokens=768)
        sent = create.call_args.kwargs
        self.assertEqual(len(sent["tools"]), 1)
        function = sent["tools"][0]["function"]
        self.assertEqual(function["name"], "repair_answer")
        self.assertTrue(function["strict"])
        update = function["parameters"]["$defs"]["ProseRepairUpdate"]
        self.assertEqual(update["properties"]["path"]["enum"], ["findings.0.text"])
        self.assertEqual(set(update["required"]), {"path", "text"})
        self.assertFalse(update["additionalProperties"])
        self.assertNotIn("response_format", sent)
        self.assertFalse(sent["parallel_tool_calls"])
        self.assertEqual(reply.tool_calls[0]["args"], repair_arguments())

    def test_repeated_bad_repairs_still_stop_at_original_model_limit(self) -> None:
        """문장 수정이 계속 실패해도 기존 모델 호출 상한을 늘리거나 거짓 완료하지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 무한 수정·가짜 완료·부분 근거 유실.
        Side Effects: 합성 모델·조회 대역만 실행한다.
        """
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0), native_call("company_pnl", {}, 1),
                                  native_call("finish_answer", self.proposal, 2)] +
                                 [native_call("repair_answer", repair_arguments("수억 원 차이입니다."), index) for index in range(3, 9)])
        with self.assertLogs(graph.logger, level="WARNING"):
            result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model,
                                         tool_caller=Mock(return_value=self.observation))
        self.assertEqual(model.call_count, 6)
        self.assertEqual(result["status"], "LIMIT_REACHED")
        self.assertIsNone(result["brief"])
        self.assertTrue(result["citations"])

    def test_repair_uses_remaining_deadline_and_discards_late_model_reply(self) -> None:
        """문장 수정의 timeout도 남은 전체 시간으로 제한하고 늦은 답변을 완료로 채택하지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 수정 단계의 시간 예산 갱신·기한 초과 수락.
        Side Effects: 합성 시계·모델·조회 대역만 실행한다.
        """
        timer, calls = Mock(return_value=0.0), []
        replies = [native_call("plan_analysis", plan_arguments(), 0), native_call("company_pnl", {}, 1),
                   native_call("finish_answer", self.proposal, 2), native_call("repair_answer", repair_arguments(), 3)]

        def model(settings: Settings, messages: list, schemas: list, **kwargs) -> object:
            """실제 대기 없이 마지막 문장 수정만 전체 마감 뒤에 반환한다.

            Args: settings 남은 timeout, messages 대화, schemas 도구 계약, kwargs 출력 옵션.
            Returns: 합성 native 호출. Raises: AssertionError: timeout 이탈.
            Side Effects: 합성 시계와 호출 목록만 변경한다.
            """
            calls.append(settings.ai_timeout_seconds)
            if len(calls) == 3:
                timer.return_value = 60.0
            elif len(calls) == 4:
                self.assertLessEqual(settings.ai_timeout_seconds, 10.0)
                timer.return_value = 71.0
            return replies[len(calls) - 1]

        with self.assertLogs(graph.logger, level="WARNING"):
            result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model,
                                         tool_caller=Mock(return_value=self.observation), clock=timer)
        self.assertEqual((len(calls), result["status"]), (4, "LIMIT_REACHED"))
        self.assertIsNone(result["brief"])
        self.assertEqual(result["observations"], [self.observation])

    def test_goal_requires_target_year_and_stops_before_mismatched_lookup(self) -> None:
        """목표 연도를 필수로 받고 불일치하면 반복 모델·업무 조회 전에 확인 안내로 끝낸다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 연도 누락·타입 오류 수락 또는 거짓 목표 비교.
        Side Effects: 합성 계획·모델만 사용하고 업무 조회는 실행하지 않는다.
        """
        for arguments in ({"targetProfitEok": "900"}, {"targetProfitEok": "900", "targetYear": "2027"},
                          {"targetProfitEok": "900", "targetYear": True}):
            with self.assertRaises(ValueError):
                normalize_arguments("goal_preview", arguments)
        request = request_fixture()
        request["message"] = "2027년 목표를 900억으로 비교해줘."
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(request["message"]), 0)] +
                                 [native_call("goal_preview", {"targetProfitEok": "900", "targetYear": 2027}, index)
                                  for index in range(1, 8)])
        tool = Mock()
        result = graph.run_assistant(request, settings=self.settings, model_caller=model, tool_caller=tool)
        self.assertEqual(result["status"], "NEEDS_INPUT")
        self.assertEqual(result["citations"], [])
        self.assertEqual(result["observations"], [])
        self.assertEqual(result["toolCalls"], [])
        self.assertEqual(result["brief"]["findings"], [])
        self.assertEqual(model.call_count, 2)
        self.assertIn("목표 연도에 맞는 마스터", result["answer"])
        tool.assert_not_called()

    def test_period_gate_preserves_earlier_observations_and_all_planned_requirements(self) -> None:
        """조회 뒤 발견한 목표 기간 불일치도 기존 자료·계획을 숨기거나 완료로 바꾸지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 기존 원장·요구 항목 유실 또는 추가 계산.
        Side Effects: 합성 모델·조회 대역만 메모리에서 실행한다.
        """
        request = request_fixture()
        request["message"] = "조직 차이를 보고 2027년 목표 900억과 비교해줘."
        plan = {"requirements": [{"id": "r1", "requestQuote": "조직 차이를 보고", "question": "조직별 차이는 무엇인가?"},
                                 {"id": "r2", "requestQuote": "2027년 목표 900억과 비교해줘", "question": "목표와 비교 가능한 기준인가?"}]}
        model = Mock(side_effect=[native_call("plan_analysis", plan, 0), native_call("company_pnl", {}, 1),
                                  native_call("goal_preview", {"targetProfitEok": "900", "targetYear": 2027}, 2)])
        tool = Mock(return_value=self.observation)
        result = graph.run_assistant(request, settings=self.settings, model_caller=model, tool_caller=tool)
        self.assertEqual(result["status"], "NEEDS_INPUT")
        self.assertEqual(result["observations"], [self.observation])
        self.assertEqual(len(result["toolCalls"]), 1)
        self.assertEqual([{key: item[key] for key in ("id", "requestQuote", "question")} for item in result["brief"]["requirements"]], plan["requirements"])
        self.assertTrue(all(item["status"] == "NEEDS_INPUT" and item["followUpQuestion"] for item in result["brief"]["requirements"]))
        self.assertEqual(model.call_count, 3)
        tool.assert_called_once()

    def test_matching_goal_year_keeps_normal_model_and_tool_flow(self) -> None:
        """기간이 같은 목표 비교는 기존 자율 조회·근거 종료 흐름을 유지한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 정상 목표 조회 차단 또는 모델 종료 생략.
        Side Effects: 합성 모델·조회 대역만 실행한다.
        """
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0),
                                  native_call("goal_preview", {"targetProfitEok": "900", "targetYear": 2026}, 1),
                                  native_call("finish_answer", selection_arguments(), 2)])
        tool = Mock(return_value=observation_fixture("goal_preview"))
        result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model, tool_caller=tool)
        self.assertEqual(result["status"], "COMPLETED")
        self.assertEqual(model.call_count, 3)
        tool.assert_called_once()
        self.assertEqual(tool.call_args.args[1]["targetYear"], 2026)

    def test_explicit_goal_period_mismatch_can_finish_without_any_lookup(self) -> None:
        """목표 기간 불일치를 이미 파악하면 조회 없이 미해결 기준과 확인 질문만 반환한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 불필요한 계산·거짓 근거·빈 안내 반환.
        Side Effects: 합성 모델 대역만 실행하고 업무 조회는 호출하지 않는다.
        """
        request = request_fixture()
        request["message"] = "2027년 영업이익 목표 900억을 비교하고 기준 연도가 다르면 먼저 알려줘."
        plan = plan_arguments(request["message"])
        plan["requirements"][0]["question"] = "요청한 목표 연도와 선택 기준이 일치하여 목표 비교가 가능한가?"
        answer = missing_arguments()
        answer["summary"] = "목표 연도와 선택한 기준 연도가 달라 현재 기준으로 목표를 비교할 수 없습니다."
        answer["requirements"][0].update(missingInformation="목표 연도와 일치하는 확정 마스터가 필요합니다.",
                                          followUpQuestion="목표 연도에 맞는 마스터를 선택하거나 목표 기간을 확인해 주실 수 있나요?")
        model = Mock(side_effect=[native_call("plan_analysis", plan, 0), native_call("finish_answer", answer, 1)])
        tool = Mock()
        result = graph.run_assistant(request, settings=self.settings, model_caller=model, tool_caller=tool)
        self.assertEqual((result["mode"], result["status"]), ("MODEL", "NEEDS_INPUT"))
        self.assertEqual(result["brief"]["findings"], [])
        self.assertTrue(result["brief"]["requirements"][0]["followUpQuestion"])
        self.assertEqual(result["observations"], [])
        self.assertEqual(result["citations"], [])
        self.assertEqual(result["toolCalls"], [])
        self.assertEqual(model.call_count, 2)
        tool.assert_not_called()
