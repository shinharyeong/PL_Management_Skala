"""Luna의 Responses 전송을 기존 LangChain 메시지·도구 계약에 연결한다."""
from __future__ import annotations

import json
from copy import deepcopy
from typing import Any

from langchain_core.messages import AIMessage, BaseMessage, ToolMessage


def input_content(content: Any) -> Any:
    """기존 텍스트·이미지 입력을 Responses 입력으로 변환한다.

    Args: content LangChain 문자열 또는 text/image_url 블록 목록.
    Returns: 문자열 또는 input_text/input_image 블록 목록.
    Raises: ValueError: 지원하지 않는 콘텐츠 블록.
    Side Effects: 없음. 원본을 변경하거나 파일·URL을 직접 읽지 않는다.
    """
    if isinstance(content, str):
        return content
    converted = []
    for block in content:
        if isinstance(block, str):
            converted.append({"type": "input_text", "text": block})
        elif block.get("type") == "text":
            converted.append({"type": "input_text", "text": block["text"]})
        elif block.get("type") == "image_url":
            image = block["image_url"]
            converted.append({"type": "input_image", "image_url": image["url"],
                              "detail": image.get("detail", "auto")})
        else:
            raise ValueError("지원하지 않는 Responses 입력 블록입니다.")
    return converted


def response_input(messages: list[BaseMessage]) -> list[dict[str, Any]]:
    """메모리의 대화·함수 호출·도구 결과를 Responses 입력 항목으로 연결한다.

    Args: messages 현재 실행의 LangChain 메시지 목록.
    Returns: call_id와 암호화된 추론 상태를 보존한 입력 목록.
    Raises: ValueError: 지원하지 않는 메시지 역할 또는 콘텐츠.
    Side Effects: 없음. 제공자 저장소·파일에 대화나 추론 원문을 저장하지 않는다.
    """
    result = []
    for message in messages:
        if isinstance(message, ToolMessage):
            result.append({"type": "function_call_output", "call_id": message.tool_call_id,
                           "output": message.content})
            continue
        if isinstance(message, AIMessage):
            items = message.additional_kwargs.get("responses_items")
            if items is not None:
                result.extend(deepcopy(items))
                continue
            if message.content:
                result.append({"role": "assistant", "content": input_content(message.content)})
            for call in message.tool_calls:
                result.append({"type": "function_call", "call_id": call["id"], "name": call["name"],
                               "arguments": json.dumps(call["args"], ensure_ascii=False, separators=(",", ":"))})
            for call in message.invalid_tool_calls:
                result.append({"type": "function_call", "call_id": call["id"], "name": call["name"],
                               "arguments": call.get("args") or "{}"})
            continue
        role = {"system": "system", "human": "user"}.get(message.type)
        if role is None:
            raise ValueError("지원하지 않는 Responses 메시지 역할입니다.")
        result.append({"role": role, "content": input_content(message.content)})
    return result


