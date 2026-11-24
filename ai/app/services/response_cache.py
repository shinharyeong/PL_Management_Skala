"""AI 응답의 제한된 프로세스 메모리 캐시. 업무 결과와 캐시를 파일로 저장하지 않는다."""
from __future__ import annotations

from collections import OrderedDict
from copy import deepcopy
from threading import RLock
from time import monotonic
from typing import Any

MAX_ENTRIES = 256
TTL_SECONDS = 3600
_entries: OrderedDict[str, tuple[float, Any]] = OrderedDict()
_lock = RLock()


def get(key: object) -> Any | None:
    """아직 유효한 응답 사본을 메모리에서 조회한다.

    Args:
        key: 호출 기능·모델·입력 해시로 구분한 키. 파일 경로처럼 보여도 접근하지 않는다.
    Returns:
        유효한 응답의 깊은 사본 또는 캐시가 없거나 만료된 경우 None.
    Raises:
        없음.
    Side Effects:
        만료 항목을 지우고 조회 순서를 갱신한다. 파일·DB·네트워크 접근은 없다.
    """
    identity = str(key)
    with _lock:
        found = _entries.get(identity)
        if found is None:
            return None
        expires, value = found
        if expires <= monotonic():
            del _entries[identity]
            return None
        _entries.move_to_end(identity)
        return deepcopy(value)


def put(key: object, value: Any) -> None:
    """응답 사본을 유효기간과 최대 개수 제한이 있는 메모리에 저장한다.

    Args:
        key: 호출 기능·모델·입력 해시로 구분한 키.
        value: 호출자가 다시 사용할 JSON 형태의 응답.
    Returns:
        없음.
    Raises:
        없음.
    Side Effects:
        프로세스 메모리만 변경한다. 재시작 시 캐시는 없어지고 파일·DB는 쓰지 않는다.
    """
    identity = str(key)
    with _lock:
        _entries[identity] = (monotonic() + TTL_SECONDS, deepcopy(value))
        _entries.move_to_end(identity)
        while len(_entries) > MAX_ENTRIES:
            _entries.popitem(last=False)


def clear() -> None:
    """테스트 격리나 프로세스 내 캐시 초기화를 위해 모든 응답을 비운다.

    Args:
        없음.
    Returns:
        없음.
    Raises:
        없음.
    Side Effects:
        프로세스 메모리 캐시만 비운다.
    """
    with _lock:
        _entries.clear()
