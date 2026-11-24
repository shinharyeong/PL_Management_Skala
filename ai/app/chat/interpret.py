"""질문 해석(코드가 먼저 한다): 조직·월·연·버전(시점)·지표·'모른다'·차단·되묻기 판정. AI 는 여기서 정해진 값을 받아 SQL 만 쓴다.

되묻기는 세 경우에만 한다: (a) "우리 사업부", "내 팀"처럼 누구인지 알 수 없는 지칭, (b) "지난달이랑 비교해줘"처럼 비교 대상이 빠진 경우, (c) 같은 이름이 둘 이상.
조직이 없으면 되묻지 않고 전사 합산으로 답한다(scope_all). 조직 이름·코드·연·월·버전은 모두 DB(dim_org, dim_version, fact)에서만 읽는다.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

# 데이터 입도에 없는 주제(fact 는 월 단위까지만 있다)
UNKNOWN_TOPICS = (("주별", "주 단위 값은 조회 DB 에 없어 조회할 수 없습니다(월별만 있습니다)."),
                  ("주차", "주 단위 값은 조회 DB 에 없어 조회할 수 없습니다(월별만 있습니다)."),
                  ("일별", "일 단위 값은 조회 DB 에 없어 조회할 수 없습니다(월별만 있습니다)."))
GENERIC_UNIT_WORDS = ("사업부", "부서", "조직", "팀", "본부")           # 조직 단위를 가리키는 일반 낱말(데이터 값이 아님)
SELF_PRONOUNS = ("우리", "내", "저희", "제")
METRICS = (("영업이익", "영업이익"), ("영업비용", "영업비용"), ("매출", "매출"), ("비용", "영업비용"), ("gap", "Gap"), ("갭", "Gap"), ("소명", "소명"), ("추이", "월별 추이"))
SQL_WRITE = re.compile(r"\b(drop|delete|update|insert|alter|truncate|create|attach|detach|pragma|replace)\b", re.I)
KO_WRITE = re.compile(r"(지워|지우|삭제|없애|초기화|비워|바꿔|수정해|변경해|고쳐|추가해|덮어)")
KO_WRITE_OBJ = re.compile(r"(데이터|테이블|전부|모두|모든|기록|행|레코드|소명|gap|fact|마스터|내용)", re.I)
COMPARE_WORD = re.compile(r"(비교|대비|차이|증감)")
COMPARE_BASE = re.compile(r"(지난\s*달|전\s*월|이전|직전|작년|전년|지난해)")


@dataclass
class Interp:
    question: str
    orgs: list[str] = field(default_factory=list)           # 해석된 조직 코드(dim_org 기준)
    org_names: list[str] = field(default_factory=list)
    months: list[int] = field(default_factory=list)
    year: int | None = None                                 # 질문에 연도가 있으면
    version: str | None = None                              # 해석된(또는 기본) 버전
    version_note: str = ""
    version_explicit: bool = False                          # 질문이 버전을 직접 가리켰는가(마감·잠정·확정)
    scenario: str = "ACTUAL"
    metric: str | None = None
    scope_all: bool = False                                 # 조직이 없어 전사 합산으로 답함
    unknown: str | None = None
    blocked: str | None = None
    clarify: dict | None = None
    flags: list[str] = field(default_factory=list)

    def values(self) -> dict:
        return {"orgs": self.orgs, "org_names": self.org_names, "months": self.months, "year": self.year, "version": self.version, "scenario": self.scenario, "metric": self.metric,
                "scope": "전사 합산(조직 필터 없음)" if self.scope_all else "지정한 조직", "flags": self.flags}


def _orgs(con: db.Conn) -> list[tuple[str, str]]:
    return [(r["org_code"], r["org_name"]) for r in con.execute("SELECT org_code, org_name FROM dim_org ORDER BY org_code")]


def _name_prefixes(orgs) -> list[str]:
    """DB 조직 이름에서 '끝 숫자를 뗀 앞부분'. 같은 꼴인데 DB 에 없는 번호를 말했는지 알아내는 데 쓴다."""
    return sorted({re.sub(r"\d+$", "", n) for _, n in orgs if re.search(r"\d+$", n) and re.sub(r"\d+$", "", n)}, key=len, reverse=True)


def _code_prefixes(orgs) -> list[str]:
    return sorted({m.group(1) for c, _ in orgs for m in [re.match(r"^([A-Za-z_\-]+)\d+$", c)] if m}, key=len, reverse=True)


def version_label(v: dict | None) -> str | None:
    """사용한 버전과 뜻: 'master-… (확정)' / '… (잠정, 공식 master 아님)' / '… (N월 마감)'."""
    if not v:
        return None
    kind = {"master": "확정", "provisional": "잠정, 공식 master 아님", "close": f"{v['month']}월 마감"}.get(v["kind"], v["kind"])
    return f"{v['version']} ({kind})"


def default_version(vs: list[dict]) -> str | None:
    """기본은 확정 최신(master 중 가장 최근). master 가 없으면 가장 최근 버전."""
    masters = [v for v in vs if v["kind"] == "master"]
    pool = masters or vs
    return pool[-1]["version"] if pool else None


def blocked_reason(q: str) -> str | None:
    """데이터를 바꾸거나 지우라는 요청·SQL 쓰기 문장은 SQL 을 만들지 않고 정중히 거절한다."""
    if SQL_WRITE.search(q):
        return "sql"
    if KO_WRITE.search(q) and KO_WRITE_OBJ.search(q):
        return "write"
    return None


def interpret(con: db.Conn, question: str, *, version: str | None = None) -> Interp:
    q = (question or "").strip()
    it = Interp(question=q)
    br = blocked_reason(q)
    if br:
        it.blocked = br
        return it
    orgs = _orgs(con)
    by_name: dict[str, list[str]] = {}
    for c, n in orgs:
        by_name.setdefault(n, []).append(c)
    by_code = {c: n for c, n in orgs}
    vs = sorted((dict(r) for r in con.execute("SELECT version, kind, year, month, is_latest FROM dim_version WHERE month IS NOT NULL")), key=lambda v: (v["year"], v["month"]))
    by_ver = {v["version"]: v for v in vs}

    # 1) 데이터 입도에 없는 주제
    for kw, msg in UNKNOWN_TOPICS:
        if kw in q:
            it.unknown = msg
            return it
    # 2) 조직: DB 의 이름(긴 것부터, 뒤에 숫자가 더 붙으면 다른 조직)·코드로 찾는다. 같은 꼴인데 DB 에 없는 번호·코드는 '모른다'
    work = q
    dup = None
    for name in sorted(by_name, key=len, reverse=True):
        for m in re.finditer(re.escape(name) + r"(?!\d)", work):
            codes = by_name[name]
            if len(codes) > 1:
                dup = (name, codes)                                     # (c) 같은 이름이 둘 이상
            for c in codes[:1]:
                if c not in it.orgs:
                    it.orgs.append(c); it.org_names.append(name)
            work = work[:m.start()] + " " * (m.end() - m.start()) + work[m.end():]
    for code in sorted(by_code, key=len, reverse=True):
        for m in re.finditer(r"(?<![A-Za-z0-9])" + re.escape(code) + r"(?![A-Za-z0-9])", work):
            if code not in it.orgs:
                it.orgs.append(code); it.org_names.append(by_code[code])
            work = work[:m.start()] + " " * (m.end() - m.start()) + work[m.end():]
    if dup:
        it.clarify = {"question": f"'{dup[0]}'이(가) 둘 이상 있습니다. 어느 쪽인가요?", "options": [f"{dup[0]} ({c})" for c in dup[1]], "slot": "dup", "replace": dup[0]}
        return it
    for p in _name_prefixes(orgs):
        for m in re.finditer(re.escape(p) + r"\s*(\d+)", work):
            it.unknown = f"{p}{int(m.group(1))} 은(는) 데이터에 없습니다(있는 조직 {len(orgs)}개)."
            return it
    for p in _code_prefixes(orgs):
        for m in re.finditer(r"(?<![A-Za-z])" + re.escape(p) + r"\d+(?![A-Za-z0-9])", work):
            it.unknown = f"{m.group(0)} 은(는) 데이터에 없습니다."
            return it
    unit_words = sorted(set(GENERIC_UNIT_WORDS) | set(_name_prefixes(orgs)), key=len, reverse=True)
    sm = re.search(r"(?:" + "|".join(SELF_PRONOUNS) + r")\s*(?:" + "|".join(re.escape(w) for w in unit_words) + ")", q)
    if not it.orgs and sm:                                                  # (a) 누구인지 알 수 없는 지칭
        it.clarify = {"question": "어느 조직을 말씀하시나요? 이름으로 고르거나 직접 입력하세요.", "options": [n for _, n in orgs], "slot": "org", "replace": sm.group(0)}
        return it
    # 지표(메트릭)
    low = q.lower()
    for kw, label in METRICS:
        if kw in low:
            it.metric = label
            break
    # (b) 비교 대상이 빠진 경우: 비교 기준 낱말은 있는데 무엇을 비교하는지(지표·조직)가 없다
    if COMPARE_BASE.search(q) and COMPARE_WORD.search(q) and not it.metric and not it.orgs:
        it.clarify = {"question": "무엇을 비교할까요? 하나를 고르거나 직접 입력하세요.", "options": ["영업이익", "매출", "영업비용"], "slot": "compare", "replace": ""}
        return it
    # 3) 연도·월(마감 표현은 아래에서 버전으로 처리)
    close_m = re.search(r"(\d{1,2})\s*월\s*마감", q)
    ym = re.search(r"(\d{4})\s*년", q)
    for m in re.finditer(r"(\d{1,2})\s*월", q):
        mo = int(m.group(1))
        if not 1 <= mo <= 12:
            it.unknown = f"{mo}월은 올바른 월이 아닙니다."
            return it
        if mo not in it.months:
            it.months.append(mo)
    if ym:
        it.year = int(ym.group(1))
    # 4) 버전(시점): 기본은 확정 최신. 질문이 "N월 마감 기준"·"잠정"·"마지막 승인 전"일 때만 바꾼다
    chosen = version
    if close_m:
        mo = int(close_m.group(1))
        cand = [v for v in vs if v["kind"] == "close" and v["month"] == mo and (it.year is None or v["year"] == it.year)]
        if not cand:
            it.unknown = f"{mo}월 마감 기준 데이터가 적재되어 있지 않습니다(적재된 버전: {', '.join(v['version'] for v in vs)})."
            return it
        chosen = cand[-1]["version"]
        it.months = [x for x in it.months if x != mo] or it.months
        it.flags.append("close")
    elif "잠정" in q or "승인 전" in q:
        cand = [v for v in vs if v["kind"] == "provisional" and (it.year is None or v["year"] == it.year)]
        if not cand:
            it.unknown = "잠정(마지막 승인 전) 버전 데이터가 적재되어 있지 않습니다."
            return it
        chosen = cand[-1]["version"]
        it.flags.append("provisional")
    elif "확정" in q or "마스터" in q:
        cand = [v for v in vs if v["kind"] == "master" and (it.year is None or v["year"] == it.year)]
        if not cand:
            it.unknown = "확정 master 데이터가 적재되어 있지 않습니다."
            return it
        chosen = cand[-1]["version"]
        it.flags.append("master")
    it.version = chosen or default_version(vs)
    it.version_explicit = bool(chosen)
    it.version_note = "질문에서 읽은 시점" if chosen else "기본값(확정 최신)"
    # 적재되지 않은 연도·월: 사용 버전의 적재 기간을 알려 준다
    rng = con.execute("SELECT MIN(year*100+month) AS a, MAX(year*100+month) AS b FROM fact WHERE version = ?", (it.version,)).fetchone()
    if rng and rng["a"]:
        lo, hi = divmod(rng["a"], 100), divmod(rng["b"], 100)
        span = f"{lo[0]}-{lo[1]:02d}~{hi[0]}-{hi[1]:02d}"
        years = {r[0] for r in con.execute("SELECT DISTINCT year FROM fact WHERE version = ?", (it.version,))}
        if it.year is not None and it.year not in years:
            it.unknown = f"{it.year}년 데이터는 적재되어 있지 않습니다. 적재된 기간은 {span}입니다."
            return it
        if it.months and it.year is None and chosen is None:
            ms = {r[0] for r in con.execute("SELECT DISTINCT month FROM fact WHERE version = ?", (it.version,))}
            miss = [m for m in it.months if m not in ms]
            if miss:
                it.unknown = f"{miss[0]}월 데이터는 적재되어 있지 않습니다. 적재된 기간은 {span}입니다."
                return it
    if "추정" in q and "실적" not in q:
        it.scenario = "ESTIMATE"
    elif "계획" in q and "실적" not in q:
        it.scenario = "PLAN"
    it.scope_all = not it.orgs
    return it


def base_params(con: db.Conn, it: Interp) -> dict:
    """SQL 에 바인딩할 인자(:version :version_prev :base_year :base_month :scn). 연·월은 질문 또는 DB(dim_version)에서만 읽는다."""
    from . import db
    v = next((dict(r) for r in con.execute("SELECT version, year, month FROM dim_version WHERE version=?", (it.version,))), None)
    year = it.year or (v["year"] if v else None)
    month = it.months[0] if it.months else (v["month"] if v else None)
    return {"version": it.version, "version_prev": db.previous_version(con, it.version) or it.version, "base_year": year, "base_month": month, "scn": it.scenario}
