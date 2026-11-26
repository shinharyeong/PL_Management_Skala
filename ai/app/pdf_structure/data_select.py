"""데이터 선택(구성 확인 다음 단계). 틀의 블록마다 '어느 기간·범위·비교 기준의 데이터로 채울지'를 조회 DB 로 확인한다.

값은 만들지 않는다. 여기서는 (1) 어느 월에 확정/잠정 데이터가 있는지, (2) 블록의 행 이름이 DB 지표에 대응하는지,
(3) 상대 기간이 기준 월에서 실제 어느 월이 되는지, (4) 그 월에 데이터가 있는지만 코드로 답한다. 연도·월·조직 이름은 모두 DB 에서 읽는다.
"""
from __future__ import annotations

import re

from ..chat import db
from ..services.numeric import amount_precision, exact_json_value
from ..report_template import periods
from ..report_template.model import Period

STATE_LABEL = {"master": "확정", "provisional": "잠정"}
COMPARE_WORDS = (("전년", "전년 동월"), ("전월", "직전 월"), ("지난달", "직전 월"), ("계획", "계획"), ("추정", "직전 추정"))
DERIVED = {"영업이익": "v_op"}                      # 합계 행이 없어 DB 뷰가 계산하는 지표(챗봇과 같은 규칙: 매출 − 영업비용)


def _con() -> db.Conn | None:
    try:
        return db.open_readonly()
    except Exception:  # noqa: BLE001 — DB 가 아직 없으면 '데이터 없음' 으로 답한다
        return None


def match_org(label: str, orgs: list[dict]) -> str | None:
    """행 이름에서 조직을 찾는다. 코드나 이름이 다른 글자에 붙어 있지 않을 때만 일치로 본다('사업부1' 이 '사업부10' 에 걸리지 않게)."""
    for o in orgs:
        for key in (o["code"], o["name"]):
            if key and re.search(rf"(?<![0-9A-Za-z가-힣]){re.escape(key)}(?![0-9A-Za-z가-힣])", label):
                return o["code"]
    return None


def months() -> dict:
    """백엔드가 정한 월별 출처 상태와 기본 기준 월을 그대로 조회한다.

    Args:
        없음.
    Returns:
        DB 준비 여부, 월별 출처·상태, 기본 월, 지표·조직 목록.
    Raises:
        DB 조회 오류는 호출부에 전달한다.
    Side Effects:
        보고서용 뷰를 읽고 연결을 닫는다. 잠정·확정 판정이나 마스터를 만들지 않는다.
    """
    con = _con()
    if con is None:
        return {"db_ready": False, "months": [], "default": None, "metrics": [], "orgs": []}
    try:
        rows = con.execute("SELECT version, kind, year, month, is_latest FROM dim_version "
                           "WHERE year IS NOT NULL AND month IS NOT NULL AND kind IN ('master','provisional') ORDER BY year, month")
        ms = [{"year": r["year"], "month": r["month"], "kind": r["kind"], "state": STATE_LABEL[r["kind"]],
               "version": r["version"], "is_latest": bool(r["is_latest"])} for r in rows]
        default = next((m for m in ms if m["is_latest"]), None)
        metrics = sorted({r[0] for r in con.execute("SELECT DISTINCT kind FROM fact")} | set(DERIVED))
        orgs = [{"code": r["org_code"], "name": r["org_name"]} for r in con.execute("SELECT org_code, org_name FROM dim_org ORDER BY org_code")]
        return {"db_ready": True, "months": ms, "default": {"year": default["year"], "month": default["month"]} if default else None, "metrics": metrics, "orgs": orgs}
    finally:
        con.close()


def compare_basis(columns: list[str]) -> list[str]:
    out = []
    for c in columns:
        for key, name in COMPARE_WORDS:
            if key in c and name not in out:
                out.append(name)
    return out


def resolve(base: dict, blocks: list[dict]) -> dict:
    """base={'year','month'} 와 블록별 {id, kind, labels, columns, period, text} → 블록별 기간·범위·비교 기준·상태."""
    info = months()
    have = {(m["year"], m["month"]): m for m in info["months"]}
    metrics = set(info["metrics"])
    org_names = {o["name"]: o["code"] for o in info["orgs"]} | {o["code"]: o["code"] for o in info["orgs"]}
    res = []
    for b in blocks:
        kind = b.get("kind")
        labels = [str(x) for x in b.get("labels") or []]
        row = {"id": b["id"], "kind": kind, "months": [], "scope": "—", "compare": [], "metric_match": None, "state": "—", "reason": None}
        if kind in ("title", "footnote"):
            res.append(row)
            continue
        p = Period.model_validate(b.get("period") or {"rule": "base"})
        ms = periods.resolve(p, (base["year"], base["month"]))
        row["months"] = [{"year": y, "month": m, "state": (have[(y, m)]["state"] if (y, m) in have else "데이터 없음")} for y, m in ms]
        row["compare"] = compare_basis([str(c) for c in b.get("columns") or []])
        hit_org = [l for l in labels if match_org(l, info["orgs"])]
        row["scope"] = f"조직별({len(hit_org)}개 일치)" if hit_org else "전사"
        found = sorted({m for l in labels for m in metrics if m in l})
        row["metric_match"] = found
        if not labels and kind in ("paragraph", "list"):
            row["state"], row["reason"] = "문장", "숫자 칸이 문장 속에 있어 데이터 선택 대상이 아니라 문장 생성 때 채웁니다"
        elif not found and not hit_org:
            row["state"], row["reason"] = "데이터 없음", "행 이름이 지표 사전(" + ", ".join(sorted(metrics)) + ")에 없음"
        else:
            sts = [m["state"] for m in row["months"]]
            row["state"] = "데이터 없음" if "데이터 없음" in sts else ("잠정" if "잠정" in sts else "확정")
            if row["state"] == "데이터 없음":
                row["reason"] = "선택한 기간 중 데이터가 없는 월이 있음"
        res.append(row)
    return {"base": base, "blocks": res, "provisional": any(r["state"] == "잠정" for r in res)}


BASIS_LABEL = {"PLAN": "계획 대비", "PREVIOUS": "직전 추정 대비"}


@amount_precision()
def gaps(year: int, month: int) -> dict:
    """백엔드 ITEM_GAP과 수락 소명을 보고서 화면용 카드로 읽는다.

    Args:
        year: 기준 연도.
        month: 기준 월. 실적은 이 월, 추정은 다음 월의 항목을 읽는다.
    Returns:
        출처 버전과 비교 종류별 카드. total_won은 해당 보고 항목 차이의 합계이며
        영업이익 기여도나 자체 임계값 판정 결과가 아니다.
    Raises:
        DB 조회 오류는 호출부에 전달한다.
    Side Effects:
        gap·explanation 뷰만 읽는다. 소명을 분류·저장하거나 Gap을 재판정하지 않는다.
    """
    info = months()
    current = next((v for v in info["months"] if v["year"] == year and v["month"] == month), None)
    empty = {"db_ready": info["db_ready"], "version": None, "state": None, "year": year, "month": month, "groups": []}
    if current is None:
        return empty
    con = _con()
    if con is None:
        return {**empty, "db_ready": False}
    try:
        ver = current["version"]
        groups = []
        next_year, next_month = (year + 1, 1) if month == 12 else (year, month + 1)
        keys = con.execute("SELECT scenario, basis, year, month, COUNT(*) n FROM gap WHERE version=? AND gap_type='ITEM_GAP' "
                           "AND ((scenario='ACTUAL' AND year=? AND month=?) OR (scenario='ESTIMATE' AND year=? AND month=?)) "
                           "GROUP BY scenario, basis, year, month ORDER BY scenario, basis", (ver, year, month, next_year, next_month)).fetchall()
        for key in keys:
            rows = con.execute("SELECT gap_id, org_code, org_name, item, diff_won, previous_won, current_won, status, explanation_status FROM gap "
                               "WHERE version=? AND gap_type='ITEM_GAP' AND year=? AND month=? AND scenario=? AND basis=? ORDER BY org_code, gap_id",
                               (ver, key["year"], key["month"], key["scenario"], key["basis"])).fetchall()
            cards = []
            for row in rows:
                gap_key = row["gap_id"].rsplit("-", 1)[0]
                explanations = con.execute("SELECT explanation_id, item, kind, status, answer FROM explanation "
                                           "WHERE version=? AND gap_key=? ORDER BY rounds DESC, explanation_id DESC", (ver, gap_key)).fetchall()
                latest = explanations[0] if explanations else None
                accepted = latest if latest and latest["status"] == "수락" and latest["answer"] and row["explanation_status"] == "수락" else None
                cards.append({"gap_id": row["gap_id"], "org_code": row["org_code"], "org_name": row["org_name"], "item": row["item"],
                              "diff_won": row["diff_won"], "summary": accepted["answer"] if accepted else None,
                              "items": [row["item"]], "explained": accepted is not None, "explanation_count": len(explanations),
                              "carried_over": False, "status": row["status"]})
            groups.append({"scenario": key["scenario"], "basis": key["basis"], "year": key["year"], "month": key["month"],
                           "basis_label": BASIS_LABEL.get(key["basis"], key["basis"]),
                           "total_won": sum(row["diff_won"] for row in rows), "cards": cards})
        return {"db_ready": True, "version": ver, "state": current["state"], "year": year, "month": month, "groups": groups}
    finally:
        con.close()


# ===================================================================== 5단계: 보고서 값 채우기(코드 계산)
from decimal import ROUND_HALF_UP, Decimal

from . import table_header as TH

