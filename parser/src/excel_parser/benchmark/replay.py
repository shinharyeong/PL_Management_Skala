"""원본 요청 맥락을 내보내고 고정한 구조 계획만 재생하는 오프라인 경계.

마스터·정답·룰 결과는 이 모듈의 입력이 아니다. 숫자 추출·정규화·계획 검증은
기존 AI 구조 엔진이 수행하며 이 클라이언트에는 실제 전송 함수가 없다.
"""

from __future__ import annotations

import copy
import hashlib
import json
import threading
from contextlib import ExitStack
from pathlib import Path
from time import perf_counter
from typing import Any
from unittest.mock import patch

from excel_parser_ai.client import ModelError, PROMPT_VERSION


def request_description(
    task: str, instructions: str, context: dict, schema: dict, *,
    model: str = "codex-session-plan", effort: str = "high",
    max_output_tokens: int = 32768,
) -> dict:
    """기존 ResponsesClient와 동일한 요청 본문 해시와 검토용 필드를 만든다.

    Args:
        task: 기존 엔진의 요청 작업 이름.
        instructions: 엔진이 모델에 전달할 시스템 지시문.
        context: 조직 원본에서만 추출한 요청 맥락.
        schema: 기존 엔진의 엄격한 응답 JSON 스키마.
        model: 재생 조건에 기록할 모델 식별자.
        effort: 요청 해시에 포함할 추론 강도.
        max_output_tokens: 요청 해시에 포함할 출력 토큰 상한.
    Returns:
        request_hash, 원본 지시문·맥락·스키마, 요청 설정을 가진 사전.
    Raises:
        TypeError: 요청을 JSON으로 직렬화할 수 없는 경우.
    Side Effects:
        없음. 입력을 수정하거나 파일·네트워크에 접근하지 않는다.
    """
    body = {
        "model": model, "store": False,
        "reasoning": {"effort": effort},
        "max_output_tokens": max_output_tokens,
        "input": [
            {"role": "system", "content": instructions},
            {"role": "user", "content": json.dumps(
                context, ensure_ascii=False, separators=(",", ":"),
            )},
        ],
        "text": {"format": {
            "type": "json_schema", "name": task, "schema": schema, "strict": True,
        }},
    }
    digest = hashlib.sha256(json.dumps(
        {"version": PROMPT_VERSION, "body": body},
        sort_keys=True, ensure_ascii=False,
    ).encode()).hexdigest()
    return {
        "request_hash": digest, "prompt_version": PROMPT_VERSION,
        "task": task, "instructions": instructions,
        "context": copy.deepcopy(context), "schema": copy.deepcopy(schema),
        "request_settings": {
            "model": model, "effort": effort,
            "max_output_tokens": max_output_tokens, "store": False,
        },
    }


def collect_initial_requests(
    workbook: dict, *, model: str = "codex-session-plan", effort: str = "high",
    max_output_tokens: int = 32768,
) -> dict:
    """기존 시트 선택·분할 함수로 응답 전 최초 요청 맥락을 모두 수집한다.

    Args:
        workbook: 조직 원본에 대한 기존 extract_workbook 반환값.
        model: 재생 클라이언트와 동일하게 사용할 모델 식별자.
        effort: 재생 클라이언트와 동일한 추론 강도.
        max_output_tokens: 재생 클라이언트와 동일한 출력 토큰 상한.
    Returns:
        requests 목록과 skipped_sheets 목록. 응답에 종속되는 repair·review·
        exception 요청은 최초 목록에 없으며 실제 ask 시 별도로 기록된다.
    Raises:
        KeyError: 추출 객체가 기존 계약을 만족하지 않는 경우.
        TypeError: 맥락을 JSON으로 직렬화할 수 없는 경우.
    Side Effects:
        없음. 파일·네트워크 접근과 구조 계획 생성·적용을 수행하지 않는다.
    """
    from excel_parser_ai.structured import (
        BLOCK_NOTE, FORMULA_SKIP_RATIO, LARGE_SHEET_ROW_THRESHOLD,
        PLAN_RESPONSE, PROMPT, _formula_ratio, _header_row_range, _row_blocks,
        structure_context,
    )

    requests = []
    skipped = []
    for sheet in workbook["sheets"]:
        candidates = [
            cell for cell in sheet["cells"]
            if cell.get("has_content", True)
            and (cell["data_type"] == "number" or cell.get("formula"))
        ]
        if not candidates:
            skipped.append({
                "sheet": sheet["name"], "reason": "no_numeric_or_formula_candidates",
            })
            continue
        ratio = _formula_ratio(sheet)
        if ratio >= FORMULA_SKIP_RATIO:
            skipped.append({
                "sheet": sheet["name"], "reason": "formula_ratio_policy",
                "formula_ratio": ratio, "threshold": FORMULA_SKIP_RATIO,
            })
            continue
        data_rows = sorted({cell["row"] for cell in candidates})
        if len(data_rows) > LARGE_SHEET_ROW_THRESHOLD:
            header_range = _header_row_range(data_rows)
            for index, (lo, hi) in enumerate(_row_blocks(data_rows)):
                context = structure_context(
                    workbook, sheet, row_range=(lo, hi),
                    header_row_range=header_range,
                )
                prompt = PROMPT + BLOCK_NOTE.format(
                    lo=lo, hi=hi, header_lo=header_range[0],
                    header_hi=header_range[1],
                )
                requests.append(request_description(
                    f"sheet_structure_v1_block{index}", prompt, context,
                    PLAN_RESPONSE, model=model, effort=effort,
                    max_output_tokens=max_output_tokens,
                ))
        else:
            requests.append(request_description(
                "sheet_structure_v1", PROMPT, structure_context(workbook, sheet),
                PLAN_RESPONSE, model=model, effort=effort,
                max_output_tokens=max_output_tokens,
            ))
    return {"requests": requests, "skipped_sheets": skipped}


