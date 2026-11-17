"""짧은 후속 질문("12월은?", "<조직>은?", "엑셀로 줘", "상위 3개만")을 AI 호출 없이 규칙으로 이전 조건에 덮어쓴다.
규칙으로 바꿀 수 없으면 None 을 돌려주고, 서비스가 이전 조건(작은 JSON)을 SQL 생성 AI 에 같이 보낸다."""
from __future__ import annotations

import re

from . import memory

SHORT = 28
TOPN = re.compile(r"상위\s*(\d+)\s*(?:개|곳|건|명)?\s*(?:만|로)?")
TAIL = re.compile(r"^(은|는|도|만|으로|로|이랑|랑|하고|요|은요|는요)?$")
POLITE = re.compile(r"(보여줘|알려줘|보여주세요|알려주세요|줘|주세요)$")


def detect(question: str, it, st: memory.State | None) -> dict | None:
    """후속 질문 종류: topn / excel / month / org / both. 해당 없으면 None."""
    if st is None or not st.last_sql:
        return None
    q = question.strip()
    if len(q) > SHORT:
        return None
    m = TOPN.search(q)
    rest = TOPN.sub("", q)
    rest = POLITE.sub("", re.sub(r"[?？!.\s]", "", rest))
    if m and not it.orgs and not it.months and len(rest) <= 2:
        return {"kind": "topn", "n": int(m.group(1))}
    if "엑셀" in q and not it.orgs and not it.months:
        return {"kind": "excel"}
    rem = q
    for n in it.org_names:
        rem = rem.replace(n, "")
    rem = re.sub(r"\d{1,2}\s*월", "", rem)
    rem = POLITE.sub("", re.sub(r"[?？!.\s]", "", rem))
    if not TAIL.match(rem):
        return None
    if it.months and not it.orgs:
        return {"kind": "month", "months": list(it.months)}
    if it.orgs and not it.months:
        return {"kind": "org", "orgs": list(it.orgs), "org_names": list(it.org_names)}
    if it.orgs and it.months:
        return {"kind": "both", "orgs": list(it.orgs), "org_names": list(it.org_names), "months": list(it.months)}
    return None


def _set_month(sql: str, params: dict, month: int) -> tuple[str, dict] | None:
    if ":base_month" in sql:
        return sql, {**params, "base_month": month}
    if re.search(r"\bmonth\s*=\s*\d{1,2}\b", sql):
        return re.sub(r"\bmonth\s*=\s*\d{1,2}\b", f"month = {int(month)}", sql), params
    return None


def _set_orgs(sql: str, params: dict, old: list[str], new: list[str]) -> tuple[str, dict] | None:
    if "org0" in params:                                           # 고정 SQL: 값 바인딩만 바꾼다
        p = {k: v for k, v in params.items() if not re.fullmatch(r"org\d+", k)}
        if len(new) != len([k for k in params if re.fullmatch(r"org\d+", k)]):
            return None                                            # 필터 모양(= / IN)이 달라지는 경우는 AI 에 맡긴다
        p.update({f"org{i}": c for i, c in enumerate(new)})
        return sql, p
    if old and len(old) == len(new) and all(f"'{c}'" in sql for c in old):
        out = sql
        for o, n in zip(old, new):
            out = out.replace(f"'{o}'", f"'{n}'")
        return out, params
    return None


def apply(st: memory.State, change: dict) -> tuple[str, dict] | None:
    """이전 SQL·인자에 후속 조건을 적용한다. 규칙으로 못 바꾸면 None."""
    sql, params = st.last_sql, dict(st.last_params)
    k = change["kind"]
    if k == "excel":
        return sql, params
    if k == "topn":
        return f"SELECT * FROM (\n{sql}\n) LIMIT {int(change['n'])}", params
    old = st.conditions.get("orgs", [])
    if k in ("month", "both"):
        r = _set_month(sql, params, change["months"][0])
        if r is None:
            return None
        sql, params = r
    if k in ("org", "both"):
        r = _set_orgs(sql, params, old, change["orgs"])
        if r is None:
            return None
        sql, params = r
    return sql, params
