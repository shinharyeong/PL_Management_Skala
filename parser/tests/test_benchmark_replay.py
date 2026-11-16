"""파일·외부 통신 없이 요청 해시와 오프라인 계획 재생 경계를 검증한다."""

import copy
import hashlib
import json
import socket
import unittest
import urllib.request
from unittest.mock import Mock, patch

from helpers import PROJECT_ROOT, extract_memory, make_package
from test_benchmark_adapters import _memory_io

from excel_parser.benchmark.adapters import run_ai_adapter
from excel_parser.benchmark.replay import (
    NetworkBlocker,
    OfflineReplayClient,
    collect_initial_requests,
)
from excel_parser_ai.client import ModelError, PROMPT_VERSION, ResponsesClient


def reference_plan() -> dict:
    """기존 메모리 OOXML의 숫자 후보를 참조 영역으로 선언한다.

    Args:
        없음.
    Returns:
        기존 구조 스키마와 실제 원본 근거 검증을 통과하는 응답 사전.
    Raises:
        없음.
    Side Effects:
        없음. 파일과 네트워크에 접근하지 않는다.
    """
    return {"plan": {
        "sheet": "검증표", "kind": "reference", "tables": [],
        "excluded_areas": [{
            "start_row": 2, "end_row": 3, "start_col": "B", "end_col": "B",
            "reason": "합성 참조 영역",
            "evidence": [{"sheet": "검증표", "cell": "A1"}],
        }],
        "uncertainties": [],
    }}


def captured_request() -> dict:
    """메모리 OOXML에서 기존 AI 초기 요청 하나를 수집한다.

    Args:
        없음.
    Returns:
        실제 구조 맥락·지시문·스키마·요청 해시를 포함한 사전.
    Raises:
        IndexError: 합성 자료의 초기 구조 요청이 없는 경우.
    Side Effects:
        메모리 ZIP만 읽는다. 실제 파일·캐시·네트워크는 사용하지 않는다.
    """
    workbook = extract_memory(make_package(), name="adapter.xlsx")
    return collect_initial_requests(workbook)["requests"][0]


