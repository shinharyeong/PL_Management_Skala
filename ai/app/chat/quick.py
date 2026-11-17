"""예시 질문(빠른 질문) 5개: 설정 파일 quick_questions.json 에서 읽는다(코드에 SQL·문구를 두지 않는다).
AI 키가 없거나 상한 초과·실패일 때도 이 고정 SQL 로 대신 답한다(화면에는 'AI 없이 기본 질의'로 표시)."""
from __future__ import annotations

import json
from pathlib import Path

CONFIG = Path(__file__).with_name("quick_questions.json")


def _load() -> list[dict]:
    return json.loads(CONFIG.read_text(encoding="utf-8"))["quick_questions"]


QUICK = _load()
BY_ID = {q["id"]: q for q in QUICK}


def render(q: dict, org_codes: list[str]) -> tuple[str, dict]:
    """고정 SQL 과 조직 필터용 바인딩 인자(:org0, :org1 …). 조직 코드는 값으로 묶어서 넣는다(문자열을 SQL 에 이어 붙이지 않음)."""
    params = {f"org{i}": c for i, c in enumerate(org_codes)}
    flt = "" if not org_codes else (" AND org_code = :org0" if len(org_codes) == 1 else " AND org_code IN (" + ", ".join(f":org{i}" for i in range(len(org_codes))) + ")")
    return q["sql"].replace("{org_filter}", flt), params


def match(question: str) -> dict | None:
    """질문에 키워드가 가장 많이 맞는 예시 질문(동점이거나 0개면 None)."""
    ql = question.lower()
    scores = [(sum(1 for k in q["keywords"] if k in ql), q) for q in QUICK]
    best = max(s for s, _ in scores)
    tops = [q for s, q in scores if s == best]
    return tops[0] if best >= 1 and len(tops) == 1 else None


def public(month: int | None = None, org: str | None = None) -> list[dict]:
    """화면용 예시 질문: label 의 {month}·{org} 를 DB 값(최신 월, 실제 조직 이름)으로 채운다. 값이 없으면 형식만 보인다."""
    out = []
    for q in QUICK:
        label = q["label"].replace("{month}", str(month) if month else "<월>").replace("{org}", org or "<조직>")
        out.append({"id": q["id"], "label": label})
    return out
