"""고정 근거 탐색·목표 추출·읽기 Tool의 무네트워크 회귀 검증."""
from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

import httpx

from test_decision_agent import analysis_fixture

sys.path.insert(0, str(Path(__file__).parents[1]))
from app.config import Settings
from app.decision_agent import discovery_core as core
from app.decision_agent import discovery_graph as graph
from app.decision_agent.discovery_models import DiscoveryRequest
from app.decision_agent.discovery_tools import MAX_EVIDENCE_RESPONSE_BYTES, read_evidence
from app.services.llm import JsonReply


def discovery_fixture() -> dict:
    """토큰과 근거 목록을 포함한 합성 분석 요청을 메모리에 구성한다.

    Args: 없음.
    Returns: 실제 데이터와 무관한 DiscoveryRequest 객체용 사전.
    Raises: 없음.
    Side Effects: 없음.
    """
    return {"analysisId": "analysis-1", "revision": 3,
            "prompt": "2026년 영업이익 1,050억 원 목표. 인력 감축 없이 R&D 예산을 유지해 줘.",
            "snapshot": analysis_fixture()["snapshot"], "protectedCategories": ["HEADCOUNT"],
            "evidenceCatalog": [{"refId": "pnl-1", "kind": "PNL", "organizationId": "org-1", "label": "조직 손익"},
                                {"refId": "explanation-1", "kind": "APPROVED_EXPLANATION", "organizationId": "org-1", "label": "승인 소명"}],
            "toolAccessToken": "synthetic-capability-not-a-real-secret"}


def evidence_fixture() -> list[dict]:
    """고정 손익과 승인 소명의 합성 읽기 결과를 구성한다.

    Args: 없음.
    Returns: 허용 목록과 일치하는 근거 본문 두 개.
    Raises: 없음.
    Side Effects: 없음.
    """
    catalog = discovery_fixture()["evidenceCatalog"]
    return [{**catalog[0], "text": "A사업부 연간 전망 영업이익은 92000000000원입니다.", "facts": {"profitWon": "92000000000"}},
            {**catalog[1], "text": "납품 일정 지연으로 매출 인식이 다음 달로 이동했습니다.", "facts": {"status": "APPROVED"}}]


def model_goal_fixture() -> dict:
    """요청문 인용에 연결된 모델 목표 대역을 구성한다.

    Args: 없음.
    Returns: ModelGoalProposal 계약 객체.
    Raises: 없음.
    Side Effects: 없음.
    """
    return {"targetProfitWon": "105000000000", "targetYear": 2026, "targetQuote": "영업이익 1,050억 원 목표",
            "yearQuote": "2026년", "protectedQuotes": [{"category": "RND", "quote": "R&D 예산을 유지해 줘"}],
            "uncertainties": ["추진 조건과 잔여 기간의 실행 가능성을 확인해 주세요."]}


def model_proposal_fixture() -> dict:
    """근거 인용에 연결된 모델 가설 대역을 구성한다.

    Args: 없음.
    Returns: ModelDiscoveryProposal 계약 객체.
    Raises: 없음.
    Side Effects: 없음.
    """
    return {"goalProposal": model_goal_fixture(), "hypotheses": [
        {"organizationId": "org-1", "title": "납품 일정 정상화 가능성 검토",
         "rationale": "승인 소명에 기재된 납품 일정의 회복 가능성을 확인할 가설입니다.",
         "evidenceRefs": ["explanation-1"], "sourceQuote": "납품 일정 지연으로 매출 인식이 다음 달로 이동했습니다.",
         "category": "REVENUE", "questions": ["고객 수락과 매출 인식 요건을 만족하는지 확인할 수 있습니까?"]}]}


