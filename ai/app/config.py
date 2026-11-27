"""설정. 값은 ai/.env → 셸 환경변수 → 기본값 순으로 읽는다(.env 가 셸 변수보다 우선).

키 값은 어떤 로그·출력에도 남기지 않고, 어디서 읽었는지(출처)만 기록한다.
상대 경로는 저장소 루트 기준으로 해석한다(실행 위치와 무관).
"""
from __future__ import annotations

import contextlib
import contextvars
import dataclasses
import logging
import os
from dataclasses import dataclass, field
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from dotenv import dotenv_values

AI_DIR = Path(__file__).resolve().parents[1]          # ai/
REPO_ROOT = AI_DIR.parent                              # 저장소 루트
logger = logging.getLogger("ai.config")


class ConfigError(RuntimeError):
    """필수 설정이 없을 때. 무엇을 설정해야 하는지 메시지에 적는다."""


def _env_file() -> Path:
    return Path(os.environ.get("AI_ENV_FILE") or (AI_DIR / ".env"))


def _read(name: str, default: str | None = None) -> tuple[str | None, str]:
    """(값, 출처). 출처: 'ai/.env' | 'env' | 'default'."""
    f = _env_file()
    if f.exists():
        v = dotenv_values(f).get(name)
        if v is not None and str(v).strip() != "":
            return str(v).strip(), "ai/.env"
    v = os.environ.get(name)
    if v is not None and v.strip() != "":
        return v.strip(), "env"
    return default, "default"


def _path(value: str | None) -> Path | None:
    if not value:
        return None
    p = Path(value).expanduser()
    return p if p.is_absolute() else (REPO_ROOT / p)


def _daily_call_limit() -> int:
    """선택한 만료일이 지난 경우 한국 시간 기준으로 복구 호출 한도를 반환한다.

    Args:
        없음. 환경 설정의 만료일은 YYYY-MM-DD 형식이며 해당 날짜까지 포함한다.
    Returns:
        현재 적용할 일일 호출 한도. 만료일이 없으면 기존 한도를 그대로 사용한다.
    Raises:
        ValueError: 적용할 한도가 정수가 아니거나 만료일이 올바르지 않은 경우.
    Side Effects:
        설정 파일과 현재 시각을 읽는다. 파일 생성·설정 변경·결과 캐시는 없다.
    """
    limit = int(_read("AI_DAILY_CALL_LIMIT", "30")[0])
    expires_on = _read("AI_DAILY_CALL_LIMIT_EXPIRES_ON")[0]
    if expires_on and datetime.now(ZoneInfo("Asia/Seoul")).date() > date.fromisoformat(expires_on):
        return int(_read("AI_DAILY_CALL_LIMIT_AFTER_EXPIRY", "30")[0])
    return limit


@dataclass
class Settings:
    openai_api_key: str | None = field(default=None, repr=False)   # 값은 repr·로그에 나오지 않음
    report_service_token: str | None = field(default=None, repr=False)   # backend 와 주고받는 내부 토큰(REPORT_AI_TOKEN, 헤더 X-Internal-Token). 비우면 검사 안 함(로컬 개발)
    backend_url: str | None = None              # PDF 분석 결과를 보고할 backend 주소(AI_BACKEND_URL, 예 http://backend:8080)
    require_internal_token: bool = False        # AI_REQUIRE_INTERNAL_TOKEN. true(운영)면 REPORT_AI_TOKEN 이 없을 때 계약 API 거부·결과 보고 안 함
    key_source: str = "none"
    openai_model: str = "gpt-4o-mini"
    # GPT-6 Responses 전용. 기존 Chat Completions의 AI_REASONING_EFFORT와 독립적으로 적용한다.
    openai_reasoning_effort: str = "low"
    vlm_model: str = ""                          # 그림 읽기(VLM) 전용 모델. 비우면 openai_model
    # GPT-5·o 계열 Chat Completions의 기존 설정. 값 지원 여부는 선택한 제공자 모델이 결정한다.
    ai_reasoning_effort: str = "none"
    ai_temperature: float = 0.2
    ai_max_output_tokens: int = 500
    ai_timeout_seconds: float = 30.0
    ai_max_retries: int = 0
    ai_daily_call_limit: int = 30
    ai_review_max_rounds: int = 2               # PDF 박스 확인 루프의 재질문 상한(0 이면 루프를 끈다)
    # 아래 경로 값은 기존 호출부의 메모리 캐시·사용량 그룹 식별자이며 파일을 만들지 않는다.
    ai_cache_dir: Path = REPO_ROOT / "ai" / ".cache"
    output_dir: Path = REPO_ROOT / "ai" / "output"
    log_dir: Path | None = None                 # 메모리 사용량 집계 그룹(비우면 output_dir)
    # 챗봇(AI 작업실)
    chat_database_url: str | None = None        # 조회용 PostgreSQL 접속 주소(AI_DATABASE_URL). 비우면 챗봇·값 채우기는 'DB 없음'으로 답한다
    chat_schema: str = "report"                     # 조회 테이블이 든 스키마(AI_DB_SCHEMA). 연결의 search_path 가 이 스키마로 고정된다
    chat_row_limit: int = 1000                  # 조회 결과 행 상한
    chat_sql_timeout_seconds: float = 20.0      # SQL 실행 시간 상한
    chat_max_fix: int = 2                       # SQL 자기 수정 최대 횟수
    chat_sql_max_tokens: int = 700              # SQL 생성 출력 토큰 상한
    chat_text_max_tokens: int = 300             # 설명 문장 출력 토큰 상한
    chat_memory: bool = True                    # 대화 기억(서버 메모리에만, 최근 6턴·2시간). off 면 이전 대화를 전혀 쓰지 않음
    chat_test_log: bool = False                 # 질의별 비용 측정 지표를 stdout 로그에 남김(질문 원문·키는 남기지 않음)
    pdf_dev: bool = False                       # PDF 구조 추출 데모 엔드포인트(/v1/dev/pdf-structure). 켜야만 열린다

    @property
    def key_present(self) -> bool:
        return bool(self.openai_api_key)



