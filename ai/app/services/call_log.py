"""AI 사용량을 stdout 로깅으로 내보내고 프로세스 메모리에서 일일 횟수를 센다.

원문·응답·키는 기록하지 않는다. 집계는 프로세스 재시작 시 초기화되므로 운영의
전역 비용 한도는 외부 사용량 정책과 함께 관리해야 한다.
"""
from __future__ import annotations

import json
import logging
from collections import Counter, deque
from datetime import datetime
from threading import RLock
from zoneinfo import ZoneInfo

_lock = RLock()
_events: deque[dict] = deque(maxlen=1000)
_counts: Counter[tuple[str, str]] = Counter()
_logger = logging.getLogger("ai.usage")
_allowed = frozenset({"task", "status", "model", "prompt_version", "facts_hash", "outcome",
                      "input_tokens", "output_tokens", "error"})


class CallLog:
    """기존 호출부와 호환되는 메모리 사용량 계수기. 경로 인자는 격리 키로만 쓴다."""

    def __init__(self, output_dir: object = "default") -> None:
        """파일에 접근하지 않고 집계 그룹만 지정한다.

        Args:
            output_dir: 기존 호출부의 집계 구분값. 파일·디렉터리를 만들지 않는다.
        Returns:
            없음.
        Raises:
            없음.
        Side Effects:
            없음.
        """
        self.namespace = str(output_dir)

    def load(self) -> dict:
        """최근 사용량 이벤트 사본을 조회한다.

        Args:
            없음.
        Returns:
            현재 그룹의 최근 이벤트를 담은 calls 목록.
        Raises:
            없음.
        Side Effects:
            없음. 파일 읽기도 수행하지 않는다.
        """
        with _lock:
            return {"calls": [{k: v for k, v in event.items() if k != "namespace"}
                              for event in _events if event["namespace"] == self.namespace]}

    def calls_today(self, today: str | None = None) -> int:
        """한국 날짜 기준 실제 호출의 성공·실패 횟수를 조회한다.

        Args:
            today: 검증할 YYYY-MM-DD. 없으면 현재 한국 날짜.
        Returns:
            캐시 적중·호출 생략을 제외한 현재 프로세스의 호출 수.
        Raises:
            없음.
        Side Effects:
            없음.
        """
        day = today or datetime.now(ZoneInfo("Asia/Seoul")).strftime("%Y-%m-%d")
        with _lock:
            return _counts[(self.namespace, day)]

    def append(self, entry: dict) -> None:
        """허용된 사용량 필드만 로깅하고 메모리 집계에 반영한다.

        Args:
            entry: 호출 종류·결과·토큰 수 등의 메타데이터. 허용하지 않은 필드는 버린다.
        Returns:
            없음.
        Raises:
            없음.
        Side Effects:
            메모리 집계를 변경하고 표준 logging에 이벤트를 보낸다. 파일은 쓰지 않는다.
        """
        now = datetime.now(ZoneInfo("Asia/Seoul"))
        day = now.strftime("%Y-%m-%d")
        event = {"at": now.isoformat(timespec="seconds"), "date": day,
                 **{k: v for k, v in entry.items() if k in _allowed}}
        with _lock:
            for key in list(_counts):
                if key[1] != day:
                    del _counts[key]
            if event.get("outcome") in {"success", "error"}:
                _counts[(self.namespace, day)] += 1
            _events.append({**event, "namespace": self.namespace})
        _logger.info("ai_call %s", json.dumps(event, ensure_ascii=False))

    @staticmethod
    def clear() -> None:
        """테스트 격리를 위해 모든 프로세스 내 사용량을 초기화한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            없음.
        Side Effects:
            프로세스 메모리의 이벤트와 집계만 비운다.
        """
        with _lock:
            _events.clear()
            _counts.clear()