class BenchmarkReplayTests(unittest.TestCase):
    """실제 요청 계약·AI 검증과 전송 차단을 메모리 자료로 검사한다."""

    def test_request_digest_matches_existing_responses_client(self) -> None:
        """수집된 해시가 기존 클라이언트의 실제 요청 본문 해시와 같은지 검사한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 요청 해시·캐시 키가 다르거나 외부 연결을 시도한 경우.
        Side Effects:
            대체 전송 Mock과 메모리 응답 캐시만 사용한다.
        """
        request = captured_request()
        response = {"status": "completed", "output": [{"content": [{
            "type": "output_text", "text": json.dumps(reference_plan()),
        }]}]}
        transport = Mock(return_value=response)
        client = ResponsesClient(
            "/unused/offline-cache", model="codex-session-plan", effort="high",
            max_output_tokens=32768, response_cache={}, transport=transport,
        )
        with NetworkBlocker() as guard:
            actual = client.ask(
                request["task"], request["instructions"],
                request["context"], request["schema"],
            )
        body = transport.call_args.args[0]
        digest = hashlib.sha256(json.dumps(
            {"version": PROMPT_VERSION, "body": body},
            ensure_ascii=False, sort_keys=True,
        ).encode()).hexdigest()
        self.assertEqual(digest, request["request_hash"])
        self.assertEqual(set(client.response_cache), {digest})
        self.assertEqual(actual, reference_plan())
        self.assertEqual(guard.blocked_attempts, [])

    def test_fixed_plan_cache_returns_isolated_copies(self) -> None:
        """첫 반환값 변경이 고정 계획과 이후 동일 요청 응답을 오염시키지 않게 한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 고정 계획·캐시·후속 응답이 변경되거나 재사용되지 않는 경우.
        Side Effects:
            메모리 계획·요청·캐시만 읽고 갱신한다. 디스크에 접근하지 않는다.
        """
        request = captured_request()
        plans = {request["request_hash"]: reference_plan()}
        original = copy.deepcopy(plans)
        client = OfflineReplayClient(plans, source_id="memory-source")
        with NetworkBlocker() as guard:
            first = client.ask(
                request["task"], request["instructions"],
                request["context"], request["schema"],
            )
            first["plan"]["excluded_areas"][0]["reason"] = "반환값만 변경"
            second = client.ask(
                request["task"], request["instructions"],
                request["context"], request["schema"], refresh=True,
            )
        self.assertEqual(plans, original)
        self.assertEqual(second, reference_plan())
        self.assertIsNot(first, second)
        self.assertEqual(client.call_count, 1)
        self.assertEqual([event["cached"] for event in client.events], [False, True])
        self.assertEqual(client.http_post_attempts, 0)
        self.assertEqual(len(client.requests[request["request_hash"]]["occurrences"]), 2)
        self.assertEqual(guard.blocked_attempts, [])

    def test_missing_plan_records_failure_without_transport_fallback(self) -> None:
        """누락된 고정 계획을 API 호출이나 임의 응답으로 대체하지 않는지 검사한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 누락 실패가 사라지거나 실제 클라이언트로 전환된 경우.
        Side Effects:
            실패 요청과 이벤트를 메모리에 남긴다. 파일·외부 통신은 사용하지 않는다.
        """
        request = captured_request()
        client = OfflineReplayClient({})
        with (
            NetworkBlocker() as guard,
            patch.object(ResponsesClient, "ask") as online_ask,
            patch.object(ResponsesClient, "_request") as online_request,
        ):
            with self.assertRaisesRegex(ModelError, "고정 구조 계획 누락"):
                client.ask(
                    request["task"], request["instructions"],
                    request["context"], request["schema"],
                )
        online_ask.assert_not_called()
        online_request.assert_not_called()
        self.assertEqual(client.call_count, 1)
        self.assertEqual(client.events[0]["error_type"], "MissingOfflinePlan")
        self.assertTrue(client.events[0]["failed"])
        self.assertEqual(client.events[0]["api_calls"], 0)
        self.assertEqual(client.http_post_attempts, 0)
        self.assertEqual(guard.blocked_attempts, [])

    def test_network_boundaries_block_before_io_and_restore(self) -> None:
        """HTTP·소켓 전송 경계가 연결 전에 차단되고 범위 종료 시 복원되는지 검사한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 차단 또는 원래 함수 복원이 누락된 경우.
        Side Effects:
            범위 내 전송 함수를 임시 교체한다. 실제 소켓 생성·연결은 수행하지 않는다.
        """
        original_urlopen = urllib.request.urlopen
        original_create = socket.create_connection
        original_connect = socket.socket.connect
        original_connect_ex = socket.socket.connect_ex
        with NetworkBlocker() as guard:
            with self.assertRaisesRegex(RuntimeError, "외부 연결"):
                urllib.request.urlopen("https://example.invalid")
            with self.assertRaisesRegex(RuntimeError, "외부 연결"):
                socket.create_connection(("example.invalid", 443))
            with self.assertRaisesRegex(RuntimeError, "외부 연결"):
                socket.socket.connect(None, ("example.invalid", 443))
            with self.assertRaisesRegex(RuntimeError, "외부 연결"):
                socket.socket.connect_ex(None, ("example.invalid", 443))
        self.assertEqual(len(guard.blocked_attempts), 4)
        self.assertIs(urllib.request.urlopen, original_urlopen)
        self.assertIs(socket.create_connection, original_create)
        self.assertIs(socket.socket.connect, original_connect)
        self.assertIs(socket.socket.connect_ex, original_connect_ex)

    def test_adapter_accepts_offline_client_without_api_permission(self) -> None:
        """오프라인 클라이언트만 API 허용 없이 기존 검증·적용 경로를 실행하게 한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 실제 AI 경로가 생략되거나 API 통계가 잘못 기록된 경우.
        Side Effects:
            합성 OOXML과 고정 계획을 메모리에서 처리한다. 파일 생성·통신은 없다.
        """
        request = captured_request()
        client = OfflineReplayClient({request["request_hash"]: reference_plan()})
        with NetworkBlocker() as guard, _memory_io(make_package()):
            result = run_ai_adapter(
                PROJECT_ROOT / "__memory__/adapter.xlsx", client=client,
                allow_api=False, review=False, template_cache={},
            )
        self.assertTrue(result["completed"], result["errors"])
        self.assertTrue(result["validation"]["extraction"]["passed"])
        self.assertTrue(result["validation"]["provenance"]["passed"])
        self.assertEqual(len(result["semantic"]["structure_plans"]), 1)
        self.assertEqual(len(result["mapped"]["records"]), 2)
        self.assertEqual(result["inference"]["api_calls"], 0)
        self.assertEqual(result["inference"]["offline_plan_loads"], 1)
        self.assertEqual(result["inference"]["offline_requests"], 1)
        self.assertEqual(result["inference"]["usage_missing_calls"], 0)
        self.assertEqual(result["inference"]["cost_status"], "not_applicable_offline_replay")
        self.assertEqual(guard.blocked_attempts, [])


if __name__ == "__main__":
    unittest.main()
