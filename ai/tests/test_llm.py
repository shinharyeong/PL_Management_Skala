"""LLM 호출 모음(app/services/llm.py): LangChain 채팅 모델이면서 전송은 OpenAI SDK(make_client) 그대로인지 확인한다."""
from __future__ import annotations

import json
from types import SimpleNamespace

import pytest
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage

from app.config import Settings
from app.services import llm, openai_client


class _Client:
    """Chat Completions 요청만 기록하는 외부 전송 없는 대역."""

    def __init__(self, answer, fail=None):
        """합성 답변과 선택적 오류를 보관한다.

        Args: answer 합성 JSON, fail 발생시킬 예외.
        Returns: 없음. Raises: 없음. Side Effects: 메모리만 사용한다.
        """
        self.calls, self.answer, self.fail = [], answer, fail
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self.create))

    def create(self, **kw):
        """요청 인자를 기록하고 SDK 형태의 합성 응답을 반환한다.

        Args: kw 모델 요청 인자. Returns: 응답·사용량 대역.
        Raises: 생성자에 지정한 fail. Side Effects: 메모리 요청 목록만 변경한다.
        """
        self.calls.append(kw)
        if self.fail:
            raise self.fail
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps(self.answer)))],
                               usage=SimpleNamespace(prompt_tokens=11, completion_tokens=7))


def test_ask_json_sends_the_same_sdk_request_and_returns_tokens(monkeypatch):
    """일반 모델의 이미지·JSON 요청과 토큰 계약을 유지한다.

    Args: monkeypatch 테스트 대역. Returns: 없음. Raises: AssertionError.
    Side Effects: SDK 클라이언트를 대체해 외부 전송을 차단한다.
    """
    c = _Client({"ok": 1})
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    image = [{"type": "text", "text": "줄"}, {"type": "image_url", "image_url": {"url": "data:image/png;base64,AA", "detail": "high"}}]
    r = llm.ask_json(Settings(), "시스템", image, max_tokens=99, temperature=0, model="gpt-x")
    assert (r.data, r.input_tokens, r.output_tokens) == ({"ok": 1}, 11, 7)
    assert c.calls == [{"model": "gpt-x", "temperature": 0, "max_tokens": 99, "response_format": {"type": "json_object"},
                        "messages": [{"role": "system", "content": "시스템"}, {"role": "user", "content": image}]}]


def test_chat_model_is_a_langchain_model_for_graphs(monkeypatch):
    """일반 모델의 LangChain 인터페이스와 기본 설정 적용을 검사한다.

    Args: monkeypatch 테스트 대역. Returns: 없음. Raises: AssertionError.
    Side Effects: SDK 클라이언트를 대체하고 로컬 .env를 읽지 않는다.
    """
    c = _Client({"x": 2})
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    s = Settings()
    m = llm.chat_model(s, max_tokens=10)
    assert isinstance(m, BaseChatModel)
    out = m.invoke([SystemMessage(content="a"), HumanMessage(content="b")])
    assert json.loads(out.content) == {"x": 2} and out.usage_metadata["total_tokens"] == 18
    assert c.calls[0]["model"] == s.openai_model and c.calls[0]["temperature"] == s.ai_temperature


def test_errors_and_bad_json_are_raised_unchanged(monkeypatch):
    """전송 오류와 JSON 파싱 오류를 성공 응답으로 숨기지 않는다.

    Args: monkeypatch 테스트 대역. Returns: 없음. Raises: AssertionError.
    Side Effects: SDK 오류와 응답을 합성 대역으로 제공한다.
    """
    monkeypatch.setattr(openai_client, "make_client", lambda s: _Client({}, fail=TimeoutError("느림")))
    with pytest.raises(TimeoutError):
        llm.ask_json(Settings(), "s", "u", max_tokens=5)
    bad = _Client({})
    bad.answer = None
    bad.create = lambda **kw: SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content="JSON 아님"))], usage=None)
    bad.chat = SimpleNamespace(completions=SimpleNamespace(create=bad.create))
    monkeypatch.setattr(openai_client, "make_client", lambda s: bad)
    with pytest.raises(json.JSONDecodeError):
        llm.ask_json(Settings(), "s", "u", max_tokens=5)


def test_reasoning_models_use_max_completion_tokens_and_no_temperature(monkeypatch):
    """GPT-5·o 계열의 기존 Chat Completions 계약을 Luna 기본 설정과 독립적으로 유지한다.

    Args: monkeypatch 테스트 대역. Returns: 없음. Raises: AssertionError.
    Side Effects: SDK를 대역으로 차단한다. GPT-6의 실제 분기는 ResponsesAdapterTests에서 검사한다.
    """
    assert llm.is_reasoning_model("gpt-6-luna") and llm.is_reasoning_model("gpt-5.4-mini") and llm.is_reasoning_model("o3")
    assert not llm.is_reasoning_model("gpt-4o-mini") and not llm.is_reasoning_model("gpt-4.1") and not llm.is_reasoning_model("gpt-x")

    c = _Client({"ok": 1})
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    settings = Settings(openai_model="gpt-6-luna", openai_reasoning_effort="high", ai_reasoning_effort="none")
    for model in ("gpt-5.4-mini", "o3"):
        r = llm.ask_json(settings, "시스템", "질문", max_tokens=77, temperature=0.2, model=model)
        assert r.data == {"ok": 1}
        call = c.calls[-1]
        assert call["model"] == model and call["max_completion_tokens"] == 77 and call["reasoning_effort"] == "none"
        assert "max_tokens" not in call and "temperature" not in call and call["response_format"] == {"type": "json_object"}


def test_reasoning_tool_calls_keep_legacy_effort_and_native_schema(monkeypatch):
    """GPT-5의 도구 호출에서도 기존 추론 설정과 native function 형식을 보존한다.

    Args: monkeypatch 테스트 대역. Returns: 없음. Raises: AssertionError.
    Side Effects: SDK를 대역으로 차단하고 설정·요청을 메모리에서만 검사한다.
    """
    c = _Client({"ok": 1})
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    tool = {"type": "function", "function": {"name": "company_pnl",
            "parameters": {"type": "object", "properties": {}}}}
    llm.ask_tools(Settings(openai_model="gpt-5.4-mini", ai_reasoning_effort="none", openai_reasoning_effort="low"),
                  [HumanMessage(content="확인")], [tool], max_tokens=77)
    call = c.calls[0]
    assert call["max_completion_tokens"] == 77 and call["reasoning_effort"] == "none"
    assert "temperature" not in call and "response_format" not in call and "max_tokens" not in call
    assert call["tools"][0]["function"]["name"] == "company_pnl"
    assert call["tool_choice"] == "auto" and call["parallel_tool_calls"] is False