METRIC_COL = {"매출": "revenue_won", "영업비용": "cost_won", "영업이익": "op_won"}     # DB 지표 이름 → v_op 컬럼
_KEYWORD = re.compile(r"전년|전월|지난달|계획|추정")
_DATE = re.compile(r"'?(\d{2})\.(\d{1,2})월")


import contextvars

UNIT = contextvars.ContextVar("report_unit", default="억원")
SCOPE_ORG = contextvars.ContextVar("report_scope_org", default=None)
SCOPE_ORGS = contextvars.ContextVar("report_scope_orgs", default=[])
UNIT_DIV = {"원": Decimal(1), "천원": Decimal(10) ** 3, "만원": Decimal(10) ** 4, "백만원": Decimal(10) ** 6, "억원": Decimal(10) ** 8, "조원": Decimal(10) ** 12}
UNIT_DIGITS = {"원": "1", "천원": "1", "만원": "1", "백만원": "0.1", "억원": "0.1", "조원": "0.01"}    # 표시 소수 자리(원·천원·만원은 정수)


def eok(won, *, diff: bool = False, plain_sign: bool = True) -> str:
    """원 → 억(소수 1자리, 반올림). diff 면 부호를 붙이고 음수는 △. 블록 단위가 백만원이면 백만원으로 환산(코드 계산)."""
    unit = UNIT.get() if UNIT.get() in UNIT_DIV else "억원"
    digits = Decimal(UNIT_DIGITS[unit])
    v = (Decimal(won) / UNIT_DIV[unit]).quantize(digits, rounding=ROUND_HALF_UP)
    if not diff:
        return f"{v:,}"
    if v == 0:
        return f"{abs(v):,}"
    return (f"+{v:,}" if v > 0 else f"△{abs(v):,}")


def _won(v) -> str | None:
    """내보내기 원 금액을 반올림하지 않은 십진 문자열로 만든다.

    Args:
        v: 원 단위 정수·Decimal 또는 None.
    Returns:
        소수 끝의 불필요한 0을 뺀 정확한 금액 문자열 또는 None.
    Raises:
        decimal.InvalidOperation: 숫자 형식이 잘못된 경우.
    Side Effects:
        없음. Decimal 기본 정밀도에 따른 normalize·quantize를 하지 않는다.
    """
    if v is None:
        return None
    d = Decimal(str(v))
    text = format(d, "f")
    return text.rstrip("0").rstrip(".") if "." in text else text


def retime(text: str, orig: tuple[int, int] | None, base: dict) -> str:
    """틀에 적힌 원래 월(예: '26.11월 / 11월)을 선택한 기준 월로 바꾼다. 원래 월을 모르면 그대로."""
    if not orig:
        return text
    oy, om = orig
    yy = f"{base['year'] % 100:02d}"
    text = _DATE.sub(lambda m: f"'{yy}.{base['month']}월" if (int(m.group(1)), int(m.group(2))) == (oy, om) else m.group(0), text)
    def ym(m):                                                         # '2026.11' 같은 연.월 표기(월 글자 없음)
        if int(m.group(1)) % 100 == oy and int(m.group(2)) == om:
            return f"{base['year']}.{base['month']:02d}" if len(m.group(2)) == 2 else f"{base['year']}.{base['month']}"
        return m.group(0)
    text = re.sub(r"(?<![\d.])(20\d{2})\.(\d{1,2})(?![\d.]|월)", ym, text)
    return re.sub(rf"(?<![\d.'→]){om}월(?!→)", f"{base['month']}월", text)


def _best_version(info: dict, y: int, m: int) -> str | None:
    x = next((v for v in info["months"] if v["year"] == y and v["month"] == m), None)
    return x["version"] if x else None


def _val(con, version, y, m, scenario, col, org=None):
    if version is None:
        return None
    q = f"SELECT SUM({col}) s, COUNT(*) n FROM v_op WHERE version=? AND year=? AND month=? AND scenario=?" + (" AND org_code=?" if org else "")
    r = con.execute(q, (version, y, m, scenario) + ((org,) if org else ())).fetchone()
    return r["s"] if r and r["n"] else None


def _bases(con, info, y, m, col, org):
    """현재 값과 비교 기준 값들(없으면 None)."""
    ver = _best_version(info, y, m)
    py, pm = (y, m - 1) if m > 1 else (y - 1, 12)
    pver = _best_version(info, py, pm)
    return {"현재": _val(con, ver, y, m, "ACTUAL", col, org),
            "계획": _val(con, ver, y, m, "PLAN", col, org),
            "직전 월": _val(con, pver, py, pm, "ACTUAL", col, org),
            "전년 동월": _val(con, _best_version(info, y - 1, m), y - 1, m, "ACTUAL", col, org),
            "직전 추정": _val(con, pver, y, m, "ESTIMATE", col, org)}


def _cname(word: str) -> str:
    return {"전년": "전년 동월", "전월": "직전 월", "지난달": "직전 월", "계획": "계획", "추정": "직전 추정"}[word]


def _metric_in(text: str, metrics) -> str | None:
    hits = [(text.find(k), k) for k in metrics if k in text]
    return min(hits)[1] if hits else None


def _accepted_causes(gap_set: dict, year: int, month: int) -> dict[str, str]:
    """블록 기준 월의 실적 원문만 조직별로 묶어 원인 칸에 표시한다.

    Args:
        gap_set: backend Gap과 최신 수락 소명을 읽은 groups 목록.
        year: 보고서 블록의 기준 연도.
        month: 보고서 블록의 기준 월.
    Returns:
        조직 코드별 수락 원문. PLAN/PREVIOUS의 같은 문장은 한 번만 표시한다.
    Raises:
        KeyError: 내부 Gap 카드의 필수 필드가 없는 경우.
    Side Effects:
        없음. 다음 월 추정 사유를 실적 칸에 섞거나 금액을 재배분하지 않는다.
    """
    parts = {}
    for group in gap_set["groups"]:
        if (group["scenario"], group["year"], group["month"]) != ("ACTUAL", year, month):
            continue
        for card in group["cards"]:
            if card["summary"]:
                parts.setdefault(card["org_code"], []).append(card["summary"])
    return {org: " / ".join(dict.fromkeys(texts)) for org, texts in parts.items()}


