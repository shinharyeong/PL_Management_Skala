"""공통 LangChain 모델 인터페이스와 유일한 OpenAI SDK 전송 경로.

LangGraph는 호출 순서·분기·재시도를 관리하고 이 모듈은 모델 호출 한 번을 담당한다.
`chat_model()`의 BaseChatModel을 `ask_json()`이 호출해 JSON과 토큰 수를 반환한다.
PDF VLM·자연어 SQL·보고 편집·핵심 문장이 같은 인터페이스를 사용하며,
실제 전송은 openai_client.make_client가 만든 SDK 클라이언트의 chat.completions다.
예외는 호출부가 처리해 필요한 경우 기본 질의나 원문을 사용한다.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage
from langchain_core.outputs import ChatGeneration, ChatResult

from ..config import Settings
from . import openai_client

_ROLES = {"system": "system", "human": "user", "ai": "assistant"}


class _SdkChatModel(BaseChatModel):
    """OpenAI SDK chat.completions 를 LangChain 채팅 모델로 감싼다. 메시지 내용(글자·이미지 목록)은 바꾸지 않고 넘긴다."""

    settings: Any
    model_name: str
    temperature: float
    max_tokens: int
    json_mode: bool = True

    @property
    def _llm_type(self) -> str:
        return "openai-sdk"

    def _generate(self, messages: list[BaseMessage], stop: list[str] | None = None, run_manager=None, **kwargs) -> ChatResult:
        args = {"model": self.model_name, "temperature": self.temperature, "max_tokens": self.max_tokens}
        if self.json_mode:
            args["response_format"] = {"type": "json_object"}
        args["messages"] = [{"role": _ROLES.get(m.type, m.type), "content": m.content} for m in messages]
        resp = openai_client.make_client(self.settings).chat.completions.create(**args)
        tin = int(getattr(resp.usage, "prompt_tokens", 0) or 0)
        tout = int(getattr(resp.usage, "completion_tokens", 0) or 0)
        message = AIMessage(content=resp.choices[0].message.content or "",
                            usage_metadata={"input_tokens": tin, "output_tokens": tout, "total_tokens": tin + tout})
        return ChatResult(generations=[ChatGeneration(message=message)])


def chat_model(settings: Settings, *, max_tokens: int, temperature: float | None = None, model: str | None = None,
               json_mode: bool = True) -> BaseChatModel:
    """LangChain 채팅 모델. temperature 를 주지 않으면 AI_TEMPERATURE, model 을 주지 않으면 OPENAI_MODEL."""
    return _SdkChatModel(settings=settings, model_name=model or settings.openai_model,
                         temperature=settings.ai_temperature if temperature is None else temperature,
                         max_tokens=max_tokens, json_mode=json_mode)


@dataclass(frozen=True)
class JsonReply:
    data: Any            # 파싱한 JSON(모양 검사는 호출하는 쪽)
    input_tokens: int
    output_tokens: int


def ask_json(settings: Settings, system: str, user: str | list, *, max_tokens: int, temperature: float | None = None,
             model: str | None = None) -> JsonReply:
    """JSON 응답 한 번. user 는 글자 또는 OpenAI 내용 목록(글자·image_url). JSON 이 아니면 json.JSONDecodeError."""
    reply = chat_model(settings, max_tokens=max_tokens, temperature=temperature, model=model).invoke(
        [SystemMessage(content=system), HumanMessage(content=user)])
    usage = reply.usage_metadata or {}
    return JsonReply(json.loads(reply.content), int(usage.get("input_tokens", 0)), int(usage.get("output_tokens", 0)))
