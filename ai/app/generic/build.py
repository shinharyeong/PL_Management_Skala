"""일반 모드 실행: 업로드된 엑셀(구조·이름·폴더가 달라도)을 읽어 월별 조회 DB 에 적재하고 보고서 화면 요약을 만든다.

기준월·조직 수·파일 수·버전 이름은 모두 데이터(파일 이름·폴더·시트)에서 읽는다. 못 읽은 것은 경고로 남기고 계속한다.
소명 JSON 이 없거나 일부만 있어도 동작한다(해당 Gap 은 '소명 없음'). AI 는 쓰지 않는다(템플릿 문장).
"""
from __future__ import annotations

import json
import re
import time
import unicodedata
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

from ..chat import db, schema
from ..chat.loader_excel import ORG_RE
from . import reader

PERIOD_RES = (re.compile(r"(20\d{2})[_\-. ]?(\d{1,2})\s*월\s*마감"), re.compile(r"(?<!\d)(20\d{2})[_\-.](\d{1,2})(?!\d)"))
SKIP_WORDS = ("전체", "마스터")
FINAL_RE, REVISED_RE = re.compile(r"최종|final", re.I), re.compile(r"수정|재제출|보완|rev|v\s*\d+", re.I)
DEFAULT_THRESHOLD = {"percent": Decimal(5), "amount_won": Decimal(10_000_000)}


def parse_period(rel: str) -> tuple[int, int] | None:
    """경로(폴더·파일 이름)에서 마감 연월을 찾는다. 파일 이름을 먼저 보고 안쪽 폴더부터 본다."""
    parts = [p for p in unicodedata.normalize("NFC", rel).split("/") if p]
    for part in reversed(parts):
        for rx in PERIOD_RES:
            m = rx.search(part)
            if m and 1 <= int(m.group(2)) <= 12:
                return int(m.group(1)), int(m.group(2))
    return None


def _job(args: tuple) -> dict:
    path, rel, year, month, org = args
    r = reader.read_workbook(path, file=rel, year=year, month=month, org_code=org)
    return {"rel": rel, "org": org, "year": year, "month": month, **r}


def plan_files(root: Path) -> tuple[list[dict], list[dict]]:
    """root 아래 xlsx 를 훑어 (읽을 작업 목록, 경고). 이름에서 조직코드와 마감 연월을 읽고 중복은 하나만 고른다."""
    warns: list[dict] = []
    cands: dict[tuple, list[dict]] = defaultdict(list)
    for p in sorted(root.rglob("*.xlsx")):
        rel = unicodedata.normalize("NFC", str(p.relative_to(root)))
        name = unicodedata.normalize("NFC", p.name)
        if name.startswith("~$"):
            continue
        if any(w in name for w in SKIP_WORDS):
            warns.append({"file": rel, "sheet": None, "code": "SKIPPED_MASTER", "message": "전체 마스터 파일은 조직별 파일이 아니라 건너뛰었습니다."})
            continue
        m = ORG_RE.search(name)
        if not m:
            warns.append({"file": rel, "sheet": None, "code": "NO_ORG_CODE", "message": "파일 이름에서 조직 코드를 찾지 못해 건너뛰었습니다."})
            continue
        per = parse_period(rel)
        if not per:
            warns.append({"file": rel, "sheet": None, "code": "NO_PERIOD", "message": "파일 이름·폴더에서 마감 연월을 찾지 못해 건너뛰었습니다."})
            continue
        cands[(m.group(0), *per)].append({"path": str(p), "rel": rel, "score": (2 if FINAL_RE.search(name) else 1 if REVISED_RE.search(name) else 0, name)})
    jobs = []
    for (org, y, mo), lst in sorted(cands.items()):
        lst.sort(key=lambda x: x["score"])
        pick = lst[-1]
        for other in lst[:-1]:
            warns.append({"file": other["rel"], "sheet": None, "code": "DUPLICATE", "message": f"같은 조직·같은 월 파일이 여러 개라 '{pick['rel'].split('/')[-1]}' 하나만 썼습니다(이 파일은 제외)."})
        jobs.append((pick["path"], pick["rel"], y, mo, org))
    return jobs, warns