@amount_precision()
def fill_report(base: dict, blocks: list[dict], orig: tuple[int, int] | None = None) -> dict:
    """백엔드 보고서용 뷰 값과 수락 소명으로 보고서 블록을 채운다.

    Args:
        base: 기준 year·month.
        blocks: 기간·조직·표현 형식을 가진 블록 목록.
        orig: 원래 문서 기준 연월 또는 None.
    Returns:
        채운 블록과 출처 상태. 없는 값은 missing으로 표시한다.
    Raises:
        DB 조회·블록 입력 오류는 호출부에 전달한다.
    Side Effects:
        읽기 전용 DB 조회와 요청 문맥 설정을 수행한다. Gap 임계값·소명 분류·출처 확정은 하지 않는다.
    """
    info = months()
    out = {"base": base, "blocks": [], "provisional": False}
    if not info["db_ready"]:
        return exact_json_value(out)
    con = _con()
    try:
        y, m = base["year"], base["month"]
        cur = next((v for v in info["months"] if v["year"] == y and v["month"] == m), None)
        out["state"] = cur["state"] if cur else None
        out["version"] = cur["version"] if cur else None
        metrics = [k for k in info["metrics"] if k in METRIC_COL]
        orgs = info["orgs"]
        gset = gaps(y, m) if cur else {"groups": []}
        gap_sets = {(y, m): gset}
        for b in blocks:
            kind = b.get("kind")
            res = {"id": b["id"], "kind": kind, "missing": False}
            codes = {o["code"] for o in orgs}
            SCOPE_ORG.set(b.get("scope_org") if b.get("scope_org") in codes else None)                                  # 블록에서 고른 범위: 지표 행의 값을 이 조직으로
            SCOPE_ORGS.set([c for c in (b.get("scope_orgs") or []) if c in codes])                                       # 조직 행을 이 조직들로만
            UNIT.set(b.get("unit") if b.get("unit") in UNIT_DIV else "억원")
            # 기간 규칙(첫 월)
            by, bm = y, m
            if b.get("period"):
                ms = periods.resolve(Period.model_validate(b["period"]), (y, m))
                by, bm = ms[-1] if b["period"].get("rule") == "recent_n" else ms[0]
            bmeta = {"year": by, "month": bm}
            if (by, bm) not in gap_sets:
                gap_sets[(by, bm)] = gaps(by, bm)
            cause = _accepted_causes(gap_sets[(by, bm)], by, bm)
            if by != y or bm != m:
                res["period"] = f"{by}.{bm:02d}"
            if b.get("sql"):                                        # 지표 사전·자연어 질의로 확정한 SQL(FR-RPT-005·006): 기준 월 인자로 다시 실행
                res.update(_sql_block(con, info, bmeta, b["sql"], b.get("scenario")))
            elif kind in ("title", "paragraph", "footnote"):
                txt = retime(str(b.get("text") or ""), orig, bmeta)
                if kind == "paragraph" and "{수치}" in txt:
                    txt, miss = _fill_sentence(con, info, bmeta, txt, metrics)
                    res["missing"] = miss
                elif kind == "footnote" and "{수치}" in txt:
                    g0 = gset["groups"][0] if gset["groups"] else None
                    if g0 and "Gap" in txt and "구성" in txt and txt.count("{수치}") == 1:
                        txt = txt.replace("{수치}", eok(g0["total_won"], diff=True) + "억")
                    else:
                        txt, res["missing"] = txt.replace("{수치}", "[값]"), True
                res["text"] = txt
            elif kind == "table" and b.get("spec"):
                res["rows"], res["missing"] = fill_spec_table(con, info, bmeta, b["spec"], cause, b.get("guess"))
                res["spec"] = b["spec"]
            elif kind == "spec_table":
                res["rows"], res["missing"] = fill_spec_table(con, info, bmeta, b.get("spec") or {"rows": [], "cols": []}, cause, b.get("guess"))
                res["spec"] = b.get("spec")
            elif kind == "chart":
                cats = [str(c) for c in (b.get("categories") or [])]
                ms = [int(m.group(1)) for m in (re.fullmatch(r"\s*(\d{1,2})월\s*", c) for c in cats) if m and 1 <= int(m.group(1)) <= 12]
                known = [k for k in metrics if k in METRIC_COL]
                metric = b.get("metric") or _metric_in(" ".join([str(b.get("title") or "")] + [str(x) for x in (b.get("series") or [])] + cats), known)      # 지표를 글자에서 못 찾으면 '데이터 없음'(영업이익 같은 기본값으로 메우지 않는다)
                month_axis = len(ms) >= 2 and len(ms) == len(cats) and not b.get("months")                                                                # 축이 월(6월…11월)이면 그 월의 추이
                res["rows"], res["missing"] = _chart(con, info, bmeta, metric, b.get("limit") or 10, "line" if month_axis else (b.get("chart_type") or "bar"), b.get("scope"), b.get("months_n"), ms if month_axis else b.get("months"))
                res["chart_type"] = b.get("chart_type") or "bar"
            elif kind == "numbercard":
                res["cards"], res["missing"] = _numbercards(con, info, bmeta, b.get("cards") or [], metrics)
            elif kind == "explist":
                res["rows"], res["missing"] = _explist(gset)
            elif kind == "query":
                r = query_exec(b.get("qspec") or {}, bmeta)
                res["text"], res["table"], res["missing"] = r["text"], r.get("table"), "데이터 없음" in r["text"]
            elif kind == "table":
                res["rows"], res["missing"] = _fill_table(con, info, bmeta, b, metrics, orgs, cause, orig)
                res["spec"] = derive_spec(b.get("rows") or [], int(b.get("header_rows") or 0), metrics, orgs, b.get("header_spans"))
            elif kind == "breakdown":
                res["rows"], res["missing"] = _breakdown(con, info, bmeta, b.get("metric") or "영업비용")
            elif kind == "metric_table":
                res["rows"], res["missing"] = _metric_table(con, info, bmeta, b.get("metrics") or [], b.get("compare") or [])
            if isinstance(res.get("rows"), list) and kind in ("table", "spec_table"):
                res["header_rows"] = sum(1 for r in res["rows"] if r and isinstance(r[0], dict) and r[0].get("hdr"))       # 머리 줄 수(병합 머리는 2줄 이상)
            if UNIT.get() != "억원" and isinstance(res.get("rows"), list) and kind in ("table", "spec_table", "breakdown", "metric_table"):
                for r in res["rows"][:2]:
                    for c in r:
                        if isinstance(c, dict) and isinstance(c.get("text"), str):
                            c["text"] = c["text"].replace("억원", UNIT.get())
                res["unit"] = UNIT.get()
            if res["missing"] and kind in ("table", "spec_table") and "rows" in res:
                res["empty"] = len(res["rows"]) > 1 and all(c.get("missing") for r in res["rows"][int(res.get("header_rows") or 1):] for c in r[1:])
            if res["missing"]:
                res["why"] = _why(kind, res, b, metrics, orgs)
            out["blocks"].append(res)
        out["provisional"] = out["state"] == "잠정"
        return exact_json_value(out)
    finally:
        con.close()


def _sql_block(con, info, base: dict, sql: str, scenario: str | None = None) -> dict:
    """확정 SQL을 블록 기준 월에 실행하고 금액 정밀도를 보존한다.

    Args:
        con: 읽기 전용 연결.
        info: backend 월·버전 목록.
        base: 블록 기준 연월.
        sql: 확정 SQL.
        scenario: 구분. 생략하면 ACTUAL.
    Returns:
        표·누락 상태. 소수 및 큰 정수 셀은 정확한 십진 문자열.
    Raises:
        DB·설정 오류를 전달한다. SqlRejected는 누락 이유로 반환한다.
    Side Effects:
        허용된 읽기 전용 SQL을 실행하며 파일을 만들지 않는다.
    """
    from ..chat import sqlguard
    from ..config import get_settings
    cur = next((v for v in info["months"] if v["year"] == base["year"] and v["month"] == base["month"]), None)
    if not cur:
        return {"table": None, "missing": True, "text": "데이터 없음"}
    params = {"version": cur["version"], "version_prev": db.previous_version(con, cur["version"]) or cur["version"],
              "base_year": base["year"], "base_month": base["month"], "scn": scenario or "ACTUAL"}
    s = get_settings()
    try:
        r = sqlguard.execute(con, sql, params, row_limit=s.chat_row_limit, timeout_s=s.chat_sql_timeout_seconds)
    except sqlguard.SqlRejected as e:
        return {"table": None, "missing": True, "text": f"SQL 을 실행할 수 없습니다({e.code})"}
    return {"table": {"columns": list(r.columns), "rows": exact_json_value(r.rows), "truncated": r.truncated},
            "missing": r.row_count == 0, "state": cur["state"]}


def _why(kind, res, b, metrics, orgs) -> list[str]:
    """'왜 못 채웠는가'를 코드가 만든다(5단계 ⓘ 표시용). 값은 만들지 않고 이유 글만 만든다."""
    out: list[str] = []
    mlist = ", ".join(sorted(metrics))
    if b.get("sql"):                                              # 확정 SQL 블록: 결과가 없거나 실행이 막힌 이유
        return [str(res.get("text") or "이 기준 월에 SQL 조회 결과가 없습니다")[:120]]
    if kind in ("paragraph", "footnote"):
        txt = str(b.get("text") or "")
        if not _metric_in(txt, metrics):
            out.append(f"문장에서 지표 이름({mlist})을 찾지 못해 어느 DB 값을 넣을지 정할 수 없는 자리입니다" + (" (Gap 합계 문장만 자동으로 계산합니다)" if kind == "footnote" else ""))
        else:
            out.append("비교 말(계획·전월·전년·추정)에 해당하는 값이 DB 에 없거나, 숫자 자리가 비교 말보다 많아 어느 값인지 정할 수 없는 자리입니다")
    elif kind in ("table", "spec_table", "breakdown", "metric_table"):
        rows = res.get("rows") or []
        hr = int(res.get("header_rows") or 1)
        heads = [" ".join(t for t in (str((rows[i][j] if j < len(rows[i]) else {}).get("text") or "") for i in range(min(hr, len(rows)))) if t) for j in range(max((len(r) for r in rows), default=0))]       # 열 이름 = 머리줄 글자를 위에서 아래로 이은 것(병합으로 가려진 칸은 빈 글자)
        no_row, no_cmp, no_cause = [], set(), False
        for r in rows[hr:]:
            cells = r[1:]
            if cells and all(c.get("missing") and c.get("text") in ("[값]", "데이터 없음") for c in cells) and (r[0].get("text") not in ("", None)):
                lab = str(r[0].get("text"))
                if not _metric_in(lab, metrics) and not match_org(lab, orgs) and all(c.get("text") in ("[값]", "데이터 없음") for c in cells):
                    no_row.append(lab)
                    continue
            for j, c in enumerate(cells, 1):
                if c.get("missing"):
                    if c.get("text") == "—":
                        no_cause = True
                    elif c.get("text") in ("데이터 없음", "[값]") and j < len(heads):
                        no_cmp.add(heads[j])
        if no_row:
            out.append(f"행 이름 ‘{'’, ‘'.join(no_row[:4])}’ 이(가) 지표 사전({mlist})·조직 목록에 없어 어느 값인지 정할 수 없습니다")
        if no_cmp:
            out.append(f"열 ‘{'’, ‘'.join(sorted(no_cmp)[:4])}’ 의 기준(계획·직전 월·전년 동월 등) 값이 조회 DB 에 없습니다")
        if no_cause:
            out.append("‘주요 원인’ 칸은 수락된 소명이 있는 조직만 채워집니다(소명이 없거나 검토 중)")
    elif kind == "chart":
        out.append("선택한 지표·기간·범위에 해당하는 값이 조회 DB 에 없습니다")
    elif kind == "numbercard":
        for c in res.get("cards") or []:
            if c.get("missing"):
                m = _metric_in(str(c.get("label") or ""), [k for k in metrics if k in METRIC_COL])
                out.append(f"카드 ‘{c.get('label')}’ 에서 지표를 찾지 못했습니다" if m is None else f"카드 ‘{c.get('label')}’ 의 값이 조회 DB 에 없습니다")
    elif kind == "explist":
        out.append("이 기준 월의 Gap·소명이 조회 DB 에 없습니다")
    elif kind == "query":
        out.append(str(res.get("text") or "")[:120])
    return out or ["조회 DB 에서 계산할 수 없는 값이 있습니다"]


