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
from pathlib import Path

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


@dataclass
class Settings:
    openai_api_key: str | None = field(default=None, repr=False)   # 값은 repr·로그에 나오지 않음
    report_service_token: str | None = field(default=None, repr=False)
    key_source: str = "none"
    openai_model: str = "gpt-4o-mini"
    vlm_model: str = ""                          # 그림 읽기(VLM) 전용 모델. 비우면 openai_model
    ai_temperature: float = 0.2
    ai_max_output_tokens: int = 500
    ai_timeout_seconds: float = 30.0
    ai_max_retries: int = 0
    ai_daily_call_limit: int = 30
    ai_review_max_rounds: int = 2               # PDF 박스 확인 루프의 재질문 상한(0 이면 루프를 끈다)
    ai_cache_dir: Path = REPO_ROOT / "ai" / ".cache"
    output_dir: Path = REPO_ROOT / "ai" / "output"
    # 파이프라인(배치) 입력. 기본값은 비어 있고, 파이프라인을 돌릴 때 없으면 오류로 안내한다.
    input_dir: Path | None = None
    explanations_path: Path | None = None
    approvals_path: Path | None = None
    scenario_dir: Path | None = None
    scenario_csv_path: Path | None = None
    # 업로드 실행(/v1/pipeline/run) 설정
    log_dir: Path | None = None                 # 호출 기록·일일 상한 기준 폴더(비우면 output_dir). 요청별 output_dir 을 써도 상한이 공유되도록 분리
    upload_max_files: int = 150                 # 요청당 파일 수 상한(압축 해제 후 포함)
    upload_max_file_mb: int = 30                # 파일 하나 크기 상한(MB)
    upload_max_total_mb: int = 400              # 요청 전체 크기 상한(MB, 압축 해제 후 포함)
    parse_workers: int = 0                      # 엑셀 파싱 동시 프로세스 수(0=자동: CPU 수, 최대 8)
    # 챗봇(AI 작업실)
    chat_database_url: str | None = None        # 조회용 PostgreSQL 접속 주소(AI_DATABASE_URL). 비우면 챗봇·값 채우기는 'DB 없음'으로 답한다
    chat_schema: str = "ai"                     # 조회 테이블이 든 스키마(AI_DB_SCHEMA). 연결의 search_path 가 이 스키마로 고정된다
    chat_row_limit: int = 1000                  # 조회 결과 행 상한
    chat_sql_timeout_seconds: float = 20.0      # SQL 실행 시간 상한
    chat_max_fix: int = 2                       # SQL 자기 수정 최대 횟수
    chat_sql_max_tokens: int = 700              # SQL 생성 출력 토큰 상한
    chat_text_max_tokens: int = 300             # 설명 문장 출력 토큰 상한
    chat_memory: bool = True                    # 대화 기억(서버 메모리에만, 최근 6턴·2시간). off 면 이전 대화를 전혀 쓰지 않음
    chat_test_log: bool = False                 # 질문마다 비용 측정 한 줄을 AI_CACHE_DIR/chat/test_log.jsonl 에 남김(질문 원문·키는 남기지 않음)
    upload_keep_files: bool = True              # 실행 후 업로드 파일 보존(False 면 삭제)
    # AI 보고서 틀(개발 전용 API). 켜야만 /v1/templates 가 열린다
    template_dev: bool = False
    template_dir: Path | None = None            # 틀·분류표 JSON 저장 폴더(비우면 AI_CACHE_DIR/templates)
    pdf_dev: bool = False                       # PDF 구조 추출 데모 엔드포인트(/v1/dev/pdf-structure). 켜야만 열린다

    @property
    def key_present(self) -> bool:
        return bool(self.openai_api_key)

    def require_pipeline_inputs(self, optional: tuple[str, ...] | None = None) -> dict[str, Path]:
        """optional 에 적은 변수는 없어도 된다(예: 업로드 실행은 시나리오 CSV 없이 돈다). 기본은 모두 필수."""
        optional = _OPTIONAL.get() if optional is None else optional
        need = {
            "INPUT_DIR": (self.input_dir, "업로드된 엑셀 폴더(계획·baseline·제출본·재제출 하위 폴더가 있는 01_excel)"),
            "EXPLANATIONS_PATH": (self.explanations_path, "소명 JSON 파일(explanations.json)"),
            "APPROVALS_PATH": (self.approvals_path, "승인 스냅샷 JSON 파일(approval_snapshots.json)"),
            "SCENARIO_DIR": (self.scenario_dir, "gap_key → 시트·셀 해석용 시나리오 JSON 폴더(02_scenarios)"),
            "SCENARIO_CSV_PATH": (self.scenario_csv_path, "시나리오 대조용 소명사유_전체.csv"),
        }
        missing = [f"{k} = {desc}" for k, (v, desc) in need.items() if v is None and k not in optional]
        if missing:
            raise ConfigError("파이프라인 입력 경로가 설정되지 않았습니다. ai/.env 또는 환경변수에 다음을 설정하세요:\n  - " + "\n  - ".join(missing))
        absent = [f"{k}={v}" for k, (v, _) in need.items() if v is not None and not Path(v).exists()]
        if absent:
            raise ConfigError("설정한 경로가 존재하지 않습니다: " + ", ".join(absent))
        return {k: v for k, (v, _) in need.items() if v is not None}


