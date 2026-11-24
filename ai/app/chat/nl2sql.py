"""챗봇의 AI 호출 두 가지(SQL 생성, 설명 문장). 기존 호출 기록(call_log)·하루 상한·OpenAI 클라이언트 생성 함수를 그대로 쓴다.

- 데이터 값은 AI 에 보내지 않는다: SQL 생성에는 테이블 구조·규칙·예시·질문·해석된 값(조직 코드·월·버전 이름)만, 설명에는 조회 결과 행(미리보기)만 보낸다.
- 같은 질문(+구조·해석값)의 SQL 은 캐시에서 읽어 AI 를 부르지 않는다. 재시도 없음(자기 수정은 서비스가 최대 2번 따로 부른다).
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

from ..config import Settings
from ..services import ai_reasons, openai_client
from ..services.call_log import CallLog
from ..services.key_messages import FORBIDDEN, numbers_in
from . import schema

SQL_VERSION, TEXT_VERSION = "chat-sql-v4", "chat-text-v1"
SQL_SYSTEM = ("너는 재무 데이터 조회용 PostgreSQL SQL 작성 보조다. 아래 구조와 규칙만 따른다. 데이터 값은 모른다(질문과 해석된 값만 받는다). 숫자를 지어내지 않는다.\n\n"
              + schema.SCHEMA_DOC + "\n\n" + schema.RULES + "\n\n" + schema.EXAMPLES)
TEXT_SYSTEM = ("너는 재무 조회 결과를 설명하는 보조다. 주어진 결과 행만 사용해 보고서체 1~3문장으로 쓴다. 숫자는 결과에 있는 값이나 억원 환산(소수 1자리)만 쓴다. "
               "새 숫자, 원인, 추측, 전망을 쓰지 않는다. 결과가 0행이면 '해당 데이터가 없습니다.'라고만 쓴다. "
               '출력은 JSON {"text": "..."} 만.')


class AiUnavailable(RuntimeError):
    """AI 를 쓸 수 없음: reason 은 NO_API_KEY / DAILY_LIMIT_EXCEEDED / AI_CALL_FAILED:<형식>."""

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


@dataclass
class Budget:
    """이 요청에서 쓴 AI 사용량."""
    calls: int = 0
    cache_hits: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    failed: int = 0
    reasons: list[str] = field(default_factory=list)

    def add_reason(self, r: str) -> None:
        if r and r not in self.reasons:
            self.reasons.append(r)

    def public(self) -> dict:
        return {"calls": self.calls, "cache_hits": self.cache_hits, "input_tokens": self.input_tokens, "output_tokens": self.output_tokens, "failed": self.failed, "reasons": self.reasons}


def reason_ko(r: str) -> str:
    return ai_reasons.ko(r) or r


def ai_ready(s: Settings, budget: Budget | None = None) -> str | None:
    """AI 를 지금 부를 수 있으면 None, 아니면 사유 코드."""
    if not s.key_present:
        return "NO_API_KEY"
    used = CallLog(s.log_dir or s.output_dir).calls_today() + (0 if budget is None else 0)
    if used >= s.ai_daily_call_limit:
        return "DAILY_LIMIT_EXCEEDED"
    return None


def _call(s: Settings, task: str, system: str, user: str, max_tokens: int, budget: Budget, version: str) -> dict:
    why = ai_ready(s, budget)
    if why:
        raise AiUnavailable(why)
    log = CallLog(s.log_dir or s.output_dir)
    meta = {"task": task, "status": "-", "model": s.openai_model, "prompt_version": version, "facts_hash": hashlib.sha256(user.encode("utf-8")).hexdigest()[:12]}
    try:
        client = openai_client.make_client(s)
        resp = client.chat.completions.create(model=s.openai_model, temperature=s.ai_temperature, max_tokens=max_tokens, response_format={"type": "json_object"},
                                              messages=[{"role": "system", "content": system}, {"role": "user", "content": user}])
        out = json.loads(resp.choices[0].message.content)
        if not isinstance(out, dict):
            raise ValueError("JSON object expected")
        tin, tout = int(getattr(resp.usage, "prompt_tokens", 0) or 0), int(getattr(resp.usage, "completion_tokens", 0) or 0)
    except Exception as e:  # noqa: BLE001 — 오류 종류만 기록(키·응답 원문 없음)
        kind = type(e).__name__ + (f" HTTP {e.status_code}" if getattr(e, "status_code", None) else "")
        log.append({**meta, "outcome": "error", "input_tokens": 0, "output_tokens": 0, "error": kind})
        budget.calls += 1
        budget.failed += 1
        raise AiUnavailable(f"AI_CALL_FAILED:{kind}") from e
    log.append({**meta, "outcome": "success", "input_tokens": tin, "output_tokens": tout})
    budget.calls += 1
    budget.input_tokens += tin
    budget.output_tokens += tout
    return out


# ------------------------------------------------------------------ SQL 생성
def sql_cache_key(s: Settings, question: str, interp_values: dict) -> str:
    norm = re.sub(r"\s+", " ", question.strip().lower())
    raw = f"{s.openai_model}|{SQL_VERSION}|{schema.SCHEMA_HASH}|{norm}|{json.dumps(interp_values, ensure_ascii=False, sort_keys=True)}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def _cache_path(s: Settings, key: str) -> Path:
    return s.ai_cache_dir / "chat" / "sql_cache" / f"{key}.json"


def cache_get(s: Settings, key: str) -> dict | None:
    p = _cache_path(s, key)
    try:
        return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None
    except (OSError, ValueError):
        return None


def cache_put(s: Settings, key: str, resp: dict) -> None:
    p = _cache_path(s, key)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(resp, ensure_ascii=False), encoding="utf-8")


def generate_sql(s: Settings, question: str, interp_values: dict, params_names: list[str], budget: Budget, *, previous: tuple[str, str] | None = None,
                 context: dict | None = None) -> dict:
    """AI 에게 SQL 을 만들게 한다. previous=(이전 SQL, 오류 설명)이면 고쳐 쓰게 하고, context 는 이전 대화의 조건(작은 JSON, 300토큰 이내)이다. 반환: {"sql","clarify","unknown"}"""
    user = f"질문: {question}\n해석된 값(코드가 먼저 뽑은 것): {json.dumps(interp_values, ensure_ascii=False)}\n쓸 수 있는 인자 이름: {', '.join(':' + p for p in params_names)}"
    if context:
        ctx = json.dumps(context, ensure_ascii=False)
        user += f"\n이전 대화에서 이어지는 조건(질문에 없는 값은 이 조건을 따른다): {ctx[:900]}"
    if previous:
        user += f"\n\n이전에 만든 SQL:\n{previous[0]}\n이 SQL 의 오류: {previous[1]}\n오류를 고친 SQL 하나만 다시 만든다."
    out = _call(s, "chat_sql", SQL_SYSTEM, user, s.chat_sql_max_tokens, budget, SQL_VERSION)
    return {"sql": out.get("sql"), "clarify": out.get("clarify"), "unknown": out.get("unknown")}


# ------------------------------------------------------------------ 설명 문장
def _eok(v) -> Decimal:
    return (Decimal(str(v)) / Decimal(10) ** 8).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)


def _eok2(v) -> Decimal:
    return (Decimal(str(v)) / Decimal(10) ** 8).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def allowed_numbers(columns: list[str], rows: list[tuple], question: str, interp_values: dict, row_count: int) -> set[str]:
    """설명 문장에 나와도 되는 숫자: 결과 셀 값, 금액의 억원 환산(1·2자리), 열 합계, 행 수, 질문·해석값에 나온 숫자."""
    ok: set[str] = set(numbers_in(question)) | {str(row_count), str(len(columns))}
    for m in interp_values.get("months", []):
        ok.add(str(m))
    for o in interp_values.get("org_names", []) + interp_values.get("orgs", []):
        ok |= set(numbers_in(str(o)))
    ok |= set(numbers_in(str(interp_values.get("version") or "")))
    for ci, c in enumerate(columns):
        vals = [r[ci] for r in rows if isinstance(r[ci], (int, float)) and not isinstance(r[ci], bool)]
        money = c.endswith("_won")
        for v in vals + ([sum(vals)] if vals else []):
            for x in {v, abs(v)}:
                ok.add(str(int(x)) if float(x) == int(x) else str(x))
                if money:
                    ok |= {str(_eok(x)), str(_eok2(x)), str(_eok(x).quantize(Decimal(1), rounding=ROUND_HALF_UP))}
                else:
                    ok |= {str(round(float(x), 1)), str(round(float(x), 2))}
        for r in rows:
            if isinstance(r[ci], str):
                ok |= set(numbers_in(r[ci]))
    return {n.replace(",", "") for n in ok}


def validate_text(text: str, allowed: set[str]) -> list[str]:
    reasons = []
    bad = [n for n in numbers_in(text) if n not in allowed]
    if bad:
        reasons.append(f"NUMBER_NOT_IN_RESULT:{','.join(bad)}")
    hit = [w for w in FORBIDDEN if w in text]
    if hit:
        reasons.append(f"FORBIDDEN_WORD:{','.join(hit)}")
    if not text.strip():
        reasons.append("EMPTY")
    return reasons


def generate_text(s: Settings, question: str, columns: list[str], rows_preview: list[tuple], row_count: int, truncated: bool, budget: Budget) -> str:
    payload = {"질문": question, "columns": columns, "rows": [list(r) for r in rows_preview], "row_count": row_count, "truncated": truncated}
    out = _call(s, "chat_text", TEXT_SYSTEM, json.dumps(payload, ensure_ascii=False, default=str), s.chat_text_max_tokens, budget, TEXT_VERSION)
    return str(out.get("text") or "")


LABELS = {"revenue_won": "매출", "cost_won": "영업비용", "op_won": "영업이익", "amount_won": "금액", "diff_won": "차이", "previous_won": "이전 값", "current_won": "현재 값",
          "prev_op_won": "이전 영업이익"}


def template_text(columns: list[str], rows: list[tuple], row_count: int, truncated: bool) -> str:
    """AI 없이 쓰는 기본 문장: 조회 행 수, 금액 열 합계, 첫 행. 숫자는 모두 결과에서 계산한다."""
    if row_count == 0:
        return "해당 데이터가 없습니다."
    parts = [f"{row_count}행을 조회했습니다" + ("(행 상한으로 일부만 표시)." if truncated else ".")]
    won_cols = [i for i, c in enumerate(columns) if c.endswith("_won")][:2]
    for i in won_cols:
        vals = [r[i] for r in rows if isinstance(r[i], (int, float))]
        if vals:
            parts.append(f"{LABELS.get(columns[i], columns[i])} 합계는 {_eok(sum(vals))}억원입니다.")
    text_i = next((i for i, c in enumerate(columns) if not c.endswith("_won") and isinstance(rows[0][i], str)), None)
    if won_cols and text_i is not None and isinstance(rows[0][won_cols[0]], (int, float)):
        parts.append(f"첫 행은 {rows[0][text_i]} {LABELS.get(columns[won_cols[0]], columns[won_cols[0]])} {_eok(rows[0][won_cols[0]])}억원입니다.")
    return " ".join(parts)