def _fill_sentence(con, info, base, txt, metrics):
    """'영업이익은 {수치}으로 계획 {수치}, 전월 {수치}' 처럼 지표 이름과 비교 말(계획·전월·전년·추정)이 순서대로 나오는 문장을 채운다. 채울 수 없는 자리는 [값]."""
    metric = _metric_in(txt, metrics)
    slots = txt.count("{수치}")
    if not metric:
        return txt.replace("{수치}", "[값]"), True
    b = _bases(con, info, base["year"], base["month"], METRIC_COL[metric], SCOPE_ORG.get())
    words = [_cname(w) for w in _KEYWORD.findall(txt)]
    vals, missing = [], False
    for i in range(slots):
        if i == 0:
            v = b["현재"]
            vals.append(eok(v) + "억" if v is not None else "[값]")
        elif i - 1 < len(words) and b["현재"] is not None and b.get(words[i - 1]) is not None:
            vals.append(eok(b["현재"] - b[words[i - 1]], diff=True) + "억")
        else:
            vals.append("[값]")
        missing |= vals[-1] == "[값]"
    it = iter(vals)
    return re.sub(r"\{수치\}", lambda _: next(it), txt), missing


def _label_col(rows, hr, metrics, orgs) -> int:
    """표에서 조직·지표 이름이 가장 많이 들어 있는 열(앞 3열 중). 첫 열이 '구분(본부)' 같은 묶음 열이면 그 오른쪽 열이 라벨 열이다. 못 찾으면 0."""
    ncol = max((len(r) for r in rows), default=0)
    best, bj = 0, 0
    for j in range(min(3, ncol)):
        hits = 0
        for r in rows[hr:]:
            t = str(r[j].get("text") or "") if len(r) > j and r[j]["type"] == "text" else ""
            hits += bool(t and (match_org(t, orgs) or _metric_in(t, metrics)))
        if hits > bj:
            best, bj = j, hits
    return best


def _cell_text(row, j) -> str:
    return str(row[j].get("text") or "") if len(row) > j and row[j]["type"] == "text" else ""


_TOTAL = re.compile(r"^(소\s*계|합\s*계|총\s*계|계)(?=$|[\s(:：])")
SUMMABLE = ("current", "cmp", "cmpval", "gap")


def _row_kind(label: str) -> str | None:
    """행 이름이 소계/합계/총계/계 로 시작하면 subtotal/total('계획' 같은 말은 해당 없음)."""
    m = _TOTAL.match(str(label or "").strip())
    return None if not m else ("subtotal" if m.group(1).replace(" ", "") == "소계" else "total")


class _Acc:
    """소계·합계 누적기. 사업부(조직) 행의 값(원)만 더한다. 하나라도 값이 없거나, 범위 필터가 걸려 있거나, 조직이 아닌 행이 섞여 있으면 합계도 '데이터 없음'(일부만 더하지 않는다)."""

    def __init__(self):
        self.grp, self.all, self.bad, self.bad_grp = [], [], False, False

    def org(self, raws):
        self.grp.append(raws)
        self.all.append(raws)

    def other(self):
        self.bad = self.bad_grp = True

    def _sum(self, cols, rows, bad):
        out = []
        for k, c in enumerate(cols):
            if c["t"] in SUMMABLE and rows and not bad and not SCOPE_ORGS.get() and all(len(r) > k and r[k] is not None for r in rows):
                v = sum((Decimal(str(r[k])) for r in rows), Decimal(0))
                out.append({"text": eok(v, diff=c["t"] in ("cmp", "gap")), "won": _won(v), "missing": False, "sum": True})
            else:
                out.append({"text": "데이터 없음", "missing": True})
        return out

    def sub(self, cols):
        r = self._sum(cols, self.grp, self.bad_grp)
        self.grp, self.bad_grp = [], False
        return r

    def total(self, cols):
        return self._sum(cols, self.all, self.bad)


def _metric_for(col: dict, row_metric: str | None, row_is_metric: bool) -> str | None:
    """칸의 지표: 열에 지표가 있으면 그 지표(조직 행). 행 자체가 지표 행이면 행 지표(열 지표와 다르면 None: 섞어 채우지 않는다)."""
    cm = col.get("metric")
    if row_is_metric:
        return row_metric if cm in (None, row_metric) else None
    return cm or row_metric


def _calc(con, info, base, cache, col: dict, metric: str | None, org):
    """열 하나의 칸 값. 반환 (글자, 없음 여부, 원 단위 값 또는 None). 지표가 DB 에 없거나 값이 None 이면 '데이터 없음'(다른 지표로 대신 채우지 않는다)."""
    ct = col.get("t")
    if ct not in SUMMABLE or metric not in METRIC_COL:
        return "데이터 없음", True, None
    key = (metric, org or SCOPE_ORG.get())
    if key not in cache:
        cache[key] = _bases(con, info, base["year"], base["month"], METRIC_COL[metric], key[1])
    bs = cache[key]
    cur = bs["현재"]
    if ct == "current":
        v = cur
    elif ct == "cmpval":
        v = bs.get(col.get("name"))
    elif ct == "cmp":
        v = cur - bs[col["name"]] if cur is not None and bs.get(col.get("name")) is not None else None
    else:
        v = cur - bs["계획"] if cur is not None and bs["계획"] is not None else None
    if v is None:
        return "데이터 없음", True, None
    return eok(v, diff=ct in ("cmp", "gap")), False, v


def _hdr(text, **kw):
    return {"text": text, "hdr": True, **kw}


def _fill_table(con, info, base, b, metrics, orgs, cause, orig):
    rows = b.get("rows") or []
    hr = int(b.get("header_rows") or 0)
    ncol = max((len(r) for r in rows), default=0)
    lead = 0
    for r in rows:                                   # 숫자 칸이 없는 맨 위 줄들은 머리줄(두 줄짜리 머리 보정)
        if any(c.get("type") == "number" for c in r):
            break
        lead += 1
    hr = max(hr, min(lead, max(1, len(rows) - 1)))
    spans = b.get("header_spans")
    lay = TH.header_layout(rows, hr, spans)
    cols = derive_spec(rows, hr, metrics, orgs, spans)["cols"]
    lc = _label_col(rows, hr, metrics, orgs)
    extra = [x for x in (b.get("extra_cols") or []) if x in ("계획", "직전 월", "전년 동월", "직전 추정")]
    out, any_missing, cache, acc = [], False, {}, _Acc()
    for i in range(hr):                              # 머리: 병합(colspan/rowspan)까지 격자 그대로
        row = []
        for c in lay["cells"][i]:
            d = _hdr(retime(c["text"], orig, base) if not c.get("hide") else "")
            d.update({k: c[k] for k in ("colspan", "rowspan", "hide") if k in c})
            row.append(d)
        for x in extra:
            row.append(_hdr(f"{x} 대비", **({"rowspan": hr} if i == 0 and hr > 1 else {}), **({"hide": True} if i else {})))
        out.append(row)
    for row in rows[hr:]:
        label = _cell_text(row, lc).replace("{수치}", "[값]") if not match_org(_cell_text(row, lc), orgs) else _cell_text(row, lc)
        lead = [_cell_text(row, j).replace("{수치}", "[값]") for j in range(lc)]       # 라벨 열 왼쪽의 묶음 열(본부A 같은 구분)은 글자 그대로
        rk = next((k for k in (_row_kind(t) for t in lead + [label]) if k), None)
        if rk:                                       # 소계·합계: 같은 열의 사업부 행 값을 코드가 더한다
            sums = acc.sub(cols) if rk == "subtotal" else acc.total(cols)
            sums = sums[:ncol - 1 - lc] + [{"text": "데이터 없음", "missing": True}] * max(0, ncol - 1 - lc - len(sums))
            out.append([{"text": t} for t in lead] + [{"text": label}] + sums + [{"text": "데이터 없음", "missing": True} for _ in extra])
            any_missing |= any(c["missing"] for c in sums) or bool(extra)
            continue
        org = match_org(label, orgs)
        if org and SCOPE_ORGS.get() and org not in SCOPE_ORGS.get():                       # 범위로 고른 조직이 아닌 조직 행은 뺀다
            continue
        metric = _metric_in(label, metrics)
        row_is_metric = bool(metric)
        if metric is None and org:
            metric = "영업이익"
        gm = None
        if metric is None and (b.get("guess") or {}).get(label) in METRIC_COL:           # AI 가 추측한 지표(5단계 ⓘ 로 추측임을 표시)
            metric = gm = b["guess"][label]
            row_is_metric = True
        resolved = bool(org or row_is_metric)
        cells, raws = [{"text": t} for t in lead] + [{"text": label, **({"guess": gm} if gm else {})}], []
        for j in range(lc + 1, ncol):
            c = row[j] if j < len(row) else {"type": "number"}
            col = cols[j - lc - 1] if j - lc - 1 < len(cols) else {"t": "unknown"}
            raw = None
            if col["t"] == "cause" and resolved:
                t = cause.get(org) if org else None
                txt, miss = (t, False) if t else ("—", True)
            elif resolved:
                txt, miss, raw = _calc(con, info, base, cache, col, _metric_for(col, metric, row_is_metric), org)
            elif c["type"] == "text":
                txt = str(c.get("text") or "")
                miss = "{수치}" in txt                                         # 가려 둔 숫자 자리가 화면에 그대로 새지 않게 [값] 으로
                txt = txt.replace("{수치}", "[값]")
            else:
                txt, miss = "[값]", True
            raws.append(raw)
            cells.append({"text": txt, "missing": miss, **({"won": _won(raw)} if raw is not None else {})})
            any_missing |= miss
        for x in extra:                                       # AI 편집으로 더한 '○○ 대비' 열: 같은 행의 현재 값과 비교 값의 차이(코드 계산)
            bs = cache.get((metric, org or SCOPE_ORG.get())) if resolved else None
            if resolved and bs is None and metric in METRIC_COL:
                bs = cache[(metric, org or SCOPE_ORG.get())] = _bases(con, info, base["year"], base["month"], METRIC_COL[metric], org or SCOPE_ORG.get())
            ok = bs is not None and bs["현재"] is not None and bs.get(x) is not None
            cells.append({"text": eok(bs["현재"] - bs[x], diff=True) if ok else "데이터 없음", "won": _won(bs["현재"] - bs[x]) if ok else None, "missing": not ok})
            any_missing |= not ok
        if org:
            acc.org(raws)
        else:
            acc.other()
        out.append(cells)
    return out, any_missing