class OfflineReplayClient:
    """요청 해시별 고정 응답을 반환하며 실제 전송 경로가 없는 클라이언트.

    plans는 읽기만 하고 response_cache·events·requests는 메모리에서 갱신한다.
    생성 시 파일을 읽거나 쓰지 않는다. 스키마·근거 검증은 기존 엔진에 맡긴다.
    """

    def __init__(
        self, plans: dict[str, dict], *, requests: dict | None = None,
        model: str = "codex-session-plan", effort: str = "high",
        max_output_tokens: int = 32768, source_id: str | None = None,
    ) -> None:
        """고정 계획 사전과 메모리 관측 상태를 설정한다.

        Args:
            plans: 정확한 request_hash에서 기존 JSON 응답으로의 사전.
            requests: 실제 요청 내역을 누적할 선택 사전.
            model: 계획 수집 때 사용한 요청 모델 식별자.
            effort: 계획 수집 때 사용한 추론 강도.
            max_output_tokens: 계획 수집 때 사용한 출력 토큰 상한.
            source_id: 요청 발생 원본을 기록할 식별자. 파일마다 변경 가능하다.
        Returns:
            없음.
        Raises:
            TypeError: plans 또는 requests가 사전이 아닌 경우.
        Side Effects:
            객체 상태만 초기화한다. 환경변수·파일·네트워크에 접근하지 않는다.
        """
        if not isinstance(plans, dict) or (requests is not None and not isinstance(requests, dict)):
            raise TypeError("plans와 requests는 사전이어야 합니다")
        self.plans = plans
        self.requests = {} if requests is None else requests
        self.model = model
        self.effort = effort
        self.max_output_tokens = max_output_tokens
        self.source_id = source_id
        self.timeout = None
        self.max_calls = float("inf")
        self.cache_dir = Path("__unused_offline_memory_cache__")
        self.response_cache: dict[str, dict] = {}
        self.events: list[dict] = []
        self.call_count = 0
        self.http_post_attempts = 0
        self.lock = threading.Lock()
        self.offline_replay = True

    def _notify(self, message: str) -> None:
        """기존 엔진의 진행 알림 인터페이스를 부작용 없이 수용한다.

        Args:
            message: 기존 엔진의 진행 메시지.
        Returns:
            없음.
        Raises:
            없음.
        Side Effects:
            없음. 메시지를 출력하거나 저장하지 않는다.
        """

    def ask(
        self, task: str, instructions: str, context: dict, schema: dict, *,
        refresh: bool = False, max_connection_attempts: int | None = None,
    ) -> dict:
        """동일 요청에 대응하는 고정 계획만 반환하고 누락은 실패로 남긴다.

        Args:
            task: 기존 엔진의 작업 이름. repair·review도 별도 해시를 가진다.
            instructions: 기존 엔진이 실제 전달한 시스템 지시문.
            context: 기존 엔진이 실제 전달한 조직 원본 맥락.
            schema: 기존 엔진의 응답 스키마.
            refresh: 인터페이스 호환 인자. 고정 계획은 변경하지 않는다.
            max_connection_attempts: 인터페이스 호환 인자. 통신은 수행하지 않는다.
        Returns:
            해당 요청의 고정 JSON 응답 복사본. 기존 검증·적용 경로로 반환된다.
        Raises:
            ModelError: 해당 해시의 고정 계획이 없거나 응답이 사전이 아닌 경우.
            TypeError: 요청 맥락을 JSON으로 직렬화할 수 없는 경우.
        Side Effects:
            메모리의 요청·이벤트·카운터·응답 캐시만 갱신한다.
            외부 호출, 파일 접근, 룰 파서 대체 및 임의 계획 생성은 하지 않는다.
        """
        started = perf_counter()
        request = request_description(
            task, instructions, context, schema, model=self.model,
            effort=self.effort, max_output_tokens=self.max_output_tokens,
        )
        digest = request["request_hash"]
        with self.lock:
            entry = self.requests.setdefault(digest, request)
            entry.setdefault("occurrences", []).append({
                "source_id": self.source_id, "task": task,
            })
            cached = digest in self.response_cache
            response = self.response_cache.get(digest, self.plans.get(digest))
            if not cached:
                self.call_count += 1
            failed = not isinstance(response, dict)
            event = {
                "task": task, "cached": cached, "failed": failed,
                "model": self.model, "request_hash": digest,
                "source_id": self.source_id, "offline_replay": True,
                "api_calls": 0, "usage": {},
                "seconds": perf_counter() - started,
            }
            if failed:
                event["error_type"] = "MissingOfflinePlan"
                self.events.append(event)
                raise ModelError(
                    f"고정 구조 계획 누락: {task}, request_hash={digest}; "
                    "외부 API 및 룰 기반 대체는 차단되어 있습니다."
                )
            self.response_cache[digest] = copy.deepcopy(response)
            self.events.append(event)
            return copy.deepcopy(response)


