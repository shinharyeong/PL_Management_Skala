"""공통 LangChain 모델 인터페이스와 유일한 OpenAI SDK 전송 경로.

LangGraph는 호출 순서·분기·재시도를 관리하고 이 모듈은 모델 호출 한 번을 담당한다.
`chat_model()`의 BaseChatModel을 `ask_json()`이 호출해 JSON과 토큰 수를 반환한다.
PDF VLM·자연어 SQL·보고 편집·핵심 문장이 같은 인터페이스를 사용하며,
실제 전송은 openai_client.make_client가 만든 SDK 클라이언트의 chat.completions다.
예외는 호출부가 처리해 필요한 경우 기본 질의나 원문을 사용한다.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from collections.abc import Sequence
from typing import Any

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.runnables import Runnable
from langchain_core.utils.function_calling import convert_to_openai_tool

from ..config import Settings
from . import openai_client

_ROLES = {"system": "system", "human": "user", "ai": "assistant"}


def sdk_message(message: BaseMessage) -> dict[str, Any]:
    """LangChain 메시지를 SDK의 일반·도구 관찰 메시지로 변환한다.

    Args:
        message: SystemMessage·HumanMessage·AIMessage·ToolMessage 중 하나.
    Returns:
        기존 text·image content를 보존한 SDK 메시지.
    Raises:
        ValueError: 허용하지 않은 메시지 역할인 경우.
    Side Effects:
        없음.
    """
    if isinstance(message, ToolMessage):
        return {"role": "tool", "tool_call_id": message.tool_call_id, "content": message.content}
    if message.type not in _ROLES:
        raise ValueError("지원하지 않는 모델 메시지 역할입니다.")
    output = {"role": _ROLES[message.type], "content": message.content}
    if isinstance(message, AIMessage) and (message.tool_calls or message.invalid_tool_calls):
        calls = [{"id": call["id"], "type": "function", "function": {"name": call["name"],
                  "arguments": json.dumps(call["args"], ensure_ascii=False, separators=(",", ":"))}}
                 for call in message.tool_calls]
        calls.extend({"id": call["id"], "type": "function", "function": {"name": call.get("name") or "invalid_tool",
                       "arguments": call.get("args") or "{}"}} for call in message.invalid_tool_calls)
        output["tool_calls"] = calls
        output["content"] = message.content or None
    return output


def sdk_tool_calls(message: Any) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """SDK native function 호출을 LangChain 호출과 파싱 오류로 나눈다.

    Args:
        message: SDK 응답의 assistant 메시지.
    Returns:
        정상 tool_calls와 invalid_tool_calls 목록.
    Raises:
        없음. 인자 JSON 오류는 실행할 수 없는 호출로 보존한다.
    Side Effects:
        없음.
    """
    valid, invalid = [], []
    for call in getattr(message, "tool_calls", None) or []:
        function = getattr(call, "function", None)
        name, raw, identifier = getattr(function, "name", "invalid_tool"), getattr(function, "arguments", ""), getattr(call, "id", None)
        try:
            arguments = json.loads(raw)
            if not isinstance(arguments, dict) or not isinstance(name, str) or not name:
                raise ValueError("도구 인자는 JSON 객체여야 합니다.")
            valid.append({"name": name, "args": arguments, "id": identifier, "type": "tool_call"})
        except (ValueError, TypeError):
            invalid.append({"name": name, "args": raw if isinstance(raw, str) else "", "id": identifier,
                            "error": "도구 인자 JSON 계약 오류", "type": "invalid_tool_call"})
    return valid, invalid


class _SdkChatModel(BaseChatModel):
    """OpenAI SDK chat.completions 를 LangChain 채팅 모델로 감싼다. 메시지 내용(글자·이미지 목록)은 바꾸지 않고 넘긴다."""

    settings: Any
    model_name: str
    temperature: float
    max_tokens: int
    json_mode: bool = True

    @property
    def _llm_type(self) -> str:
        """공통 SDK 모델의 종류를 반환한다.

        Args: 없음.
        Returns: 모델 종류 식별자.
        Raises: 없음.
        Side Effects: 없음.
        """
        return "openai-sdk"

    def bind_tools(self, tools: Sequence[Any], *, tool_choice: str | dict | None = None, **kwargs: Any) -> Runnable:
        """기존 SDK 모델에 native function schema를 결합한다.

        Args:
            tools: OpenAI function schema 또는 LangChain이 변환할 수 있는 도구 정의.
            tool_choice: auto·required·none 또는 특정 함수 선택.
            kwargs: parallel_tool_calls 등 공통 모델 바인딩 옵션.
        Returns:
            native tool_calls를 반환하는 Runnable.
        Raises:
            ValueError: 도구 정의를 변환할 수 없는 경우.
        Side Effects:
            없음. 실제 호출은 invoke 시에만 수행한다.
        """
        return self.bind(tools=[convert_to_openai_tool(tool) for tool in tools],
                         tool_choice="required" if tool_choice == "any" else (tool_choice or "auto"),
                         parallel_tool_calls=kwargs.get("parallel_tool_calls", False))

    def _generate(self, messages: list[BaseMessage], stop: list[str] | None = None, run_manager: Any = None, **kwargs: Any) -> ChatResult:
        """기존 JSON 응답과 native tool 응답을 동일한 SDK 경로로 처리한다.

        Args:
            messages: 모델 대화와 실제 Tool 관찰.
            stop: LangChain 호환용 중단 문자열. 기존 전송 동작을 유지한다.
            run_manager: LangChain 실행 관리 객체.
            kwargs: 바인딩된 tools·tool_choice·parallel_tool_calls.
        Returns:
            token usage와 tool_calls를 포함한 ChatResult.
        Raises:
            SDK 오류: 제공자 요청 실패.
            ValueError: 지원하지 않는 메시지 역할.
        Side Effects:
            openai_client.make_client로 SDK 요청 한 번을 수행한다. 원문 로그·파일을 만들지 않는다.
        """
        if is_reasoning_model(self.model_name):   # gpt-6-luna 같은 추론 모델: max_tokens·temperature 를 받지 않고, 도구 호출은 추론 강도 none 이어야 한다
            args = {"model": self.model_name, "max_completion_tokens": self.max_tokens, "reasoning_effort": self.settings.ai_reasoning_effort}
        else:
            args = {"model": self.model_name, "temperature": self.temperature, "max_tokens": self.max_tokens}
        if self.json_mode and not kwargs.get("tools"):
            args["response_format"] = {"type": "json_object"}
        for key in ("tools", "tool_choice", "parallel_tool_calls"):
            if key in kwargs:
                args[key] = kwargs[key]
        args["messages"] = [sdk_message(message) for message in messages]
        resp = openai_client.make_client(self.settings).chat.completions.create(**args)
        tin = int(getattr(resp.usage, "prompt_tokens", 0) or 0)
        tout = int(getattr(resp.usage, "completion_tokens", 0) or 0)
        choice = resp.choices[0]
        reply = choice.message
        reason = getattr(choice, "finish_reason", None)
        metadata = {"finish_reason": reason} if isinstance(reason, str) and reason in {"stop", "length", "tool_calls", "content_filter", "function_call"} else {}
        calls, invalid = sdk_tool_calls(reply)
        message = AIMessage(content=reply.content or "", tool_calls=calls, invalid_tool_calls=invalid,
                            usage_metadata={"input_tokens": tin, "output_tokens": tout, "total_tokens": tin + tout},
                            response_metadata=metadata)
        return ChatResult(generations=[ChatGeneration(message=message)])


_REASONING_MODEL = re.compile(r"^(gpt-5|gpt-6|o\d)")


def is_reasoning_model(name: str) -> bool:
    """chat.completions 에서 max_tokens·temperature 대신 max_completion_tokens·reasoning_effort 를 쓰는 추론 모델 이름인지."""
    return bool(_REASONING_MODEL.match(str(name or "")))


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


def ask_tools(settings: Settings, messages: list[BaseMessage], tools: list[dict[str, Any]], *,
              max_tokens: int = 1500, temperature: float = 0) -> AIMessage:
    """관찰 결과를 포함한 대화에서 native function 호출 한 단계를 요청한다.

    Args:
        settings: 제한된 timeout·재시도 설정.
        messages: 시스템 지시·신뢰하지 않는 사용자 문맥·실제 Tool 결과.
        tools: 서버가 제공한 허용 function schema 목록.
        max_tokens: 해당 모델 호출의 응답 토큰 상한.
        temperature: 생성 온도.
    Returns:
        native tool_calls·invalid_tool_calls·사용량을 포함한 AIMessage.
    Raises:
        SDK 오류 또는 ValueError: 호출·메시지 계약 오류.
    Side Effects:
        기존 SDK 전송 경로로 한 번 호출한다. 도구 실행은 수행하지 않는다.
    """
    model = chat_model(settings, max_tokens=max_tokens, temperature=temperature, json_mode=False)
    return model.bind_tools(tools, tool_choice="auto", parallel_tool_calls=False).invoke(messages)