class DiscoveryCoreTests(unittest.TestCase):
    """금액 원문·보호 조건·근거 ID·인용의 경계 검증."""

    def test_goal_unit_conversion_is_exact_and_explicit(self) -> None:
        """요청에 명시된 원·만원·억원 단위만 정밀하게 변환한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 단위 변환이나 부호 제한이 잘못되면 발생.
        Side Effects: 없음.
        """
        self.assertEqual(core.money_values("1,050억 원"), ["105000000000"])
        self.assertEqual(core.money_values("1.25만원, 0.000001원, 2조원"), ["12500", "0.000001", "2000000000000"])
        self.assertEqual(core.money_values("-100억원, −200억원, 1e3원"), [])

    def test_fallback_does_not_guess_sales_or_ambiguous_targets(self) -> None:
        """매출 목표·복수 금액·취소 표현을 영업이익 목표로 확정하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 모호한 목표가 숫자로 제안되면 발생.
        Side Effects: 없음.
        """
        for prompt in ("매출 목표 200억원", "영업이익 100억원 말고 200억원", "영업이익 100억원 목표 취소"):
            with self.subTest(prompt=prompt):
                self.assertIsNone(core.fallback_goal(prompt, 2026)["targetProfitWon"])
        self.assertEqual(core.fallback_goal("영업이익 1,050억원 목표", 2026)["targetProfitWon"], "105000000000")

    def test_goal_quote_and_amount_must_match(self) -> None:
        """금액 단위·원문 인용·연도 불일치를 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 요청에 없는 목표가 수락되면 발생.
        Side Effects: 없음.
        """
        payload = discovery_fixture()
        for field, value in (("targetProfitWon", "999999999"), ("targetQuote", "목표 9억원"), ("targetYear", 2027)):
            proposal = model_goal_fixture()
            proposal[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                core.validate_goal(proposal, payload["prompt"], 2026)

    def test_negative_protection_is_not_keyword_approval(self) -> None:
        """보호 단어가 있어도 축소 허용·유지 부정·예외 표현을 자동 선택하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 보호 조건의 부정을 놓치면 발생.
        Side Effects: 없음.
        """
        for phrase in ("R&D 예산을 유지하지 않아도 된다", "R&D 축소 가능", "R&D 유지 말고 축소 허용"):
            proposal = model_goal_fixture()
            proposal.update(targetProfitWon=None, targetQuote=None, targetYear=2026, yearQuote=None,
                            protectedQuotes=[{"category": "RND", "quote": phrase}])
            result = core.validate_goal(proposal, phrase, 2026)
            with self.subTest(phrase=phrase):
                self.assertEqual(result["protectedCategories"], [])
                self.assertTrue(result["needsConfirmation"])

    def test_known_protection_is_retained_and_new_protection_needs_confirmation(self) -> None:
        """기존 보호 조건을 유지하며 원문 기반 추가 제안도 확인 대상으로 둔다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 기존 보호가 삭제되거나 자동 적용되면 발생.
        Side Effects: 없음.
        """
        result = core.validate_goal(model_goal_fixture(), discovery_fixture()["prompt"], 2026, ["HEADCOUNT"])
        self.assertEqual(result["protectedCategories"], ["HEADCOUNT", "RND"])
        self.assertTrue(result["needsConfirmation"])

    def test_reference_selection_is_allowlisted_and_unique(self) -> None:
        """다른 분석 ID·중복·빈 조회를 허용하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 미허용 조회가 수락되면 발생.
        Side Effects: 없음.
        """
        catalog = discovery_fixture()["evidenceCatalog"]
        for refs in (["foreign"], ["pnl-1", "pnl-1"], []):
            with self.subTest(refs=refs), self.assertRaises(ValueError):
                core.validate_references({"refIds": refs}, catalog)

    def test_read_result_requires_exact_scope_but_accepts_reordering(self) -> None:
        """응답 순서와 무관하게 분석 버전·근거 집합·조직을 대조한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 다른 범위의 읽기 응답이 수락되면 발생.
        Side Effects: 없음.
        """
        payload = discovery_fixture()
        body = {"analysisId": payload["analysisId"], "revision": 3, "items": list(reversed(evidence_fixture()))}
        refs = ["pnl-1", "explanation-1"]
        self.assertEqual(len(core.validate_read_result(body, payload, refs)), 2)
        for field, value in (("analysisId", "foreign"), ("revision", 2), ("items", evidence_fixture()[:1])):
            invalid = {**body, field: value}
            with self.subTest(field=field), self.assertRaises(ValueError):
                core.validate_read_result(invalid, payload, refs)

    def test_model_cannot_reference_unread_evidence_or_fabricate_quote(self) -> None:
        """실제 읽지 않은 ID·다른 조직·원문에 없는 인용을 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 근거 없는 가설이 수락되면 발생.
        Side Effects: 없음.
        """
        for field, value in (("evidenceRefs", ["foreign"]), ("organizationId", "other"), ("sourceQuote", "계약 완료")):
            proposal = model_proposal_fixture()
            proposal["hypotheses"][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                core.validate_discovery_proposal(proposal, discovery_fixture(), evidence_fixture())

    def test_model_cannot_smuggle_effect_into_fields_or_text(self) -> None:
        """금액 필드와 가설 문장으로 미검증 숫자·실현 확정을 추가할 수 없다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 미검증 효과가 수락되면 발생.
        Side Effects: 없음.
        """
        for field, value in (("amountWon", "3500000000"), ("recognitionMonth", 12), ("rationale", "35억 절감 확정"),
                             ("title", "절감 효과 보장"), ("questions", ["달성률 95%가 가능합니다."]),
                             ("rationale", "삼십억원이 필요합니다."), ("title", "이조원을 검토해 주세요."),
                             ("questions", ["수억 원 규모입니까?"]), ("rationale", "비용이삼십억증가했습니다.")):
            proposal = model_proposal_fixture()
            proposal["hypotheses"][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                core.validate_discovery_proposal(proposal, discovery_fixture(), evidence_fixture())

    def test_organization_words_are_allowed_in_goal_and_hypothesis(self) -> None:
        """조직·조정·조건의 단어 일부가 목표 불확실성이나 검토 가설에서 금액으로 오인되지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 근거 있는 정성 검토 문장이 숫자로 거부되면 발생.
        Side Effects: 합성 제안과 고정 근거만 메모리에서 검증한다.
        """
        for sentence in ("전사 조직의 상황을 확인해 주세요.", "이 조직의 회신이 필요합니다.",
                         "손익 차이 조정이 필요합니다.", "이 조건을 검토해 주세요."):
            proposal = model_proposal_fixture()
            proposal["goalProposal"]["uncertainties"] = [sentence]
            proposal["hypotheses"][0]["rationale"] = sentence
            with self.subTest(sentence=sentence):
                goal, hypotheses = core.validate_discovery_proposal(proposal, discovery_fixture(), evidence_fixture())
                self.assertIn(sentence, goal["uncertainties"])
                self.assertEqual(hypotheses[0]["rationale"], sentence)

    def test_verified_quote_keeps_numbers_but_effect_stays_null(self) -> None:
        """원문 숫자는 인용에만 유지하고 효과·반영 시점은 비워 둔다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 인용 숫자가 손상되거나 효과가 자동 추정되면 발생.
        Side Effects: 없음.
        """
        proposal = model_proposal_fixture()
        proposal["hypotheses"][0].update(evidenceRefs=["pnl-1"], sourceQuote=evidence_fixture()[0]["text"], category="HEADCOUNT")
        goal, hypotheses = core.validate_discovery_proposal(proposal, discovery_fixture(), evidence_fixture())
        self.assertIn("92000000000", hypotheses[0]["sourceQuote"])
        self.assertIsNone(hypotheses[0]["amountWon"])
        self.assertIsNone(hypotheses[0]["recognitionMonth"])
        self.assertTrue(hypotheses[0]["needsConfirmation"])
        self.assertIn("보호 조건", hypotheses[0]["questions"][-1])
        self.assertTrue(goal["needsConfirmation"])


class DiscoveryRuntimeTests(unittest.TestCase):
    """실제 LangGraph에 대역 모델·읽기를 연결하는 회귀 검증."""

    def setUp(self) -> None:
        """메모리 사용량을 초기화하고 비밀이 아닌 합성 설정을 준비한다.

        Args: 없음.
        Returns: 없음.
        Raises: 없음.
        Side Effects: 메모리 사용량 집계만 초기화한다.
        """
        graph.CallLog.clear()
        self.settings = Settings(openai_api_key="synthetic-key", backend_url="http://backend.internal",
                                 report_service_token="synthetic-internal", output_dir=Path("/private/tmp/pl-agent-verification/usage-namespace"))

    def test_model_runs_plan_read_propose_without_exposing_tool_token(self) -> None:
        """두 모델 호출 사이에서 실제 읽기 대역을 실행하고 토큰을 모델에 전달하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 호출 순서·상한·토큰 비공개가 깨지면 발생.
        Side Effects: 대역 모델과 읽기 함수를 메모리에서 호출한다.
        """
        payload = discovery_fixture()
        reader = Mock(return_value=evidence_fixture())
        replies = [JsonReply({"refIds": ["pnl-1", "explanation-1"]}, 1, 1), JsonReply(model_proposal_fixture(), 2, 2)]
        with patch.object(graph.llm, "ask_json", side_effect=replies) as ask:
            response = graph.discover_analysis(payload, settings=self.settings, evidence_reader=reader)
        self.assertEqual(ask.call_count, 2)
        reader.assert_called_once_with(["pnl-1", "explanation-1"])
        self.assertNotIn(payload["toolAccessToken"], str(ask.call_args_list))
        self.assertNotIn(payload["toolAccessToken"], str(response))
        self.assertEqual(response["mode"], "MODEL")
        self.assertEqual(len(response["steps"]), 5)
        self.assertIsNone(response["hypotheses"][0]["amountWon"])
        self.assertEqual(ask.call_args.args[0].ai_timeout_seconds, 20)
        self.assertEqual(ask.call_args.args[0].ai_max_retries, 0)

    def test_model_off_still_reads_fixed_evidence_before_fallback(self) -> None:
        """모델 생략 시에도 읽기 Tool에서 확인한 자료로만 기본 가설을 만든다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 읽기를 생략하거나 모델을 호출하면 발생.
        Side Effects: 읽기 대역만 호출한다.
        """
        reader = Mock(return_value=evidence_fixture())
        with patch.object(graph.llm, "ask_json") as ask:
            response = graph.discover_analysis(discovery_fixture(), settings=self.settings,
                                               allow_model=False, evidence_reader=reader)
        ask.assert_not_called()
        reader.assert_called_once()
        self.assertEqual(response["mode"], "TEMPLATE")
        self.assertTrue(response["hypotheses"])
        self.assertEqual(response["steps"][2]["status"], "COMPLETED")
        self.assertEqual(response["steps"][3]["status"], "TEMPLATE")

    def test_read_failure_or_empty_catalog_never_invents_hypotheses(self) -> None:
        """읽기 실패·빈 허용 목록에서는 근거 부족을 표시하고 후보를 만들지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 없는 근거에서 가설이 생성되면 발생.
        Side Effects: 실패 대역만 호출한다.
        """
        reader = Mock(side_effect=RuntimeError("private-tool-secret"))
        failed = graph.discover_analysis(discovery_fixture(), settings=self.settings, allow_model=False, evidence_reader=reader)
        empty = discovery_fixture()
        empty["evidenceCatalog"] = []
        empty_reader = Mock()
        no_catalog = graph.discover_analysis(empty, settings=self.settings, allow_model=False, evidence_reader=empty_reader)
        for response in (failed, no_catalog):
            self.assertEqual(response["hypotheses"], [])
            self.assertIn("근거", " ".join(response["limitations"]))
            self.assertNotIn("private-tool-secret", str(response))
        empty_reader.assert_not_called()
        self.assertEqual(failed["steps"][2]["status"], "FAILED")

    def test_invalid_model_plan_cannot_expand_tool_scope(self) -> None:
        """모델이 만든 미지 ID를 Tool에 전달하지 않고 고정 기본 조회로 복구한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 허용되지 않은 ref가 조회되면 발생.
        Side Effects: 대역 모델·읽기를 호출한다.
        """
        reader = Mock(return_value=evidence_fixture())
        replies = [JsonReply({"refIds": ["outside-analysis"]}, 1, 1), JsonReply(model_proposal_fixture(), 1, 1)]
        with patch.object(graph.llm, "ask_json", side_effect=replies):
            response = graph.discover_analysis(discovery_fixture(), settings=self.settings, evidence_reader=reader)
        reader.assert_called_once_with(["pnl-1", "explanation-1"])
        self.assertEqual(response["steps"][1]["status"], "TEMPLATE")

    def test_invalid_model_amount_falls_back_to_grounded_questions(self) -> None:
        """숫자를 추가한 모델 가설을 원문 기반 기본 질문으로 대체한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 미검증 숫자 제안이 노출되면 발생.
        Side Effects: 대역 모델·읽기를 호출한다.
        """
        proposal = model_proposal_fixture()
        proposal["hypotheses"][0]["rationale"] = "추가로 35억원 확보 가능"
        replies = [JsonReply({"refIds": ["pnl-1", "explanation-1"]}, 1, 1), JsonReply(proposal, 1, 1)]
        with patch.object(graph.llm, "ask_json", side_effect=replies):
            response = graph.discover_analysis(discovery_fixture(), settings=self.settings, evidence_reader=Mock(return_value=evidence_fixture()))
        self.assertEqual(response["mode"], "TEMPLATE")
        self.assertNotIn("35억원", str(response))
        self.assertTrue(response["hypotheses"])

    def test_goal_endpoint_logic_validates_quote_and_recovers_failure(self) -> None:
        """목표 제안 성공과 모델 실패의 기본 추출 경로를 구분한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 모델 실행 상태나 원문 목표가 잘못되면 발생.
        Side Effects: 대역 모델만 호출한다.
        """
        request = {"prompt": discovery_fixture()["prompt"], "year": 2026}
        with patch.object(graph.llm, "ask_json", return_value=JsonReply({"goalProposal": model_goal_fixture()}, 1, 1)):
            modeled = graph.interpret_goal(request, settings=self.settings)
        with patch.object(graph.llm, "ask_json", side_effect=RuntimeError("private-key")):
            fallback = graph.interpret_goal(request, settings=self.settings)
        self.assertEqual(modeled["mode"], "MODEL")
        self.assertEqual(fallback["mode"], "TEMPLATE")
        self.assertEqual(fallback["goalProposal"]["targetProfitWon"], "105000000000")
        self.assertNotIn("private-key", str(fallback))


class DiscoveryToolTests(unittest.TestCase):
    """고정 URL·capability·응답 범위를 실제 httpx 전송 대역으로 검증한다."""

    def test_read_tool_sends_scoped_headers_and_validates_response(self) -> None:
        """고정 경로에 분석·revision과 두 내부 헤더만 전달한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 경로·토큰·요청 범위가 잘못되면 발생.
        Side Effects: 메모리 전송 대역만 호출한다.
        """
        payload = discovery_fixture()

        def handler(request: httpx.Request) -> httpx.Response:
            """내부 읽기 요청을 검사하고 순서가 바뀐 고정 근거를 반환한다.

            Args: request 요청 객체.
            Returns: 합성 JSON 응답.
            Raises: AssertionError: 범위를 벗어난 요청인 경우.
            Side Effects: 없음.
            """
            self.assertEqual(str(request.url), "http://backend.internal/internal/decision-agent/evidence/read")
            self.assertEqual(request.headers["X-Decision-Tool-Token"], payload["toolAccessToken"])
            self.assertEqual(request.headers["X-Internal-Token"], "synthetic-internal")
            self.assertEqual(json.loads(request.content), {"analysisId": "analysis-1", "revision": 3, "refIds": ["pnl-1", "explanation-1"]})
            return httpx.Response(200, json={"analysisId": "analysis-1", "revision": 3, "items": list(reversed(evidence_fixture()))})

        settings = Settings(backend_url="http://backend.internal", report_service_token="synthetic-internal", require_internal_token=True)
        items = read_evidence(payload, ["pnl-1", "explanation-1"], settings, transport=httpx.MockTransport(handler))
        self.assertEqual(len(items), 2)

    def test_redirect_and_oversized_response_are_rejected(self) -> None:
        """리디렉션을 따르지 않고 과도한 응답을 파싱하기 전에 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 외부 경로 이동 또는 응답 상한 위반이 있으면 발생.
        Side Effects: 메모리 전송 대역만 호출한다.
        """
        payload = discovery_fixture()
        settings = Settings(backend_url="http://backend.internal")
        for response in (httpx.Response(302, headers={"Location": "https://outside.invalid/"}),
                         httpx.Response(200, content=b" " * (MAX_EVIDENCE_RESPONSE_BYTES + 1))):
            handler = Mock(return_value=response)
            with self.subTest(status=response.status_code), self.assertRaises((httpx.HTTPError, ValueError)):
                read_evidence(payload, ["pnl-1", "explanation-1"], settings, transport=httpx.MockTransport(handler))
            handler.assert_called_once()

    def test_unknown_reference_is_rejected_before_http(self) -> None:
        """허용 목록 밖 ID는 네트워크 호출 전에 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 미허용 요청이 전송되면 발생.
        Side Effects: 없음.
        """
        handler = Mock()
        with self.assertRaises(ValueError):
            read_evidence(discovery_fixture(), ["outside"], Settings(backend_url="http://backend.internal"), transport=httpx.MockTransport(handler))
        handler.assert_not_called()


class DiscoveryHttpTests(unittest.IsolatedAsyncioTestCase):
    """실제 내부 API 라우터와 LangGraph를 연결한 무네트워크 HTTP 검증."""

    def setUp(self) -> None:
        """실제 라우터를 등록하고 모델·백엔드 읽기만 대역으로 제한한다.

        Args: 없음.
        Returns: 없음.
        Raises: ImportError: 런타임 설치가 잘못된 경우.
        Side Effects: 메모리 ASGI 앱과 테스트 대역을 준비한다.
        """
        from fastapi import FastAPI
        from app.api import contract
        self.app = FastAPI()
        self.app.include_router(contract.router)
        self.settings = Settings(report_service_token="synthetic-internal", require_internal_token=True,
                                 backend_url="http://backend.internal")
        self.headers = {"X-Internal-Token": "synthetic-internal"}
        for module in (contract, graph):
            patcher = patch.object(module, "get_settings", return_value=self.settings)
            patcher.start()
            self.addCleanup(patcher.stop)
        reader_patch = patch.object(graph, "read_evidence", return_value=evidence_fixture())
        self.reader = reader_patch.start()
        self.addCleanup(reader_patch.stop)
        model_patch = patch.object(graph.llm, "ask_json", side_effect=AssertionError("실제 모델 호출 금지"))
        self.model = model_patch.start()
        self.addCleanup(model_patch.stop)

    async def test_both_new_endpoints_require_internal_auth_before_body_parsing(self) -> None:
        """인증 실패 시 토큰 포함 본문을 파싱하거나 Tool을 실행하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 인증 전에 기능이 실행되면 발생.
        Side Effects: 메모리 HTTP 요청만 수행한다.
        """
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url="http://test") as client:
            for endpoint in ("discover", "interpret-goal"):
                response = await client.post(f"/v1/decision-agent/{endpoint}", content=b"invalid", headers={"Content-Length": "999999"})
                self.assertEqual(response.status_code, 401)
        self.reader.assert_not_called()
        self.model.assert_not_called()

    async def test_goal_endpoint_returns_confirmation_required_template(self) -> None:
        """실제 목표 API가 명시 목표와 사용자 확인 표시를 반환한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 계약·단위·템플릿 표시가 잘못되면 발생.
        Side Effects: 메모리 HTTP 요청만 수행한다.
        """
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url="http://test") as client:
            response = await client.post("/v1/decision-agent/interpret-goal", headers=self.headers,
                                         json={"prompt": discovery_fixture()["prompt"], "year": 2026})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["mode"], "TEMPLATE")
        self.assertEqual(response.json()["goalProposal"]["targetProfitWon"], "105000000000")
        self.assertTrue(response.json()["goalProposal"]["needsConfirmation"])
        self.reader.assert_not_called()
        self.model.assert_not_called()

    async def test_discovery_endpoint_runs_real_graph_and_keeps_effect_unset(self) -> None:
        """실제 탐색 API·그래프·입력 계약을 통과해 근거 연결 제안을 반환한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 근거 조회·금액 미확정·토큰 비공개가 깨지면 발생.
        Side Effects: 메모리 HTTP와 읽기 대역만 호출한다.
        """
        payload = discovery_fixture()
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url="http://test") as client:
            response = await client.post("/v1/decision-agent/discover", json=payload, headers=self.headers)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["mode"], "TEMPLATE")
        self.assertIsNone(response.json()["hypotheses"][0]["amountWon"])
        self.assertTrue(response.json()["hypotheses"][0]["needsConfirmation"])
        self.assertNotIn(payload["toolAccessToken"], response.text)
        self.reader.assert_called_once()
        self.model.assert_not_called()

    async def test_discovery_contract_and_both_endpoint_body_limits(self) -> None:
        """미지 필드·조직 연결·과도한 본문을 기능 실행 전에 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 무효 입력이 통과하면 발생.
        Side Effects: 메모리 HTTP 요청만 수행한다.
        """
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url="http://test") as client:
            payload = discovery_fixture()
            payload["backend_url"] = "https://outside.invalid"
            unexpected = await client.post("/v1/decision-agent/discover", json=payload, headers=self.headers)
            self.assertEqual(unexpected.status_code, 422)
            payload = discovery_fixture()
            payload["evidenceCatalog"][0]["organizationId"] = "outside-org"
            mismatch = await client.post("/v1/decision-agent/discover", json=payload, headers=self.headers)
            self.assertEqual(mismatch.status_code, 422)
            for endpoint in ("discover", "interpret-goal"):
                oversized = await client.post(f"/v1/decision-agent/{endpoint}", content=b" " * (256 * 1024 + 1), headers=self.headers)
                self.assertEqual(oversized.status_code, 413)
        self.reader.assert_not_called()
        self.model.assert_not_called()


if __name__ == "__main__":
    unittest.main()