class NetworkBlocker:
    """범위 내 Python HTTP·소켓 연결을 차단하고 시도 수를 관측한다.

    정상 오프라인 경로의 blocked_attempts는 빈 목록이며 실제 외부 호출은 없다.
    전역 전송 경계를 잠시 교체하므로 다른 네트워크 작업과 병행하지 않는다.
    """

    def __init__(self) -> None:
        """연결 차단기와 관측 목록을 초기화한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            없음.
        Side Effects:
            메모리 상태만 생성한다. 아직 전송 경계를 바꾸지 않는다.
        """
        self.blocked_attempts: list[dict] = []
        self._stack = ExitStack()

    def _deny(self, *args: Any, **kwargs: Any) -> None:
        """전송 전에 시도를 기록하고 연결을 거부한다.

        Args:
            args: 원래 전송 함수의 위치 인자. 민감 정보 방지를 위해 보관하지 않는다.
            kwargs: 원래 전송 함수의 키워드 인자. 보관하지 않는다.
        Returns:
            없음. 항상 예외가 발생한다.
        Raises:
            RuntimeError: 모든 외부 연결 시도.
        Side Effects:
            차단 건수를 메모리에 추가한다. 외부로 데이터를 전송하지 않는다.
        """
        self.blocked_attempts.append({"reason": "offline_network_block"})
        raise RuntimeError("오프라인 검증 중 외부 연결이 차단되었습니다")

    def __enter__(self) -> NetworkBlocker:
        """HTTP와 소켓의 실제 연결 경계를 차단한다.

        Args:
            없음.
        Returns:
            관측 목록을 가진 현재 차단기.
        Raises:
            AttributeError: 표준 라이브러리의 연결 인터페이스가 없는 경우.
        Side Effects:
            Python 프로세스의 연결 함수들을 범위 종료까지 임시 교체한다.
        """
        for target in (
            "urllib.request.urlopen", "socket.create_connection",
            "socket.socket.connect", "socket.socket.connect_ex",
        ):
            self._stack.enter_context(patch(target, side_effect=self._deny))
        return self

    def __exit__(self, exc_type: Any, exc: Any, traceback: Any) -> bool:
        """임시 차단을 복원하고 본문 예외를 숨기지 않는다.

        Args:
            exc_type: 본문 예외 종류 또는 None.
            exc: 본문 예외 또는 None.
            traceback: 본문 traceback 또는 None.
        Returns:
            False. 본문 예외는 호출자에게 전파된다.
        Raises:
            없음.
        Side Effects:
            원래 HTTP·소켓 함수를 복원한다. 파일이나 네트워크에 접근하지 않는다.
        """
        self._stack.__exit__(exc_type, exc, traceback)
        return False
