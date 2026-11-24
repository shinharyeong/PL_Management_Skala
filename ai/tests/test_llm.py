"""LLM 호출 모음(app/services/llm.py): LangChain 채팅 모델이면서 전송은 OpenAI SDK(make_client) 그대로인지 확인한다."""
from __future__ import annotations

import json
from types import SimpleNamespace

import pytest
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage

from app.config import get_settings
from app.services import llm, openai_client


class _Client:
    def __init__(self, answer, fail=None):
        self.calls, self.answer, self.fail = [], answer, fail
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self.create))

    def create(self, **kw):
        self.calls.append(kw)
        if self.fail:
            raise self.fail
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=json.dumps(self.answer)))],
                               usage=SimpleNamespace(prompt_tokens=11, completion_tokens=7))


def test_ask_json_sends_the_same_sdk_request_and_returns_tokens(monkeypatch):
    c = _Client({"ok": 1})
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    image = [{"type": "text", "text": "줄"}, {"type": "image_url", "image_url": {"url": "data:image/png;base64,AA", "detail": "high"}}]
    r = llm.ask_json(get_settings(), "시스템", image, max_tokens=99, temperature=0, model="gpt-x")
    assert (r.data, r.input_tokens, r.output_tokens) == ({"ok": 1}, 11, 7)
    assert c.calls == [{"model": "gpt-x", "temperature": 0, "max_tokens": 99, "response_format": {"type": "json_object"},
                        "messages": [{"role": "system", "content": "시스템"}, {"role": "user", "content": image}]}]   # 이미지 목록도 그대로


def test_chat_model_is_a_langchain_model_for_graphs(monkeypatch):
    c = _Client({"x": 2})
    monkeypatch.setattr(openai_client, "make_client", lambda s: c)
    s = get_settings()
    m = llm.chat_model(s, max_tokens=10)
    assert isinstance(m, BaseChatModel)
    out = m.invoke([SystemMessage(content="a"), HumanMessage(content="b")])
    assert json.loads(out.content) == {"x": 2} and out.usage_metadata["total_tokens"] == 18
    assert c.calls[0]["model"] == s.openai_model and c.calls[0]["temperature"] == s.ai_temperature


def test_errors_and_bad_json_are_raised_unchanged(monkeypatch):
    monkeypatch.setattr(openai_client, "make_client", lambda s: _Client({}, fail=TimeoutError("느림")))
    with pytest.raises(TimeoutError):
        llm.ask_json(get_settings(), "s", "u", max_tokens=5)
    bad = _Client({})
    bad.answer = None
    bad.create = lambda **kw: SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content="JSON 아님"))], usage=None)
    bad.chat = SimpleNamespace(completions=SimpleNamespace(create=bad.create))
    monkeypatch.setattr(openai_client, "make_client", lambda s: bad)
    with pytest.raises(json.JSONDecodeError):
        llm.ask_json(get_settings(), "s", "u", max_tokens=5)
