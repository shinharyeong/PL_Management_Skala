"""Luna 전송의 함수 왕복·이미지·추론 상태·길이 제한을 무네트워크로 검사한다."""
from copy import deepcopy
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage

from app.config import Settings
from app.services import llm, openai_client
from app.services.responses_adapter import response_input, response_message


class OutputItem:
    """SDK 출력 항목의 직렬화 계약만 제공하는 합성 대역."""

    def __init__(self, value):
        """합성 출력 값을 보관한다.

        Args: value 테스트 사전. Returns: 없음. Raises: 없음. Side Effects: 메모리만 사용.
        """
        self.value = value

    def model_dump(self, **kwargs):
        """SDK와 같은 사전 반환 입구를 제공한다.

        Args: kwargs 직렬화 옵션. Returns: 사전 사본. Raises: 없음. Side Effects: 없음.
        """
        return deepcopy(self.value)


def response(items, *, status="completed", reason=None):
    """사용량을 포함한 합성 Responses 결과를 만든다.

    Args: items 출력 항목 목록, status 실행 상태, reason 미완료 사유.
    Returns: SDK 형태 대역. Raises: 없음. Side Effects: 없음.
    """
    return SimpleNamespace(status=status, output=[OutputItem(item) for item in items],
                           incomplete_details=SimpleNamespace(reason=reason),
                           usage=SimpleNamespace(input_tokens=20, output_tokens=30,
                                                 output_tokens_details=SimpleNamespace(reasoning_tokens=10)))