def _breakdown(con, info, base, metric):
    """비용 상세(또는 매출 상세): 항목 계층 1단계(cat1)별 당월 실적과 계획 대비. fact 의 값을 합산한다."""
    y, m = base["year"], base["month"]
    ver = _best_version(info, y, m)
    if ver is None or metric not in ("영업비용", "매출"):
        return [[{"text": "항목"}, {"text": "당월"}, {"text": "계획比"}]], True
    q = ("SELECT cat1, SUM(CASE WHEN scenario='ACTUAL' THEN amount_won ELSE 0 END) a, SUM(CASE WHEN scenario='PLAN' THEN amount_won ELSE 0 END) p, "
         "SUM(CASE WHEN scenario='PLAN' THEN 1 ELSE 0 END) pn FROM fact WHERE version=? AND kind=? AND year=? AND month=? AND scenario IN ('ACTUAL','PLAN') GROUP BY cat1 ORDER BY ABS(SUM(CASE WHEN scenario='ACTUAL' THEN amount_won ELSE 0 END)) DESC LIMIT 6")
    rows = con.execute(q, (ver, metric, y, m)).fetchall()
    out = [[{"text": f"{metric} 상세 (억원)"}, {"text": f"{m}월"}, {"text": "계획比"}]]
    for r in rows:
        out.append([{"text": r["cat1"] or "(분류 없음)"}, {"text": eok(r["a"]), "won": _won(r["a"]), "missing": False},
                    {"text": eok(r["a"] - r["p"], diff=True) if r["pn"] else "데이터 없음", "won": _won(r["a"] - r["p"]) if r["pn"] else None, "missing": not r["pn"]}])
    return out, not rows


# ===================================================================== 5단계: 보고 문장(OpenAI)
@amount_precision()
def build_facts(base: dict, assign: dict, gi: int = 0) -> list[dict]:
    """보고서용 뷰의 숫자와 수락 소명으로 문장 작성 근거를 구성한다.

    Args:
        base: 기준 year·month.
        assign: 이전 개발 화면 호환 인자. 분류는 백엔드가 담당하므로 여기서는 사용하지 않는다.
        gi: 표시할 Gap 비교 그룹 번호.
    Returns:
        확인된 지표·수락 소명의 id·kind·text 목록.
    Raises:
        DB 조회 오류는 호출부에 전달한다.
    Side Effects:
        보고서용 뷰를 읽는다. 분류·원인별 금액 배분·업무 데이터 저장을 하지 않는다.
    """
    info = months()
    y, m = base["year"], base["month"]
    cur = next((v for v in info["months"] if v["year"] == y and v["month"] == m), None)
    if not info["db_ready"] or cur is None:
        return []
    con = _con()
    facts: list[dict] = []
    try:
        names = {"매출": ("매출", "매출"), "영업비용": ("비용", "영업비용"), "영업이익": ("영업이익", "영업이익")}
        for metric in [k for k in ("매출", "영업비용", "영업이익") if k in info["metrics"]]:
            bs = _bases(con, info, y, m, METRIC_COL[metric], None)
            if bs["현재"] is None:
                continue
            kind, label = names[metric]
            parts = []
            for nm in ("계획", "직전 월", "전년 동월"):
                if bs[nm] is not None:
                    parts.append(f"{nm} 대비 {eok(bs['현재'] - bs[nm], diff=True)}억원")
            facts.append({"kind": kind, "text": f"{y}년 {m}월 전사 {label} 실적은 {eok(bs['현재'])}억원" + ("이며, " + ", ".join(parts) + "입니다." if parts else "입니다.")})
        g = gaps(y, m)
        grp = g["groups"][gi] if g["groups"] and 0 <= gi < len(g["groups"]) else None
        if grp:
            explained = sorted([c for c in grp["cards"] if c["explained"]], key=lambda c: -abs(c["diff_won"]))[:2]
            for c in explained:
                facts.append({"kind": "소명", "text": f"{c['org_name']}의 {c['item']} {grp['basis_label']} Gap은 {eok(c['diff_won'], diff=True)}억원이며, 수락된 소명 원문은 \"{c['summary']}\"입니다."})
    finally:
        con.close()
    return [{"id": f"F{i}", **f} for i, f in enumerate(facts, 1)]


def ai_status_ko(result: dict, limit: int) -> dict:
    """생성 결과에서 'AI 문장이 쓰였는지, 안 됐다면 이유'를 사람이 읽는 말로. 키 값은 다루지 않는다."""
    sents = result.get("sentences") or []
    if any(s.get("source") == "ai" for s in sents):
        replaced = [s for s in sents if s.get("source") == "template" and s.get("reason") and "FILL" not in s["reason"]]
        return {"ok": True, "code": "ok", "message": "AI 문장 생성 성공" + (f" (숫자 검증 실패로 기본 문구로 대체된 문장 {len(replaced)}개)" if replaced else "")}
    reason = (sents[0].get("reason") if sents else None) or ""
    err = result.get("error") or ""
    if "NO_API_KEY" in reason:
        return {"ok": False, "code": "NO_API_KEY", "message": "키 없음 — ai/.env 에 OPENAI_API_KEY 를 넣고 서버를 재시작하세요. 기본 문구로 대체했습니다."}
    if "DAILY_LIMIT_EXCEEDED" in reason:
        return {"ok": False, "code": "DAILY_LIMIT", "message": f"오늘 AI 호출 한도({limit}회)에 도달했습니다. 기본 문구로 대체했습니다."}
    if reason.startswith("AI_CALL_FAILED") or err:
        e = err or reason.split(":", 1)[-1]
        if "Connection" in e or "Timeout" in e:
            return {"ok": False, "code": "NETWORK", "message": f"네트워크 연결 실패({e}). 기본 문구로 대체했습니다."}
        if "Authentication" in e or "401" in e or "PermissionDenied" in e:
            return {"ok": False, "code": "AUTH", "message": f"키가 거절되었습니다({e}). 키를 확인하세요. 기본 문구로 대체했습니다."}
        if "RateLimit" in e or "429" in e:
            return {"ok": False, "code": "RATE_LIMIT", "message": f"OpenAI 사용 한도/속도 제한({e}). 기본 문구로 대체했습니다."}
        return {"ok": False, "code": "CALL_FAILED", "message": f"AI 호출 실패({e}). 기본 문구로 대체했습니다."}
    return {"ok": False, "code": "VALIDATION", "message": f"AI 문장이 숫자·금지어 검증을 통과하지 못해 모두 기본 문구로 대체했습니다({reason or '사유 없음'})."}


def generate_sentences(base: dict, assign: dict, gi: int = 0, force: bool = False) -> dict:
    from ..config import get_settings
    from ..services import key_messages
    facts = build_facts(base, assign, gi)
    if not facts:
        return {"facts": [], "sentences": [], "usage": {"input_tokens": 0, "output_tokens": 0, "cached": False},
                "status": {"ok": False, "code": "NO_DATA", "message": "문장을 만들 데이터가 없습니다(기준 월 데이터 없음)."}}
    cur = next(v for v in months()["months"] if v["year"] == base["year"] and v["month"] == base["month"])
    r = key_messages.generate(facts, "final" if cur["kind"] in ("master", "close") else "provisional", force=force)
    return {"facts": facts, "sentences": r["sentences"], "usage": r["usage"], "model": r["model"], "status": ai_status_ko(r, get_settings().ai_daily_call_limit)}


def _metric_table(con, info, base, metrics, compare):
    """지표별 표(AI 편집으로 추가): 행=지표, 열=당월 실적 + 선택한 비교 기준과의 차이. 지표 사전에 없는 이름은 호출 전에 막는다."""
    y, m = base["year"], base["month"]
    comp = [c for c in compare if c in ("계획", "직전 월", "전년 동월", "직전 추정")]
    out = [[{"text": "지표 (억원)"}, {"text": f"{m}월"}] + [{"text": f"{c} 대비"} for c in comp]]
    miss = False
    for mt in metrics:
        if mt not in METRIC_COL:
            continue
        bs = _bases(con, info, y, m, METRIC_COL[mt], None)
        row = [{"text": mt}, {"text": eok(bs["현재"]) if bs["현재"] is not None else "데이터 없음", "won": _won(bs["현재"]), "missing": bs["현재"] is None}]
        for c in comp:
            ok = bs["현재"] is not None and bs.get(c) is not None
            row.append({"text": eok(bs["현재"] - bs[c], diff=True) if ok else "데이터 없음", "won": _won(bs["현재"] - bs[c]) if ok else None, "missing": not ok})
        miss |= any(c.get("missing") for c in row[1:])
        out.append(row)
    return out, miss


# ===================================================================== 조회형 질문(코드가 DB 로 답한다)
def _tbl(headers, rows):
    return {"headers": headers, "rows": rows}