def response_tools(tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """등록된 함수 도구만 Responses 형식으로 변환한다.

    Args: tools 기존 Chat Completions function 도구 정의.
    Returns: 동일한 이름·인자·strict 정책의 Responses 함수 정의.
    Raises: ValueError: 함수 외 도구가 전달된 경우.
    Side Effects: 없음. 제공자 내장 도구나 새 접근 권한을 추가하지 않는다.
    """
    converted = []
    for tool in tools:
        if tool.get("type") != "function":
            raise ValueError("등록된 함수 도구만 사용할 수 있습니다.")
        function = deepcopy(tool["function"])
        function.setdefault("strict", False)
        converted.append({"type": "function", **function})
    return converted


def response_message(response: Any) -> AIMessage:
    """Responses 출력에서 공개 답변·함수·사용량과 메모리용 추론 상태를 분리한다.

    Args: response 공식 SDK Response 객체.
    Returns: 기존 그래프와 호환되는 AIMessage. 출력 잘림은 finish_reason=length로 보존한다.
    Raises: ValueError: 실패·거절 응답, 도구 인자 이외의 지원하지 않는 출력.
    Side Effects: 없음. 추론 원문을 출력·로깅하지 않으며 암호화 상태는 실행 메모리에만 둔다.
    """
    status = getattr(response, "status", None)
    incomplete = getattr(response, "incomplete_details", None)
    reason = getattr(incomplete, "reason", None)
    if status not in {"completed", "incomplete"}:
        raise ValueError("모델 응답이 완료되지 않았습니다.")
    if status == "incomplete" and reason != "max_output_tokens":
        raise ValueError("모델 응답을 사용할 수 없습니다.")
    calls, invalid, text, items = [], [], [], []
    for output in response.output:
        value = output.model_dump(exclude_none=True)
        kind = value.get("type")
        if kind == "reasoning":
            encrypted = value.get("encrypted_content")
            if encrypted:
                items.append({"type": "reasoning", "id": value["id"], "summary": [],
                              "encrypted_content": encrypted})
        elif kind == "function_call":
            identifier, name, arguments = value["call_id"], value["name"], value["arguments"]
            items.append({key: value[key] for key in ("type", "id", "call_id", "name", "arguments") if key in value})
            try:
                parsed = json.loads(arguments)
                if not isinstance(parsed, dict):
                    raise ValueError("도구 인자는 객체여야 합니다.")
                calls.append({"id": identifier, "name": name, "args": parsed, "type": "tool_call"})
            except (ValueError, TypeError):
                invalid.append({"id": identifier, "name": name, "args": arguments,
                                "type": "invalid_tool_call", "error": "도구 인자 JSON 계약 오류"})
        elif kind == "message":
            for block in value.get("content", []):
                if block.get("type") == "refusal":
                    raise ValueError("모델이 답변을 제공하지 않았습니다.")
                if block.get("type") == "output_text":
                    text.append(block["text"])
            items.append({"role": "assistant", "content": "".join(
                block["text"] for block in value.get("content", []) if block.get("type") == "output_text")})
        else:
            raise ValueError("등록하지 않은 모델 출력 종류입니다.")
    usage = response.usage
    tin, tout = int(usage.input_tokens), int(usage.output_tokens)
    details = getattr(usage, "output_tokens_details", None)
    reasoning = int(getattr(details, "reasoning_tokens", 0) or 0)
    metadata = {"finish_reason": "length" if status == "incomplete" else "tool_calls" if calls else "stop",
                "reasoning_tokens": reasoning}
    return AIMessage(content="".join(text), tool_calls=calls, invalid_tool_calls=invalid,
                     additional_kwargs={"responses_items": items}, response_metadata=metadata,
                     usage_metadata={"input_tokens": tin, "output_tokens": tout, "total_tokens": tin + tout})


def generate_response(client: Any, model: str, messages: list[BaseMessage], *, max_tokens: int,
                      reasoning_effort: str, json_mode: bool, options: dict[str, Any]) -> AIMessage:
    """Luna에 저장 없는 Responses 요청 한 건을 보내고 기존 메시지로 반환한다.

    Args: client 공식 SDK, model 모델 ID, messages 현재 대화, max_tokens 추론 포함 출력 한도,
        reasoning_effort 추론 수준, json_mode JSON 응답 여부, options 등록 함수 및 선택 정책.
    Returns: 함수 호출·출력 잘림·토큰 사용량이 보존된 AIMessage.
    Raises: SDK 오류 또는 ValueError: 제공자 실패·지원하지 않는 입력/출력.
    Side Effects: 외부 모델 호출 한 번. store=false이며 원문·추론·키를 로깅하지 않는다.
    """
    arguments = {"model": model, "input": response_input(messages), "max_output_tokens": max_tokens,
                 "reasoning": {"effort": reasoning_effort}, "store": False,
                 "include": ["reasoning.encrypted_content"]}
    if options.get("tools"):
        arguments["tools"] = response_tools(options["tools"])
        choice = options.get("tool_choice", "auto")
        arguments["tool_choice"] = ({"type": "function", "name": choice["function"]["name"]}
                                    if isinstance(choice, dict) else choice)
        arguments["parallel_tool_calls"] = options.get("parallel_tool_calls", False)
    elif json_mode:
        arguments["text"] = {"format": {"type": "json_object"}}
    return response_message(client.responses.create(**arguments))