class ResponsesAdapterTests(unittest.TestCase):
    """실제 전송 없이 모델 변경 시 깨지기 쉬운 계약을 검사한다."""

    def test_luna_function_roundtrip_preserves_call_id_and_encrypted_state(self):
        """함수 식별자와 암호화 상태가 도구 결과 다음 호출까지 이어지는지 확인한다.

        Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: SDK 호출을 대역으로 차단.
        """
        first = response([{"type": "reasoning", "id": "rs-1", "summary": [], "encrypted_content": "opaque-synthetic"},
                          {"type": "function_call", "id": "fc-1", "call_id": "call-1", "name": "company_pnl", "arguments": "{}"}])
        second = response([{"type": "message", "content": [{"type": "output_text", "text": "확인했습니다."}]}])
        create = Mock(side_effect=[first, second])
        client = SimpleNamespace(responses=SimpleNamespace(create=create))
        settings = Settings(openai_model="gpt-6-luna", openai_reasoning_effort="low", ai_reasoning_effort="none")
        tool = {"type": "function", "function": {"name": "company_pnl", "description": "조회",
                "parameters": {"type": "object", "properties": {}, "additionalProperties": False}, "strict": True}}
        messages = [SystemMessage(content="허용된 조회만 사용합니다."), HumanMessage(content="손익 조회")]
        with patch.object(openai_client, "make_client", return_value=client):
            answer = llm.ask_tools(settings, messages, [tool], max_tokens=3000)
            final = llm.ask_tools(settings, [*messages, answer, ToolMessage(tool_call_id="call-1", content='{"ok":true}')], [tool])
        request = create.call_args_list[0].kwargs
        self.assertFalse(request["store"])
        self.assertEqual(request["reasoning"], {"effort": "low"})
        self.assertEqual(request["max_output_tokens"], 3000)
        self.assertNotIn("temperature", request)
        self.assertNotIn("max_tokens", request)
        self.assertNotIn("max_completion_tokens", request)
        self.assertNotIn("function", request["tools"][0])
        self.assertEqual(answer.tool_calls[0]["id"], "call-1")
        carried = create.call_args_list[1].kwargs["input"]
        self.assertEqual(carried[2]["encrypted_content"], "opaque-synthetic")
        self.assertEqual(carried[-1], {"type": "function_call_output", "call_id": "call-1", "output": '{"ok":true}'})
        self.assertEqual(final.content, "확인했습니다.")
        self.assertEqual(answer.usage_metadata["output_tokens"], 30)
        self.assertEqual(answer.response_metadata["reasoning_tokens"], 10)

    def test_luna_json_and_image_input_use_responses_contract(self):
        """기존 이미지·JSON 호출도 같은 SDK 생성 경로로 유지한다.

        Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 외부 요청 대체.
        """
        create = Mock(return_value=response([{"type": "message", "content": [{"type": "output_text", "text": '{"ok":true}'}]}]))
        client = SimpleNamespace(responses=SimpleNamespace(create=create))
        content = [{"type": "text", "text": "Return JSON."},
                   {"type": "image_url", "image_url": {"url": "data:image/png;base64,AA", "detail": "high"}}]
        with patch.object(openai_client, "make_client", return_value=client):
            result = llm.ask_json(Settings(openai_model="gpt-6-luna", ai_reasoning_effort="medium"),
                                  "Return JSON.", content, max_tokens=1000)
        self.assertTrue(result.data["ok"])
        self.assertEqual(create.call_args.kwargs["reasoning"], {"effort": "low"})
        self.assertEqual(create.call_args.kwargs["text"], {"format": {"type": "json_object"}})
        self.assertEqual(create.call_args.kwargs["input"][1]["content"][1]["type"], "input_image")
        self.assertEqual(content[1]["type"], "image_url")

    def test_incomplete_call_retains_length_signal_for_graph_recovery(self):
        """잘린 인자를 실행하지 않고 그래프 복구에 필요한 길이 종료 사유를 유지한다.

        Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 없음.
        """
        reply = response_message(response([{"type": "function_call", "id": "fc-1", "call_id": "call-1",
                                             "name": "finish_answer", "arguments": '{"summary":'}],
                                          status="incomplete", reason="max_output_tokens"))
        self.assertEqual(reply.response_metadata["finish_reason"], "length")
        self.assertEqual(reply.tool_calls, [])
        self.assertEqual(len(reply.invalid_tool_calls), 1)

    def test_refusal_and_unregistered_provider_tools_are_not_executed(self):
        """모델 거절과 등록하지 않은 내장 도구 출력을 안전하게 실패시킨다.

        Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 없음.
        """
        for item in [{"type": "message", "content": [{"type": "refusal", "refusal": "거절"}]},
                     {"type": "web_search_call"}]:
            with self.subTest(kind=item["type"]), self.assertRaises(ValueError):
                response_message(response([item]))

    def test_incomplete_json_is_rejected_even_when_syntactically_valid(self):
        """유효 JSON 모양이어도 제공자의 미완료 상태를 성공으로 바꾸지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: SDK를 대역으로 차단.
        """
        for content in ['{"ok":true}', '{"ok":']:
            create = Mock(return_value=response([{"type": "message", "content": [
                {"type": "output_text", "text": content}]}], status="incomplete", reason="max_output_tokens"))
            client = SimpleNamespace(responses=SimpleNamespace(create=create))
            with self.subTest(content=content), patch.object(openai_client, "make_client", return_value=client):
                with self.assertRaises(llm.ModelOutputLimitError) as caught:
                    llm.ask_json(Settings(openai_model="gpt-6-luna"), "Return JSON.", "확인", max_tokens=500)
                self.assertEqual((caught.exception.input_tokens, caught.exception.output_tokens), (20, 30))
                self.assertNotIn(content, str(caught.exception))

    def test_plain_reasoning_is_never_carried_or_exposed(self):
        """평문 추론은 버리고 공개 답변과 암호화 상태만 허용한다.

        Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 없음.
        """
        reply = response_message(response([{"type": "reasoning", "id": "rs-1", "summary": [{"text": "비공개 합성 추론"}]},
                                           {"type": "message", "content": [{"type": "output_text", "text": "답변"}]}]))
        self.assertEqual(response_input([reply]), [{"role": "assistant", "content": "답변"}])
        self.assertNotIn("비공개", str(reply))

    def test_single_control_tool_is_required_but_business_selection_stays_auto(self):
        """제어 단계의 자유 본문 낭비를 막고 업무 도구의 모델 선택은 유지한다.

        Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 외부 요청 대체.
        """
        create = Mock(return_value=response([]))
        client = SimpleNamespace(responses=SimpleNamespace(create=create))
        for name in ['plan_analysis', 'finish_answer', 'repair_answer', 'company_pnl']:
            tool = {"type": "function", "function": {"name": name,
                    "parameters": {"type": "object", "properties": {}}}}
            with self.subTest(name=name), patch.object(openai_client, "make_client", return_value=client):
                llm.ask_tools(Settings(openai_model="gpt-6-luna"), [HumanMessage(content="확인")], [tool])
                self.assertEqual(create.call_args.kwargs['tool_choice'], 'auto' if name == 'company_pnl' else 'required')


if __name__ == "__main__":
    unittest.main()