_OVERRIDE: contextvars.ContextVar[Settings | None] = contextvars.ContextVar("ai_settings_override", default=None)
_OPTIONAL: contextvars.ContextVar[tuple[str, ...]] = contextvars.ContextVar("ai_optional_inputs", default=())


@contextlib.contextmanager
def use_settings(**changes):
    """요청 한 건 동안만 설정(경로 등)을 바꾼다. 'optional' 로 필수에서 뺄 입력 변수 이름을 줄 수 있다. 파이프라인 계산은 건드리지 않는다."""
    optional = tuple(changes.pop("optional", ()))
    s = dataclasses.replace(get_settings(), **changes)
    t1, t2 = _OVERRIDE.set(s), _OPTIONAL.set(optional)
    try:
        yield s
    finally:
        _OVERRIDE.reset(t1)
        _OPTIONAL.reset(t2)


def get_settings() -> Settings:
    ov = _OVERRIDE.get()
    if ov is not None:
        return ov
    key, key_src = _read("OPENAI_API_KEY")
    s = Settings(
        openai_api_key=key, key_source=key_src if key else "none",
        report_service_token=_read("REPORT_AI_TOKEN")[0],
        openai_model=_read("OPENAI_MODEL", "gpt-4o-mini")[0],
        vlm_model=_read("AI_VLM_MODEL", "")[0],
        ai_temperature=float(_read("AI_TEMPERATURE", "0.2")[0]),
        ai_max_output_tokens=int(_read("AI_MAX_OUTPUT_TOKENS", "500")[0]),
        ai_timeout_seconds=float(_read("AI_TIMEOUT_SECONDS", "30")[0]),
        ai_max_retries=int(_read("AI_MAX_RETRIES", "0")[0]),
        ai_daily_call_limit=int(_read("AI_DAILY_CALL_LIMIT", "30")[0]),
        ai_review_max_rounds=max(0, min(5, int(_read("AI_REVIEW_MAX_ROUNDS", "2")[0]))),
        ai_cache_dir=_path(_read("AI_CACHE_DIR", "ai/.cache")[0]),
        output_dir=_path(_read("OUTPUT_DIR", "ai/output")[0]),
        input_dir=_path(_read("INPUT_DIR")[0]), explanations_path=_path(_read("EXPLANATIONS_PATH")[0]),
        approvals_path=_path(_read("APPROVALS_PATH")[0]), scenario_dir=_path(_read("SCENARIO_DIR")[0]),
        scenario_csv_path=_path(_read("SCENARIO_CSV_PATH")[0]),
        log_dir=_path(_read("AI_LOG_DIR")[0]),
        upload_max_files=int(_read("AI_UPLOAD_MAX_FILES", "150")[0]), upload_max_file_mb=int(_read("AI_UPLOAD_MAX_FILE_MB", "30")[0]),
        upload_max_total_mb=int(_read("AI_UPLOAD_MAX_TOTAL_MB", "400")[0]),
        parse_workers=int(_read("AI_PARSE_WORKERS", "0")[0]),
        chat_database_url=_read("AI_DATABASE_URL")[0], chat_schema=_read("AI_DB_SCHEMA", "ai")[0], chat_row_limit=int(_read("CHAT_ROW_LIMIT", "1000")[0]),
        chat_sql_timeout_seconds=float(_read("CHAT_SQL_TIMEOUT_SECONDS", "20")[0]), chat_max_fix=int(_read("CHAT_MAX_FIX", "2")[0]),
        chat_memory=str(_read("CHAT_MEMORY", "true")[0]).strip().lower() not in ("0", "false", "no", "off"),
        chat_test_log=str(_read("CHAT_TEST_LOG", "0")[0]).strip().lower() in ("1", "true", "yes", "on"),
        upload_keep_files=str(_read("AI_UPLOAD_KEEP", "true")[0]).strip().lower() not in ("0", "false", "no", "off"),
        template_dev=str(_read("AI_TEMPLATE_DEV", "0")[0]).strip().lower() in ("1", "true", "yes", "on"),
        template_dir=_path(_read("AI_TEMPLATE_DIR")[0]),
        pdf_dev=str(_read("AI_PDF_DEV", "0")[0]).strip().lower() in ("1", "true", "yes", "on"),
    )
    return s


def log_key_source(s: Settings) -> None:
    """키를 어느 쪽에서 읽었는지만 기록(값은 기록하지 않음)."""
    logger.info("OPENAI_API_KEY source=%s present=%s", s.key_source, s.key_present)