def q_value(base, metric, org, compare):
    info = months()
    con = _con()
    try:
        y, m = base["year"], base["month"]
        bs = _bases(con, info, y, m, METRIC_COL[metric], org)
        who = next((o["name"] for o in info["orgs"] if o["code"] == org), None) or "전사"
        if bs["현재"] is None:
            return {"text": f"{y}년 {m}월 {who} {metric}: 데이터 없음(조회 DB에 값이 없습니다)."}
        text = f"{y}년 {m}월 {who} {metric} 실적은 {eok(bs['현재'])}억원입니다."
        comps = [compare] if compare else ["계획", "직전 월", "전년 동월"]
        parts = []
        for c in comps:
            v = bs.get(c)
            parts.append(f"{c} 대비 {eok(bs['현재'] - v, diff=True)}억원" if v is not None else f"{c} 대비 데이터 없음")
        return {"text": text + " " + ", ".join(parts) + "."}
    finally:
        con.close()


def q_org_rank(base, metric, order, limit):
    info = months()
    ver = _best_version(info, base["year"], base["month"])
    con = _con()
    try:
        col = METRIC_COL[metric]
        rows = con.execute(f"SELECT org_code, org_name, SUM({col}) s FROM v_op WHERE version=? AND year=? AND month=? AND scenario='ACTUAL' GROUP BY org_code, org_name "
                           f"ORDER BY s {'ASC' if order == 'asc' else 'DESC'}, org_code" + (" LIMIT ?" if limit else ""), (ver, base["year"], base["month"]) + ((int(limit),) if limit else ())).fetchall() if ver else []
        if not rows:
            return {"text": f"{base['year']}년 {base['month']}월 조직별 {metric}: 데이터 없음."}
        return {"text": f"{base['year']}년 {base['month']}월 조직별 {metric} 실적 순위(억원, {'낮은' if order == 'asc' else '높은'} 순)입니다.",
                "table": _tbl(["순위", "조직", f"{metric}(억원)"], [[str(i), r["org_name"], eok(r["s"])] for i, r in enumerate(rows, 1)])}
    finally:
        con.close()


def q_gap_orgs(base):
    """백엔드 Gap 대상 조직과 보고 항목을 표시한다.

    Args:
        base: 기준 year·month.
    Returns:
        중복 제거한 조직 수와 항목별 차이·소명 표.
    Raises:
        DB 조회 오류는 호출부에 전달한다.
    Side Effects:
        보고서용 뷰 조회만 수행한다.
    """
    result = gaps(base["year"], base["month"])
    if not result["groups"]:
        return {"text": f"{base['year']}년 {base['month']}월에는 Gap 데이터가 없습니다."}
    cards = [(group, card) for group in result["groups"] for card in group["cards"]]
    org_count = len({card["org_code"] for _, card in cards})
    return {"text": f"{base['year']}년 {base['month']}월 보고 항목 Gap이 있는 조직은 {org_count}곳입니다(비교 기준별 표시).",
            "table": _tbl(["조직", "항목", "구분", "비교", "Gap(억원)", "소명"],
                          [[c["org_name"], c["item"], group["scenario"], group["basis_label"], eok(c["diff_won"], diff=True), "수락됨" if c["explained"] else "검토 중/없음"]
                           for group, c in sorted(cards, key=lambda pair: -abs(pair[1]["diff_won"]))])}


GAP_KIND_LABEL = {("ITEM_GAP", "PLAN"): "계획 대비 보고 항목 Gap", ("ITEM_GAP", "PREVIOUS"): "직전 추정 대비 보고 항목 Gap"}


def _gap_label(gt, basis):
    return GAP_KIND_LABEL.get((gt, basis)) or f"{gt} · {basis}"


def q_gap_count(base):
    """Gap 전체 건수와 종류별(유형·비교 기준) 건수. 모두 DB 의 gap 표를 코드가 센다."""
    info = months()
    ver = _best_version(info, base["year"], base["month"])
    con = _con()
    try:
        rows = con.execute("SELECT gap_type, basis, COUNT(*) n FROM gap WHERE version=? GROUP BY gap_type, basis ORDER BY n DESC, gap_type, basis", (ver,)).fetchall() if ver else []
        if not rows:
            return {"text": "Gap 데이터가 없습니다(조회 DB 에 gap 이 적재되지 않았습니다)."}
        total = sum(r["n"] for r in rows)
        parts = ", ".join(f"{_gap_label(r['gap_type'], r['basis'])} {r['n']}건" for r in rows)
        return {"text": f"소명 대상 Gap은 모두 {total}건입니다({parts}).",
                "table": _tbl(["종류", "건수"], [[_gap_label(r["gap_type"], r["basis"]), str(r["n"])] for r in rows] + [["합계", str(total)]])}
    finally:
        con.close()


def q_org_gap_count(base, limit=None):
    info = months()
    ver = _best_version(info, base["year"], base["month"])
    con = _con()
    try:
        rows = con.execute("SELECT org_code, org_name, COUNT(*) n FROM gap WHERE version=? GROUP BY org_code, org_name ORDER BY n DESC, org_code" + (" LIMIT ?" if limit else ""), (ver,) + ((int(limit),) if limit else ())).fetchall() if ver else []
        if not rows:
            return {"text": "Gap 데이터가 없습니다."}
        tot = con.execute("SELECT COUNT(*) FROM gap WHERE version=?", (ver,)).fetchone()[0]
        return {"text": f"조직별 Gap 건수입니다(전체 {tot}건, 건수 많은 순, 같으면 조직 코드 순).",
                "table": _tbl(["순위", "조직", "Gap 건수"], [[str(i), r["org_name"], str(r["n"])] for i, r in enumerate(rows, 1)])}
    finally:
        con.close()


def q_explanation_count(base: dict) -> dict:
    """소명 이력을 검토 항목별 최신 회차로 묶어 상태별 건수를 표시한다.

    Args:
        base: 조회할 year·month.
    Returns:
        검토 항목 단위 소명 건수와 Gap 비교 항목 연결 수.
    Raises:
        DB 조회 오류는 호출부에 전달한다.
    Side Effects:
        보고서용 뷰만 조회한다. 소명 회차·상태를 변경하지 않는다.
    """
    info = months()
    ver = _best_version(info, base["year"], base["month"])
    con = _con()
    try:
        rows = con.execute("WITH latest AS (SELECT status, ROW_NUMBER() OVER (PARTITION BY gap_key ORDER BY rounds DESC, explanation_id DESC) rn "
                           "FROM explanation WHERE version=?) SELECT COALESCE(status,'상태 없음') st, COUNT(*) n "
                           "FROM latest WHERE rn=1 GROUP BY status ORDER BY n DESC, st", (ver,)).fetchall() if ver else []
        if not rows:
            return {"text": "소명 데이터가 없습니다."}
        total = sum(r["n"] for r in rows)
        ng = con.execute("SELECT COUNT(*) FROM gap WHERE version=?", (ver,)).fetchone()[0]
        return {"text": f"소명은 검토 항목 기준 모두 {total}건입니다(" + ", ".join(f"{r['st']} {r['n']}건" for r in rows) + f"). Gap 비교 {ng}건 중 소명이 연결된 항목은 {ng - len(_unlinked(con, ver))}건입니다.",
                "table": _tbl(["상태", "건수"], [[r["st"], str(r["n"])] for r in rows] + [["합계", str(total)]])}
    finally:
        con.close()


def _unlinked(con: db.Conn, ver: str) -> list:
    """소명 gap_key가 연결되지 않은 Gap 비교 항목을 조회한다.

    Args:
        con: 읽기 전용 DB 연결.
        ver: 조회 버전.
    Returns:
        소명이 없는 Gap 식별자 행 목록.
    Raises:
        DB 조회 오류는 호출부에 전달한다.
    Side Effects:
        읽기 전용 SQL 한 번을 실행한다.
    """
    return con.execute("SELECT g.gap_id FROM gap g WHERE g.version=? AND NOT EXISTS "
                       "(SELECT 1 FROM explanation e WHERE e.version=g.version AND g.gap_id=e.gap_key || '-' || g.basis)", (ver,)).fetchall()


def q_cost_top(base, limit=None, org=None, metric="영업비용"):
    """비용(또는 매출) 상위 소분류 항목. 조직을 주면 그 조직만."""
    info = months()
    ver = _best_version(info, base["year"], base["month"])
    con = _con()
    try:
        n = int(limit) if limit else 10
        sql = "SELECT COALESCE(NULLIF(cat2,''), NULLIF(cat1,''), item) nm, SUM(amount_won) s FROM fact WHERE version=? AND kind=? AND year=? AND month=? AND scenario IN ('ACTUAL','ESTIMATE')" + (" AND org_code=?" if org else "") + " GROUP BY nm ORDER BY s DESC LIMIT ?"
        rows = con.execute(sql, (ver, metric, base["year"], base["month"]) + ((org,) if org else ()) + (n,)).fetchall() if ver else []
        if not rows:
            return {"text": f"{base['year']}년 {base['month']}월 {metric} 항목: 데이터 없음."}
        who = next((o["name"] for o in info["orgs"] if o["code"] == org), None) or "전사"
        return {"text": f"{base['year']}년 {base['month']}월 {who} {metric} 상위 {len(rows)}개 항목입니다(소분류 기준, 억원).",
                "table": _tbl(["순위", "항목", "금액(억원)"], [[str(i), r["nm"], eok(r["s"])] for i, r in enumerate(rows, 1)])}
    finally:
        con.close()