def load_period(version: str, year: int, month: int, rows: list[tuple], source: str, settings) -> int:
    """한 마감월의 fact 를 조회 DB 에 적재(같은 버전은 교체)."""
    con = db.open_writer(settings)
    try:
        con.execute("PRAGMA synchronous=OFF")
        rows = [(version, *r[1:]) for r in rows]
        with con:
            con.execute("DELETE FROM fact WHERE version=?", (version,))
            con.execute("DELETE FROM dim_version WHERE version=?", (version,))
            for i in range(0, len(rows), 50000):
                con.executemany("INSERT INTO fact VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows[i:i + 50000])
            from collections import Counter
            names: dict = {}
            for r in rows:
                if r[2]:
                    names.setdefault(r[1], Counter())[r[2]] += 1
            seen = {org: (names[org].most_common(1)[0][0] if org in names else org) for org in {r[1] for r in rows}}     # 가장 많이 나온 이름(없으면 코드)
            con.executemany("INSERT OR REPLACE INTO dim_org(org_code, org_name) VALUES(?,?)", list(seen.items()))
            con.execute("INSERT INTO dim_version(version, kind, label, year, month, loaded_at, source, row_count, is_latest) VALUES(?,?,?,?,?,?,?,?,0)",
                        (version, "close", f"{month}월 마감", year, month, datetime.now().isoformat(timespec="seconds"), source, len(rows)))
        con.executescript(schema.INDEXES)
        with con:
            db.mark_latest(con)
    finally:
        con.close()
    return len(rows)


# ----------------------------------------------------------------------------- 보고서 요약
def eok(won) -> Decimal:
    return (Decimal(won) / Decimal(10) ** 8).quantize(Decimal("0.1"), ROUND_HALF_UP)


def fmt(d: Decimal) -> str:
    s = f"{abs(d):,.1f}"
    return ("−" if d < 0 else "") + s


def signed(d: Decimal, plus: str = "+") -> str:
    return ("−" if d < 0 else (plus if d > 0 else "")) + f"{abs(d):,.1f}"


def pct(diff: int, base: int) -> Decimal | None:
    if not base:
        return None
    return (Decimal(diff) / Decimal(abs(base)) * 100).quantize(Decimal("0.1"), ROUND_HALF_UP)


class Sums:
    """fact 행을 (조직, 종류, 월, 시나리오) 로 합산한다."""

    def __init__(self, rows: list[tuple]):
        self.s: dict = defaultdict(int)
        counts: dict = defaultdict(lambda: defaultdict(int))
        codes = set()
        for r in rows:
            self.s[(r[1], r[3], r[12], r[13])] += r[14]
            codes.add(r[1])
            if r[2]:
                counts[r[1]][r[2]] += 1
        self.names = {c: (max(counts[c].items(), key=lambda kv: kv[1])[0] if counts.get(c) else c) for c in codes}     # 가장 많이 나온 이름(없으면 코드)
        self.orgs = sorted(codes)

    def total(self, kind: str | None, month: int, scen: str, org: str | None = None) -> int | None:
        keys = [k for k in self.s if k[2] == month and k[3] == scen and (org is None or k[0] == org) and (kind is None or k[1] == kind)]
        return sum(self.s[k] for k in keys) if keys else None

    def op(self, month: int, scen: str, org: str | None = None) -> int | None:
        r, c = self.total("매출", month, scen, org), self.total("영업비용", month, scen, org)
        return None if r is None and c is None else (r or 0) - (c or 0)

    def annual_op(self, org: str, scens: tuple) -> int | None:
        keys = [k for k in self.s if k[0] == org and k[3] in scens]
        if not keys:
            return None
        return sum(self.s[k] * (1 if k[1] == "매출" else -1) for k in keys)


def _threshold(warns: list) -> dict:
    try:
        from ..pipeline.common import load_thresholds
        t = load_thresholds()["ACTUAL"]
        return {"percent": Decimal(t["percent"]), "amount_won": Decimal(t["amount_won"])}
    except Exception:  # noqa: BLE001
        warns.append({"file": None, "sheet": None, "code": "THRESHOLD_DEFAULT", "message": "Gap 기준 문서를 읽지 못해 기본값(±5%·1천만원)을 썼습니다."})
        return DEFAULT_THRESHOLD


def load_explanations(path: Path | None) -> list[dict]:
    if not path or not path.exists():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except ValueError:
        return []
    return [x for x in data if isinstance(x, dict)] if isinstance(data, list) else []


def build_screen(groups: dict, base: tuple[int, int], warns: list, explanations: list[dict], file_count: int, notes: list[str]) -> dict:
    y, m = base
    S = Sums(groups[base])
    prev_group = groups.get((y, m - 1)) if m > 1 else groups.get((y - 1, 12))
    SP = Sums(prev_group) if prev_group else None
    SY = Sums(groups[(y - 1, m)]) if (y - 1, m) in groups else None
    rows = []
    for name, kind in (("매출", "매출"), ("영업비용", "영업비용"), ("영업이익", None)):
        get = (lambda s, mm, sc: s.total(kind, mm, sc)) if kind else (lambda s, mm, sc: s.op(mm, sc))
        tm = get(S, m, "ACTUAL")
        lm = get(S, m - 1, "ACTUAL") if m > 1 else None
        if lm is None and SP is not None:
            lm = get(SP, m - 1 if m > 1 else 12, "ACTUAL")
        ly = get(SY, m, "ACTUAL") if SY else None

        def cmp(base):
            if tm is None or base is None:
                return "-", "-"
            d = tm - base
            p = pct(d, base)
            arrow = "▲" if d >= 0 else "▼"
            return f"{arrow}{abs(eok(d)):,.1f}", ("-" if p is None else f"{'+' if p >= 0 else '−'}{abs(p):,.1f}%")
        d1, p1 = cmp(lm)
        d2, p2 = cmp(ly)
        rows.append([name, fmt(eok(ly)) if ly is not None else "-", fmt(eok(lm)) if lm is not None else "-", fmt(eok(tm)) if tm is not None else "-", d1, p1, d2, p2])
    # 연간 브리지(계획 → 조직별 증감 → 최신 추정)
    bridge = None
    plan_by, est_by = {}, {}
    for org in S.orgs:
        pl, es = S.annual_op(org, ("PLAN",)), S.annual_op(org, ("ACTUAL", "ESTIMATE"))
        if pl is not None and es is not None:
            plan_by[org], est_by[org] = pl, es
    if plan_by:
        plan_t, est_t = sum(plan_by.values()), sum(est_by.values())
        deltas = sorted(((est_by[o] - plan_by[o], o) for o in plan_by), key=lambda x: -abs(x[0]))
        top, rest = deltas[:5], deltas[5:]
        steps = [{"label": "연간 계획", "value": fmt(eok(plan_t)), "eok": float(eok(plan_t)), "type": "total"}]
        for d, o in top:
            steps.append({"label": S.names[o], "value": signed(eok(d)), "eok": float(eok(d)), "type": "delta"})
        if rest:
            d = sum(x[0] for x in rest)
            steps.append({"label": f"기타({len(rest)}개 조직)", "value": signed(eok(d)), "eok": float(eok(d)), "type": "delta"})
        steps.append({"label": "최신 추정", "value": fmt(eok(est_t)), "eok": float(eok(est_t)), "type": "total"})
        dt = est_t - plan_t
        p = pct(dt, plan_t)
        bridge = {"plan": fmt(eok(plan_t)), "est": fmt(eok(est_t)), "delta": signed(eok(dt)), "pct": (f"{'+' if p >= 0 else '−'}{abs(p):,.1f}%" if p is not None else "-"), "caption": f"연간 계획 {fmt(eok(plan_t))} → 최신 추정 {fmt(eok(est_t))} ({signed(eok(dt))}" + (f", {'+' if p >= 0 else '−'}{abs(p):,.1f}%" if p is not None else "") + ") · 억원", "steps": steps}
    # Gap(조직 영업이익의 계획 대비) + 소명 연결
    th = _threshold(warns)
    by_org_exp: dict[str, list[dict]] = defaultdict(list)
    for e in explanations:
        parts = str(e.get("gap_key", "")).split(":")
        if len(parts) >= 4 and parts[3].isdigit() and int(parts[3]) == m:
            by_org_exp[parts[0]].append(e)
    gaps, no_exp = [], 0
    for org in S.orgs:
        a, pl = S.op(m, "ACTUAL", org), S.op(m, "PLAN", org)
        if a is None or pl is None:
            continue
        d = a - pl
        p = pct(d, pl)
        over = (abs(p) > th["percent"] and abs(Decimal(d)) > th["amount_won"]) if p is not None else abs(Decimal(d)) > th["amount_won"]
        if not over:
            continue
        ex = sorted(by_org_exp.get(org, []), key=lambda e: e.get("round", 0))
        last = ex[-1] if ex else None
        status = {"ACCEPTED": "수락", "REJECTED": "반려"}.get((last or {}).get("verdict"), "검토중") if last else "소명 없음"
        no_exp += 0 if last else 1
        gaps.append({"org_name": S.names[org], "item": "영업이익", "ym": f"{y}-{m:02d}", "gclass": "영업이익 계획 대비", "diff_eok": f"{eok(d):.1f}", "basis": "계획 대비", "status": status,
                     "dfr": None, "answer": (last or {}).get("answer") if status == "수락" else None, "resub": len(ex) > 1})
    # 핵심 메시지(템플릿): 숫자는 위 표의 값 그대로
    label = f"{y}년 {m}월 전사"
    msgs = []
    for name, kind in (("매출", "매출"), ("영업비용", "영업비용"), ("영업이익", None)):
        tm = S.total(kind, m, "ACTUAL") if kind else S.op(m, "ACTUAL")
        pl = S.total(kind, m, "PLAN") if kind else S.op(m, "PLAN")
        if tm is None:
            continue
        s = f"{label} {name} 실적은 {fmt(eok(tm))}억원"
        if pl is not None:
            dd = tm - pl
            pp = pct(dd, pl)
            s += f"이며, 계획 {fmt(eok(pl))}억원 대비 {signed(eok(dd))}억원" + (f"({'+' if pp >= 0 else '−'}{abs(pp):,.1f}%)" if pp is not None else "")
        msgs.append(s + "입니다.")
    if bridge:
        msgs.append(f"연간 영업이익은 계획 {bridge['plan']}억원에서 최신 추정 {bridge['est']}억원으로 {bridge['delta']}억원({bridge['pct']}) 차이가 납니다.")
    if gaps:
        msgs.append(f"계획 대비 Gap 조직은 {len(gaps)}곳이고 소명이 없는 곳은 {no_exp}곳입니다.")
    nw = len(warns)
    if nw:
        msgs.append(f"읽지 못했거나 건너뛴 항목이 {nw}건 있어 일부 값이 빠졌을 수 있습니다.")
    return {
        "state": "final", "data_status": "일반 모드", "master_version": f"close-{y}-{m:02d}", "base_month": f"{y}-{m:02d}", "unit": "억원",
        "scope": f"전사({len(S.orgs)}개 조직)", "approval_label": f"파일 {file_count}개", "generic": True,
        "banner": (f"일반 모드로 읽었습니다(공식 master 아님). 읽지 못했거나 건너뛴 항목 {nw}건이 있어 일부 값이 빠졌을 수 있습니다." if nw else "일반 모드로 읽었습니다(공식 master 아님)."),
        "summary": {"caption": f"{y}년 {m}월 · 전사({len(S.orgs)}개 조직) · 억원", "rows": rows}, "bridge": bridge, "message": msgs,
        "reason": next(({"title": f"▼ {g['item']} 소명 · [{g['org_name']}]", "cause": g["answer"]} for g in gaps if g["answer"]), None), "weekly": None,
        "gap_counts": {"gaps_total": len(gaps), "item_revision_gaps": 0, "op_gaps": len(gaps), "gaps_without_explanation": no_exp, "explanation_keys": sum(len(v) for v in by_org_exp.values())},
        "explanations": gaps, "warnings": warns[:300], "warning_total": nw, "notes": notes,
    }


def run(root: Path, scen_dir: Path | None, settings, progress=lambda *_: None, workers: int | None = None) -> dict:
    """root: 업로드된 엑셀 폴더. 반환 {parse, gaps, master, report} 단계 결과."""
    t0 = time.perf_counter()
    progress("parse", "running", "엑셀을 읽는 중")
    jobs, warns = plan_files(root)
    if not jobs:
        return {"parse": {"status": "FAIL", "message": "읽을 수 있는 조직 파일이 없습니다(파일 이름에 조직 코드와 마감 연월이 필요합니다).", "files": [], "errors": warns[:50], "warnings": warns[:300]}}
    w = workers if workers is not None else max(1, min(len(jobs), settings.parse_workers or 8))
    if w > 1 and len(jobs) > 1:
        with ProcessPoolExecutor(max_workers=w) as ex:
            results = list(ex.map(_job, jobs))
    else:
        results = [_job(j) for j in jobs]
    groups: dict[tuple, list] = defaultdict(list)
    files = []
    for r in results:
        warns.extend(r["warnings"])
        groups[(r["year"], r["month"])].extend(r["rows"])
        files.append({"name": r["rel"], "org_code": r["org"], "period": f"{r['year']}-{r['month']:02d}", "rows": len(r["rows"]), "units": r["stats"]["units"]})
    groups = {k: v for k, v in groups.items() if v}
    if not groups:
        return {"parse": {"status": "FAIL", "message": "읽은 금액이 없습니다. 파일 양식을 확인하세요.", "files": files, "errors": warns[:50], "warnings": warns[:300]}}
    parse_status = "WARN" if warns else "OK"
    n_orgs = len({f["org_code"] for f in files})
    parse = {"status": parse_status, "message": f"엑셀 {len(files)}개({n_orgs}개 조직, {len(groups)}개 마감월)를 읽었습니다." + (f" 경고 {len(warns)}건." if warns else ""),
             "files": files[:500], "warnings": warns[:300], "warning_total": len(warns), "periods": [f"{y}-{m:02d}" for y, m in sorted(groups)]}
    progress("parse", "done", parse["message"])
    progress("gaps", "running", "조회 DB 적재와 Gap 계산")
    db.clear_all(settings)
    for (y, m), rows in sorted(groups.items()):
        load_period(f"close-{y}-{m:02d}", y, m, rows, f"generic:{len(files)}files", settings)
    base = max(groups)
    exps = load_explanations(next(iter(sorted(scen_dir.glob("explanations.json"))), None) if scen_dir and scen_dir.exists() else None)
    screen = build_screen(groups, base, warns, exps, len(files), [])
    gaps = {"status": "OK" if not exps and not screen["gap_counts"]["gaps_total"] else ("WARN" if screen["gap_counts"]["gaps_without_explanation"] else "OK"),
            "message": f"계획 대비 Gap {screen['gap_counts']['gaps_total']}건(소명 없는 Gap {screen['gap_counts']['gaps_without_explanation']}건).", "counts": screen["gap_counts"]}
    progress("gaps", "done", gaps["message"])
    master = {"status": "OK", "message": "일반 모드: 공식 master 단계 없이 마감월 조직 합계로 보고합니다.", "final": None, "provisional": None}
    report = {"status": "OK", "message": f"{base[0]}년 {base[1]}월 보고서 요약을 만들었습니다(템플릿 문장).", "screen": screen, "data": None, "ai_requested": False}
    progress("master", "done", master["message"])
    progress("report", "done", report["message"])
    return {"parse": parse, "gaps": gaps, "master": master, "report": report, "seconds": round(time.perf_counter() - t0, 2)}


def reload(root: Path, settings, workers: int | None = None) -> dict:
    """보관된 업로드 원본을 다시 읽어 조회 DB 에만 적재한다(실행 선택으로 챗봇 DB 를 바꿀 때)."""
    jobs, _ = plan_files(root)
    w = workers if workers is not None else max(1, min(len(jobs) or 1, settings.parse_workers or 8))
    results = [_job(j) for j in jobs] if w <= 1 or len(jobs) <= 1 else list(ProcessPoolExecutor(max_workers=w).map(_job, jobs))
    groups: dict[tuple, list] = defaultdict(list)
    for r in results:
        groups[(r["year"], r["month"])].extend(r["rows"])
    versions = []
    db.clear_all(settings)
    for (y, m), rows in sorted(groups.items()):
        if rows:
            load_period(f"close-{y}-{m:02d}", y, m, rows, f"generic:{len(jobs)}files", settings)
            versions.append({"version": f"close-{y}-{m:02d}", "rows": len(rows)})
    return {"versions": versions}