_OVERRIDE: contextvars.ContextVar[Settings | None] = contextvars.ContextVar("ai_settings_override", default=None)


@contextlib.contextmanager
def use_settings(**changes):
    """요청 한 건 동안만 설정(경로 등)을 바꾼다."""
    s = dataclasses.replace(get_settings(), **changes)
    token = _OVERRIDE.set(s)
    try:
        yield s
    finally:
        _OVERRIDE.reset(token)


def get_settings() -> Settings:
    """요청별 재정의 또는 환경 설정으로 AI 실행 설정을 구성한다.

    Args:
        없음.
    Returns:
        AI 서비스 Settings. 보고서 스키마 기본값은 report이며 호출 한도 만료는 매번 재평가한다.
    Raises:
        ValueError: 숫자 환경 설정 또는 호출 한도 만료일이 올바르지 않은 경우.
    Side Effects:
        설정 파일이 있으면 읽는다. 경로 값 조회로 파일·폴더를 생성하지 않는다.
    """
    ov = _OVERRIDE.get()
    if ov is not None:
        return ov
    key, key_src = _read("OPENAI_API_KEY")
    s = Settings(
        openai_api_key=key, key_source=key_src if key else "none",
        report_service_token=_read("REPORT_AI_TOKEN")[0],
        backend_url=_read("AI_BACKEND_URL")[0],
        require_internal_token=str(_read("AI_REQUIRE_INTERNAL_TOKEN", "false")[0]).strip().lower() in ("1", "true", "yes", "on"),
        openai_model=_read("OPENAI_MODEL", "gpt-4o-mini")[0],
        openai_reasoning_effort=_read("OPENAI_REASONING_EFFORT", "low")[0],
        vlm_model=_read("AI_VLM_MODEL", "")[0],
        ai_reasoning_effort=str(_read("AI_REASONING_EFFORT", "none")[0]).strip() or "none",
        ai_temperature=float(_read("AI_TEMPERATURE", "0.2")[0]),
        ai_max_output_tokens=int(_read("AI_MAX_OUTPUT_TOKENS", "500")[0]),
        ai_timeout_seconds=float(_read("AI_TIMEOUT_SECONDS", "30")[0]),
        ai_max_retries=int(_read("AI_MAX_RETRIES", "0")[0]),
        ai_daily_call_limit=_daily_call_limit(),
        ai_review_max_rounds=max(0, min(5, int(_read("AI_REVIEW_MAX_ROUNDS", "2")[0]))),
        ai_cache_dir=_path(_read("AI_CACHE_DIR", "ai/.cache")[0]),
        output_dir=_path(_read("OUTPUT_DIR", "ai/output")[0]),
        log_dir=_path(_read("AI_LOG_DIR")[0]),
        chat_database_url=_read("AI_DATABASE_URL")[0], chat_schema=_read("AI_DB_SCHEMA", "report")[0], chat_row_limit=int(_read("CHAT_ROW_LIMIT", "1000")[0]),
        chat_sql_timeout_seconds=float(_read("CHAT_SQL_TIMEOUT_SECONDS", "20")[0]), chat_max_fix=int(_read("CHAT_MAX_FIX", "2")[0]),
        chat_memory=str(_read("CHAT_MEMORY", "true")[0]).strip().lower() not in ("0", "false", "no", "off"),
        chat_test_log=str(_read("CHAT_TEST_LOG", "0")[0]).strip().lower() in ("1", "true", "yes", "on"),
        pdf_dev=str(_read("AI_PDF_DEV", "0")[0]).strip().lower() in ("1", "true", "yes", "on"),
    )
    return s


def log_key_source(s: Settings) -> None:
    """키를 어느 쪽에서 읽었는지만 기록(값은 기록하지 않음)."""
    logger.info("OPENAI_API_KEY source=%s present=%s", s.key_source, s.key_present)