def q_monthly(base, metric, org=None):
    """한 지표의 월별 추이(실적 1~현재월, 이후는 추정). 기준 연도의 DB 값만 쓴다."""
    info = months()
    con = _con()
    try:
        col = METRIC_COL[metric]
        vers = [m["version"] for m in info["months"] if m["year"] == base["year"]]
        ver = _best_version(info, base["year"], base["month"])
        rows = con.execute(f"SELECT month, scenario, SUM({col}) s FROM v_op WHERE version=? AND year=? AND scenario IN ('ACTUAL','ESTIMATE')" + (" AND org_code=?" if org else "") + " GROUP BY month, scenario ORDER BY month", (ver, base["year"]) + ((org,) if org else ())).fetchall() if ver else []
        if not rows:
            return {"text": f"{base['year']}년 {metric} 월별 추이: 데이터 없음."}
        who = next((o["name"] for o in info["orgs"] if o["code"] == org), None) or "전사"
        return {"text": f"{base['year']}년 {who} {metric} 월별 추이입니다(억원, 추정은 표시).",
                "table": _tbl(["월", f"{metric}(억원)", "구분"], [[f"{r['month']}월", eok(r["s"]), "추정" if r["scenario"] == "ESTIMATE" else "실적"] for r in rows])}
    finally:
        con.close()


def q_org_explanation(base, org_code):
    info = months()
    ver = _best_version(info, base["year"], base["month"])
    con = _con()
    try:
        name = next((o["name"] for o in info["orgs"] if o["code"] == org_code), org_code)
        rows = con.execute("SELECT item, kind, status, answer FROM explanation WHERE version=? AND org_code=? AND year=? AND month=? ORDER BY explanation_id", (ver, org_code, base["year"], base["month"])).fetchall() if ver else []
        if not rows:
            return {"text": f"{name}: {base['year']}년 {base['month']}월 소명이 없습니다."}
        return {"text": f"{name}의 {base['year']}년 {base['month']}월 소명 {len(rows)}건입니다(수락된 건만 원문이 있습니다).",
                "table": _tbl(["항목", "상태", "소명 원문"], [[r["item"] or "", r["status"] or "", r["answer"] or "(수락 전이라 원문 없음)"] for r in rows])}
    finally:
        con.close()


def q_unexplained(base: dict) -> dict:
    """모든 비교 기준에서 아직 소명이 수락되지 않은 Gap 항목을 표시한다.

    Args:
        base: 조회할 year·month.
    Returns:
        현재 실적·다음 월 추정의 미수락 Gap 비교 항목 표.
    Raises:
        DB 조회 오류는 호출부에 전달한다.
    Side Effects:
        보고서용 뷰를 조회한다. 소명 상태를 변경하지 않는다.
    """
    result = gaps(base["year"], base["month"])
    if not result["groups"]:
        return {"text": f"{base['year']}년 {base['month']}월에는 Gap 데이터가 없습니다."}
    cards = [(group, card) for group in result["groups"] for card in group["cards"]]
    missing = [(group, card) for group, card in cards if not card["explained"]]
    if not missing:
        return {"text": f"소명이 없는 Gap 항목은 없습니다(Gap 비교 {len(cards)}건 모두 소명이 수락됨)."}
    return {"text": f"소명이 없는(수락되지 않은) Gap 비교 항목 {len(missing)}건입니다.",
            "table": _tbl(["조직", "항목", "구분", "비교", "Gap(억원)", "소명 회차 수"],
                          [[card["org_name"], card["item"], group["scenario"], group["basis_label"], eok(card["diff_won"], diff=True), str(card["explanation_count"])]
                           for group, card in missing])}


# ===================================================================== 표 블록 편집(행·열 스펙) · 차트 · 소명 목록 · 조회 블록
COL_KINDS = ("current", "cmp", "cmpval", "gap", "cause", "unknown")


def _classify(h: str, has_num: bool, explicit_current: bool) -> dict | None:
    kw = _KEYWORD.search(h)
    if "원인" in h or "소명" in h or "사유" in h:
        return {"t": "cause"}
    if "Gap" in h or "차이" in h or "증감" in h:
        return {"t": "gap"}
    if "실적" in h or (not kw and has_num):
        return {"t": "current"}
    if kw:
        return {"t": "cmpval" if explicit_current else "cmp", "name": _cname(kw.group(0))}
    return None


def derive_spec(rows, hr, metrics, orgs, spans=None) -> dict:
    """PDF 에서 읽은 표를 편집 가능한 스펙(행=지표/조직/소계/합계, 열=종류+지표)으로 바꾼다. 알 수 없는 행·열은 unknown 으로 두어 '데이터 없음' 칸이 된다.
    머리가 여러 줄·병합이면 header_grid 로 열마다 경로(예: ['매출','계획'])를 만들고, 종류는 경로의 아랫칸, 지표는 경로의 글자에서 찾는다(키 metric: 없으면 행 지표를 쓰는 기존 동작)."""
    ncol = max((len(r) for r in rows), default=0)
    lead = 0
    for r in rows:
        if any(c.get("type") == "number" for c in r):
            break
        lead += 1
    hr = max(hr, min(lead, max(1, len(rows) - 1)))
    lay = TH.header_layout(rows, hr, spans)
    paths, amb = lay["paths"], lay["amb_cols"]
    lc = _label_col(rows, hr, metrics, orgs)
    explicit_current = any("실적" in " ".join(p) for p in paths)
    cols = []
    for j in range(lc + 1, ncol):
        path = paths[j] if j < len(paths) else []
        h_all = " ".join(path)
        body = [r for r in rows[hr:] if not _row_kind(str(r[0].get("text") or "") if r and r[0]["type"] == "text" else "")]      # 소계·합계 행은 빈 칸이 많아 판정에서 뺀다
        has_num = bool(body) and sum(1 for r in body if len(r) > j and r[j]["type"] == "number") * 2 >= len(body)        # 절반 이상이 숫자 칸일 때만 숫자 열(글자 열에 null 이 섞여도 숫자 열로 오판하지 않는다)
        if j in amb:                                         # 병합 범위를 모르는 머리 칸: 어느 지표 아래 열인지 추측하지 않는다(값 대신 '데이터 없음')
            cols.append({"t": "unknown", "label": h_all or "열", "ambiguous": True})
            continue
        col = _classify(path[-1] if path else "", has_num, explicit_current)
        if col is None and len(path) > 1:
            col = _classify(h_all, has_num, explicit_current)
        if col is None:
            cols.append({"t": "unknown", "label": h_all or "열"})
            continue
        mt = _metric_in(h_all, metrics)
        if mt and col["t"] != "cause":
            col["metric"], col["label"] = mt, (path[-1] if path else "")
        cols.append(col)
    out_rows = []
    for r in rows[hr:]:
        label = _cell_text(r, lc)
        lead = [_cell_text(r, j) for j in range(lc)]
        rk = next((k for k in (_row_kind(t) for t in lead + [label]) if k), None)
        if rk:
            out_rows.append({"t": rk, "label": label or next((t for t in lead if t), ""), **({"texts": lead + [label]} if lc else {})})
            continue
        org = match_org(label, orgs)
        mt = _metric_in(label, metrics)
        row = {"t": "org", "code": org} if org else ({"t": "metric", "metric": mt} if mt else {"t": "unknown", "label": label})
        if lc:
            row["lead"] = lead
        out_rows.append(row)
    corner = str(rows[0][0].get("text") or "") if rows and hr and rows[0] and rows[0][0]["type"] == "text" else ""
    spec = {"corner": corner, "rows": out_rows, "cols": cols}
    if lc:                                                       # 라벨 열 왼쪽의 묶음 열(구분 등): 머리 글자와 행별 글자를 그대로 가진다
        spec["lead_heads"] = [" ".join(paths[j]) if j < len(paths) else "" for j in range(lc)]
        spec["corner"] = " ".join(paths[lc]) if lc < len(paths) else ""
    return spec


def _col_label(c, base):
    t = c.get("t")
    if c.get("metric") and c.get("label"):
        return str(c["label"])                               # 지표 묶음 아래의 열 이름은 PDF 에 적힌 글자(계획·실적·차이)
    if t == "current":
        return f"{base['month']}월"
    if t == "cmp":
        return f"{c.get('name')} 대비"
    if t == "cmpval":
        return str(c.get("name"))
    if t == "gap":
        return "Gap"
    if t == "cause":
        return "주요 원인"
    return str(c.get("label") or "열")


def _spec_header(spec, cols, base):
    """열에 지표가 있으면 머리를 두 줄(지표 묶음 colspan / 열 이름)로, 없으면 한 줄로."""
    corner = spec.get("corner") or ""
    leads = spec.get("lead_heads") or []
    if not any(c.get("metric") for c in cols):
        return [[_hdr(h) for h in leads] + [_hdr(corner)] + [_hdr(_col_label(c, base)) for c in cols]]
    top, bot, j = [_hdr(h, rowspan=2) for h in leads] + [_hdr(corner, rowspan=2)], [_hdr("", hide=True) for _ in leads] + [_hdr("", hide=True)], 0
    while j < len(cols):
        c = cols[j]
        mt = c.get("metric")
        if not mt:
            top.append(_hdr(_col_label({**c, "metric": None}, base), rowspan=2))
            bot.append(_hdr("", hide=True))
            j += 1
            continue
        k = j
        while k + 1 < len(cols) and cols[k + 1].get("metric") == mt:
            k += 1
        top.append(_hdr(mt, **({"colspan": k - j + 1} if k > j else {})))
        top += [_hdr("", hide=True) for _ in range(j + 1, k + 1)]
        bot += [_hdr(_col_label(cols[x], base)) for x in range(j, k + 1)]
        j = k + 1
    return [top, bot]


def fill_spec_table(con, info, base, spec, cause, guess=None):
    """스펙대로 표를 그린다. 칸 값은 전부 조회 DB 에서 코드가 계산한 값이고, 계산할 수 없으면 '데이터 없음'. 열에 지표가 있으면 그 지표로 계산하고, 소계·합계는 사업부 행 값을 더한다."""
    orgs = info["orgs"]
    cols = [c for c in (spec.get("cols") or []) if c.get("t") in COL_KINDS]
    out, miss_any, cache, acc = list(_spec_header(spec, cols, base)), False, {}, _Acc()
    for r in spec.get("rows") or []:
        t = r.get("t")
        if t in ("subtotal", "total"):
            sums = acc.sub(cols) if t == "subtotal" else acc.total(cols)
            texts = r.get("texts") or [r.get("label") or ("소계" if t == "subtotal" else "합계")]
            out.append([{"text": x} for x in texts] + sums)
            miss_any |= any(c["missing"] for c in sums)
            continue
        org = r.get("code") if t == "org" and any(o["code"] == r.get("code") for o in orgs) else None
        if org and SCOPE_ORGS.get() and org not in SCOPE_ORGS.get():
            continue
        row_is_metric = t == "metric" and r.get("metric") in METRIC_COL
        metric = r.get("metric") if row_is_metric else ("영업이익" if org else None)
        label = (next((o["name"] for o in orgs if o["code"] == org), "") if org else (r.get("metric") if t == "metric" else r.get("label"))) or "—"
        gm = None
        if metric is None and (guess or {}).get(r.get("label")) in METRIC_COL:           # AI 가 추측한 지표
            metric = gm = guess[r.get("label")]
            row_is_metric = True
        resolved = bool(org or row_is_metric)
        cells, raws = [{"text": str(x)} for x in (r.get("lead") or [])] + [{"text": str(label), **({"guess": gm} if gm else {})}], []
        for c in cols:
            raw = None
            if c["t"] == "cause":
                v = cause.get(org) if org else None
                txt, miss = (v, False) if (v and resolved) else ("—", True)
            elif resolved:
                txt, miss, raw = _calc(con, info, base, cache, c, _metric_for(c, metric, row_is_metric), org)
            else:
                txt, miss = "데이터 없음", True
            raws.append(raw)
            cells.append({"text": txt, "missing": miss})
            miss_any |= miss
        if org:
            acc.org(raws)
        else:
            acc.other()
        out.append(cells)
    return out, miss_any


def _chart(con, info, base, metric, limit, ctype="bar", scope=None, months_n=None, months=None):
    """보고서용 뷰의 값으로 차트 표시 행을 구성한다.

    Args:
        con: 읽기 전용 연결.
        info: 월·버전 정보.
        base: 기준 연월.
        metric: 허용된 지표.
        limit: 조직 표시 상한.
        ctype: bar·donut·line·waterfall.
        scope: 선택 조직 목록.
        months_n: 최근 월 수.
        months: 명시적으로 선택한 월 목록.
    Returns:
        정확한 won 문자열·반올림 표시를 가진 차트 행과 누락 여부.
    Raises:
        DB 조회·숫자 인자 오류를 전달한다.
    Side Effects:
        뷰 조회만 수행한다. 차트 표시 합계를 만들며 Gap을 판정하지 않는다.
    """
    ver = _best_version(info, base["year"], base["month"])
    if ver is None or metric not in METRIC_COL:
        return [], True
    col = METRIC_COL[metric]
    if ctype == "line":
        n = int(months_n or 12)
        sel = [int(x) for x in (months or []) if isinstance(x, int) and 1 <= x <= 12]
        rs = con.execute(f"SELECT month, SUM({col}) s FROM v_op WHERE version=? AND year=? AND scenario='ACTUAL' AND month<=?" + (" AND org_code IN (%s)" % ",".join("?" * len(scope)) if scope else "") + " GROUP BY month ORDER BY month",
                         (ver, base["year"], 12 if sel else base["month"]) + tuple(scope or ())).fetchall()
        rs = [r for r in rs if r["month"] in sel] if sel else rs[-n:]                # 사용자가 고른 월이 있으면 그 월만, 없으면 최근 N개월
        return [{"name": f"{r['month']}월", "won": _won(r["s"]), "text": eok(r["s"])} for r in rs], not rs
    if ctype == "waterfall":
        q = f"SELECT org_name, SUM(CASE WHEN scenario='ACTUAL' THEN {col} ELSE 0 END) a, SUM(CASE WHEN scenario='PLAN' THEN {col} ELSE 0 END) p FROM v_op WHERE version=? AND year=? AND month=? GROUP BY org_code, org_name"
        rs = con.execute(q, (ver, base["year"], base["month"])).fetchall()
        if not rs:
            return [], True
        pt, at = sum(r["p"] for r in rs), sum(r["a"] for r in rs)
        d = sorted(((r["a"] - r["p"], r["org_name"]) for r in rs), key=lambda x: -abs(x[0]))
        top, rest = d[: max(1, int(limit) if int(limit) < 8 else 5)], d[max(1, int(limit) if int(limit) < 8 else 5):]
        out = [{"name": "계획", "won": _won(pt), "text": eok(pt), "type": "total"}] + [{"name": n_, "won": _won(w), "text": eok(w, diff=True), "type": "delta"} for w, n_ in top]
        if rest:
            w = sum(x[0] for x in rest)
            out.append({"name": f"기타 {len(rest)}곳", "won": _won(w), "text": eok(w, diff=True), "type": "delta"})
        out.append({"name": "실적", "won": _won(at), "text": eok(at), "type": "total"})
        return out, False
    q = f"SELECT org_name, SUM({col}) s FROM v_op WHERE version=? AND year=? AND month=? AND scenario='ACTUAL'" + (" AND org_code IN (%s)" % ",".join("?" * len(scope)) if scope else "") + " GROUP BY org_code, org_name ORDER BY s DESC LIMIT ?"
    rows = con.execute(q, (ver, base["year"], base["month"]) + tuple(scope or ()) + (int(limit),)).fetchall()
    return [{"name": r["org_name"], "won": _won(r["s"]), "text": eok(r["s"])} for r in rows], not rows


def _numbercards(con, info, base, cards, metrics):
    """지표 카드의 표시값과 내보내기 원 금액을 함께 구성한다.

    Args:
        con: 읽기 전용 연결.
        info: 월·버전 정보.
        base: 기준 연월.
        cards: 지표 이름·비교 표기를 가진 카드.
        metrics: 허용된 지표 목록.
    Returns:
        value·unit·won·delta·missing 카드 목록과 누락 여부.
    Raises:
        DB 조회·숫자 변환 오류를 전달한다.
    Side Effects:
        보고서용 뷰를 읽는다. won은 반올림하지 않은 원 단위 문자열이며 파일은 쓰지 않는다.
    """
    out, miss = [], False
    for c in cards:
        label = str(c.get("label") or "")
        metric = _metric_in(label, [k for k in metrics if k in METRIC_COL])
        item = {"label": label, "unit": UNIT.get(), "delta_label": c.get("delta_label"), "value": None, "won": None, "delta": None, "missing": False}
        if metric is None:
            item["missing"] = True
            miss = True
            out.append(item)
            continue
        v = _bases(con, info, base["year"], base["month"], METRIC_COL[metric], SCOPE_ORG.get())
        cur = v["현재"]
        if cur is None:
            item["missing"] = True
            miss = True
        else:
            item["value"] = eok(cur)
            item["won"] = _won(cur)
        dl = str(c.get("delta_label") or "")
        key = next((name for word, name in COMPARE_WORDS if word in dl), None) if dl else None
        if key and cur is not None:
            bv = v.get(key)
            item["delta"] = (key + " 대비 " + eok(cur - bv, diff=True)) if bv is not None else (key + " 대비 데이터 없음")
        out.append(item)
    return out, miss


def _explist(gset):
    if not gset.get("groups"):
        return [[{"text": "조직"}, {"text": "Gap(억원)"}, {"text": "소명"}]], True
    g = gset["groups"][0]
    rows = [[{"text": "조직"}, {"text": f"Gap(억원, {g['basis_label']})"}, {"text": "소명 요약"}]]
    for c in sorted(g["cards"], key=lambda c: -abs(c["diff_won"])):
        rows.append([{"text": c["org_name"]}, {"text": eok(c["diff_won"], diff=True), "won": _won(c["diff_won"])}, {"text": c["summary"] or "소명 검토 중", "missing": not c["explained"]}])
    return rows, False


def query_exec(q: dict, base: dict) -> dict:
    """검증을 마친 조회 스펙(type, metric, org=코드, year, month, compare, order, limit)을 실행한다."""
    b = dict(base)
    if isinstance(q.get("year"), int):
        b["year"] = q["year"]
    if isinstance(q.get("month"), int):
        b["month"] = q["month"]
    t = q.get("type")
    metric = q.get("metric") if q.get("metric") in METRIC_COL else "영업이익"
    if t == "value":
        return q_value(b, metric, q.get("org"), q.get("compare"))
    if t == "org_rank":
        return q_org_rank(b, metric, "asc" if q.get("order") == "asc" else "desc", q.get("limit"))
    if t == "gap_orgs":
        return q_gap_orgs(b)
    if t == "org_explanation" and q.get("org"):
        return q_org_explanation(b, q["org"])
    if t == "unexplained":
        return q_unexplained(b)
    if t == "gap_count":
        return q_gap_count(b)
    if t == "org_gap_count":
        return q_org_gap_count(b, q.get("limit"))
    if t == "explanation_count":
        return q_explanation_count(b)
    if t == "cost_top":
        return q_cost_top(b, q.get("limit"), q.get("org"), metric if metric in ("매출", "영업비용") else "영업비용")
    if t == "monthly":
        return q_monthly(b, metric, q.get("org"))
    return {"text": "데이터 없음"}
