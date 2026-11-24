"""데이터 묶음(여러 조직·여러 시점 엑셀 + 소명 이력 JSON) → 조회 DB 적재와 검증.

읽는 것
- 현재 월 업로드(조직별 xlsx, 가장 늦은 마감월): fact(ACTUAL≤마감월 · ESTIMATE · PLAN). 버전 master-<연>-<월>
- 이전 월 승인본(같은 조직의 더 이른 마감월 xlsx): 버전 master-<연>-<월>. '직전 월', '직전 추정'의 기준
- 연간계획(이름에 PLAN): 업로드의 PLAN 과 같은지만 대조해 경고로 남긴다(적재는 업로드 쪽 PLAN 사용)
- 전년 실적: 업로드 파일 안의 '전년 실적' 블록을 월별 버전(close-<전년>-<월>)으로 넣는다 → '전년 동월'
- 소명: explanation_histories.json(있으면), 임계값: test_settings.json(있으면, 없으면 docs 기본값)
Gap 은 코드가 계산한다: 현재월 ACTUAL 은 PLAN 과, 이전 월 ACTUAL·ESTIMATE 는 이전 승인본과 비교(영업이익 임계값 두 가지가 모두 엄격 초과)하고,
이전 승인본과 비교할 때는 금액이 바뀐 상세 줄이 있으면 임계값 이하여도 '수정' 항목으로 남긴다.
정답 JSON(expected_*.json)은 여기서 읽지 않는다. 검증은 verify_against() 가 따로 한다.
"""
from __future__ import annotations

import json
import re
import unicodedata
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime
from decimal import Decimal
from pathlib import Path

from ..chat import db, schema
from ..chat.loader_excel import ORG_RE
from ..generic import reader
from ..generic.build import parse_period

SKIP_PART = re.compile(r"최초|first|invalid|오류|빈칸|control|무변경|경계|~\$", re.I)
PLAN_NAME = re.compile(r"(20\d{2})[_\-. ]?PLAN", re.I)
FALLBACK = {"ACTUAL": {"percent": Decimal(5), "amount_won": Decimal(10_000_000)}, "ESTIMATE": {"percent": Decimal(10), "amount_won": Decimal(50_000_000)}}


def _read(job: tuple) -> dict:
    path, rel, year, month, org, tag = job
    r = reader.read_workbook(path, file=rel, year=year, month=month, org_code=org)
    return {"tag": tag, "org": org, "rel": rel, **r}


def discover(root: Path) -> dict:
    """root 아래 엑셀을 훑어 현재 월 업로드 / 이전 월 승인본 / 연간계획으로 나눈다(이름에서 조직코드·연월을 읽는다)."""
    cur, plan, warns = defaultdict(list), [], []
    for p in sorted(root.rglob("*.xlsx")):
        rel = unicodedata.normalize("NFC", str(p.relative_to(root)))
        if SKIP_PART.search(rel):
            continue
        m = ORG_RE.search(p.name)
        if not m:
            continue
        pl = PLAN_NAME.search(p.name)
        if pl:
            plan.append({"path": str(p), "rel": rel, "org": m.group(0), "year": int(pl.group(1))})
            continue
        per = parse_period(rel)
        if per:
            cur[per].append({"path": str(p), "rel": rel, "org": m.group(0)})
    if not cur:
        return {"periods": [], "warnings": [{"code": "NO_FILES", "message": "읽을 조직 파일(조직코드+연월 이름)이 없습니다."}]}
    periods = sorted(cur)
    for per, lst in cur.items():
        seen = {}
        for f in lst:
            if f["org"] in seen:
                warns.append({"code": "DUPLICATE", "message": f"{f['rel']} 와 {seen[f['org']]['rel']} 은 같은 조직·같은 월입니다. 앞의 것만 씁니다."})
            else:
                seen[f["org"]] = f
        cur[per] = list(seen.values())
    return {"periods": periods, "files": dict(cur), "plan": plan, "warnings": warns}


def _thresholds(root: Path, warns: list) -> tuple[dict, str]:
    for p in sorted(root.rglob("test_settings.json")):
        try:
            t = json.loads(p.read_text(encoding="utf-8-sig"))["thresholds"]
            return {k: {"percent": Decimal(str(v["percent"])), "amount_won": Decimal(str(v["amount_won"]))} for k, v in t.items()}, f"{p.name}(데이터 묶음의 테스트 설정)"
        except (ValueError, KeyError, OSError):
            continue
    try:
        from ..pipeline.common import load_thresholds
        t = load_thresholds()
        return {k: {"percent": t[k]["percent"], "amount_won": t[k]["amount_won"]} for k in ("ACTUAL", "ESTIMATE")}, "ai 기본값(예시 값)"
    except Exception:  # noqa: BLE001
        warns.append({"code": "THRESHOLD_DEFAULT", "message": "임계값 문서를 읽지 못해 기본값(실적 5%·1천만원, 추정 10%·5천만원)을 썼습니다."})
        return FALLBACK, "기본값"


def _op(rows: list[tuple]) -> dict:
    """fact 행 → {(org, 월, 시나리오): 영업이익(원)}"""
    s = defaultdict(int)
    for r in rows:
        s[(r[1], r[12], r[13])] += r[14] if r[3] == "매출" else -r[14]
    return s


def _lines(rows: list[tuple]) -> dict:
    """상세 줄별 금액 {(org, 월, 시나리오, 종류, 시트 행, 항목…): 원}"""
    out = defaultdict(int)
    for r in rows:
        m = re.search(r"!([A-Z]+)(\d+)$", r[10] or "")
        out[(r[1], r[12], r[13], r[3], m.group(2) if m else "", r[4], r[5], r[6])] += r[14]
    return out


def _exceeds(delta: int, base: int, th: dict) -> tuple[bool, str | None]:
    rate = None if base == 0 else (Decimal(delta) / Decimal(abs(base)) * 100)
    if base == 0:
        return delta != 0, None
    return (abs(rate) > th["percent"] and abs(Decimal(delta)) > th["amount_won"]), f"{rate.quantize(Decimal('0.1'))}"


def compute_gaps(cur_ver: str, cy: int, cm: int, cur: list[tuple], prior: list[tuple] | None, names: dict, th: dict) -> list[dict]:
    """코드로 Gap 을 만든다. 반환 항목: gap_id, gap_type(OP_GAP|AMOUNT_REVISION), org, month, scenario, basis, previous, current, diff, rate, detail."""
    out = []
    opc = _op(cur)
    for (org, mo, sc), v in sorted(opc.items()):
        if sc == "ACTUAL" and mo == cm:
            base = opc.get((org, mo, "PLAN"))
            if base is None:
                continue
            ex, rate = _exceeds(v - base, base, th["ACTUAL"])
            if ex:
                out.append({"gap_id": f"{org}-{cur_ver}-{mo}-OP-PLAN", "gap_type": "OP_GAP", "org": org, "month": mo, "scenario": "ACTUAL", "basis": "PLAN", "previous": base, "current": v, "diff": v - base, "rate": rate})
    if prior is not None:
        opp = _op(prior)
        lc, lp = _lines(cur), _lines(prior)
        for (org, mo, sc), v in sorted(opc.items()):
            if sc == "PLAN" or (sc == "ACTUAL" and mo == cm):
                continue
            base = opp.get((org, mo, sc))
            if base is None:
                continue
            ex, rate = _exceeds(v - base, base, th[sc])
            if ex:
                out.append({"gap_id": f"{org}-{cur_ver}-{mo}-OP-PREVIOUS", "gap_type": "OP_GAP", "org": org, "month": mo, "scenario": sc, "basis": "PREVIOUS", "previous": base, "current": v, "diff": v - base, "rate": rate})
        changed = defaultdict(lambda: [0, 0])
        for k in set(lc) | set(lp):
            org, mo, sc = k[0], k[1], k[2]
            if sc == "PLAN" or (sc == "ACTUAL" and mo == cm) or (org, mo, sc) not in opp or (org, mo, sc) not in opc:
                continue
            if lc.get(k, 0) != lp.get(k, 0):
                # 보고 항목: 매출은 관리분류, 영업비용은 소분류(같은 보고 항목 안의 상세 증감은 합쳐서 한 건)
                rep = (k[5] if k[3] == "매출" else k[7]) or k[7] or k[6] or k[5]
                a2 = changed[(org, mo, sc, k[3], rep)]
                a2[0] += lp.get(k, 0)
                a2[1] += lc.get(k, 0)
        for (org, mo, sc, kind, rep), (before, after) in sorted(changed.items()):
            tag = "매출" if kind == "매출" else "Op"
            out.append({"gap_id": f"{org}-{cur_ver}-{mo}-{tag}-{rep}", "gap_type": "AMOUNT_REVISION", "org": org, "month": mo, "scenario": sc, "basis": "PREVIOUS", "previous": before, "current": after,
                        "diff": after - before, "rate": None, "item": rep, "kind": kind})
    return out


def _history_rows(root: Path, gaps: list[dict]) -> tuple[list[dict], list[str]]:
    """explanation_histories.json → 소명 행. 소명이 가리키는 항목 id 의 (조직, 월, 꼬리)로 코드가 만든 Gap 과 연결한다."""
    files = sorted(root.rglob("explanation_histories.json"))
    if not files:
        return [], ["explanation_histories.json 이 없어 소명을 읽지 못했습니다."]
    try:
        data = json.loads(files[0].read_text(encoding="utf-8-sig"))
    except ValueError:
        return [], ["explanation_histories.json 을 읽지 못했습니다."]
    rx = re.compile(r"^(?P<org>[A-Za-z]+\d+)-\d{4}-\d{2}-\d{2}-(?P<month>\d{1,2})-(?P<tail>.+)$")
    rows, notes = [], []
    for h in data:
        m = rx.match(str(h.get("review_item_id", "")))
        if not m:
            notes.append(f"{h.get('review_item_id')}: 항목 id 형식을 읽지 못함")
            continue
        org, mo, tail = m["org"], int(m["month"]), m["tail"]
        if tail.upper() == "OP":
            hit = [g for g in gaps if g["org"] == org and g["month"] == mo and g["gap_type"] == "OP_GAP"]
        else:
            item = tail.split("-", 1)[-1]
            hit = [g for g in gaps if g["org"] == org and g["month"] == mo and g["gap_type"] == "AMOUNT_REVISION" and g.get("item") == item]
        if not hit:
            notes.append(f"{h['review_item_id']}: 연결할 Gap 없음(코드가 만든 Gap 에 없음)")
            continue
        rounds = sorted(h.get("rounds") or [], key=lambda r: r.get("round", 0))
        last = rounds[-1] if rounds else {}
        for g in hit:
            rows.append({"gap": g, "id": h.get("id"), "answer": last.get("answer"), "verdict": last.get("verdict"), "rounds": len(rounds), "src": h.get("review_item_id")})
    return rows, notes


def load_dataset(root: Path, settings, *, workers: int | None = None) -> dict:
    """root 의 데이터 묶음을 조회 DB 로 교체 적재한다. 기존 DB 는 호출하는 쪽에서 백업한다."""
    root = Path(root)
    d = discover(root)
    warns = list(d.get("warnings") or [])
    if not d["periods"]:
        raise FileNotFoundError(warns[0]["message"] if warns else "파일이 없습니다.")
    (cy, cm) = d["periods"][-1]
    prior_per = d["periods"][-2] if len(d["periods"]) > 1 else None
    th, th_src = _thresholds(root, warns)
    jobs = [(f["path"], f["rel"], cy, cm, f["org"], "cur") for f in d["files"][(cy, cm)]]
    jobs += [(f["path"], f["rel"], cy - 1, 12, f["org"], "py") for f in d["files"][(cy, cm)]]
    if prior_per:
        jobs += [(f["path"], f["rel"], prior_per[0], prior_per[1], f["org"], "prior") for f in d["files"][prior_per]]
    plan_jobs = [(f["path"], f["rel"], f["year"], 0, f["org"], "plan") for f in d["plan"] if f["year"] == cy]
    allj = jobs + plan_jobs
    w = workers or max(1, min(len(allj), 8))
    with ProcessPoolExecutor(max_workers=w) as ex:
        res = list(ex.map(_read, allj))
    by = defaultdict(list)
    files_info = defaultdict(list)
    for r in res:
        by[r["tag"]].extend(r["rows"])
        warns.extend(r["warnings"])
        files_info[r["tag"]].append({"name": r["rel"], "org": r["org"], "rows": len(r["rows"])})
    cur_ver = f"master-{cy}-{cm:02d}"
    names = {}
    for r in by["cur"]:
        if r[2]:
            names.setdefault(r[1], r[2])
    gaps = compute_gaps(cur_ver, cy, cm, by["cur"], by["prior"] if prior_per else None, names, th)
    hist, hnotes = _history_rows(root, gaps)
    # 연간계획 PLAN 과 업로드 PLAN 대조
    plan_diff = 0
    if by["plan"]:
        pa, pb = _op(by["plan"]), {k: v for k, v in _op(by["cur"]).items() if k[2] == "PLAN"}
        plan_diff = sum(1 for k, v in pb.items() if k in pa and pa[k] != v)
        if plan_diff:
            warns.append({"code": "PLAN_MISMATCH", "message": f"연간계획 파일과 업로드 파일의 PLAN 영업이익이 다른 (조직·월) {plan_diff}곳. 업로드 쪽 값을 썼습니다."})

    db.clear_all(settings)
    con = db.open_writer(settings)
    ts = datetime.now().isoformat(timespec="seconds")
    try:
        with con:
            def put(ver, kind, label, y, m, rows):
                rows = [(ver, *r[1:]) for r in rows]
                for i in range(0, len(rows), 50000):
                    con.executemany("INSERT INTO fact VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows[i:i + 50000])
                con.execute("INSERT INTO dim_version(version, kind, label, year, month, loaded_at, source, row_count, is_latest) VALUES(?,?,?,?,?,?,?,?,0)", (ver, kind, label, y, m, ts, f"dataset:{root.name}", len(rows)))
            put(cur_ver, "master", f"{cy}년 {cm}월 업로드(최신)", cy, cm, by["cur"])
            if prior_per:
                put(f"master-{prior_per[0]}-{prior_per[1]:02d}", "master", f"{prior_per[0]}년 {prior_per[1]}월 승인본", prior_per[0], prior_per[1], by["prior"])
            for mm in range(1, 13):
                rows = [r for r in by["py"] if r[12] == mm and r[13] == "ACTUAL"]
                if rows:
                    put(f"close-{cy - 1}-{mm:02d}", "close", f"{cy - 1}년 {mm}월 실적(업로드 파일의 전년 블록)", cy - 1, mm, rows)
            for org in sorted({r[1] for r in by["cur"]}):
                db.upsert_org(con, org, names.get(org, org))
            linked = defaultdict(list)
            for h in hist:
                linked[h["gap"]["gap_id"]].append(h)
            for g in gaps:
                hs = linked.get(g["gap_id"], [])
                accepted = any(h["verdict"] == "ACCEPTED" for h in hs)
                est = "수락" if accepted else ("검토중" if hs else "소명 없음")
                status = "CARRIED_OVER_ELIGIBLE" if (g["gap_type"] == "OP_GAP" and _carry(g, gaps)) else ("NEW" if g["gap_type"] == "OP_GAP" else "REVISION")
                con.execute("INSERT INTO gap VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", (cur_ver, g["gap_id"], g["gap_type"], g["org"], names.get(g["org"], g["org"]), g.get("item") or "영업이익", cy, g["month"], g["scenario"],
                                                                                     g["basis"], g["previous"], g["current"], g["diff"], g["rate"], status, est))
            for h in hist:
                g = h["gap"]
                acc = h["verdict"] == "ACCEPTED"
                con.execute("INSERT INTO explanation VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", (cur_ver, h["id"], h["src"], g["gap_id"], g["org"], names.get(g["org"], g["org"]), "소명", g.get("item") or "영업이익", cy, g["month"], g["scenario"],
                                                                                             g["basis"], "수락" if acc else "검토중", h["verdict"], h["rounds"], h["answer"] if acc else None, h["src"]))
            db.mark_latest(con)
        con.executescript(schema.INDEXES + "ANALYZE;")
    finally:
        con.close()
    return {"root": str(root), "current": f"{cy}-{cm:02d}", "prior": f"{prior_per[0]}-{prior_per[1]:02d}" if prior_per else None, "orgs": len({r[1] for r in by["cur"]}),
            "files": {k: len(v) for k, v in files_info.items()}, "fact_rows": len(by["cur"]), "gaps": len(gaps), "gaps_op": sum(1 for g in gaps if g["gap_type"] == "OP_GAP"),
            "gaps_revision": sum(1 for g in gaps if g["gap_type"] == "AMOUNT_REVISION"), "explanations": len(hist), "explanation_notes": hnotes, "thresholds_from": th_src, "plan_mismatch": plan_diff,
            "warnings": warns[:50], "warning_total": len(warns)}


def _carry(g: dict, gaps: list[dict]) -> bool:
    """이연 후보: 같은 조직에서 현재 월 Gap 과 다음 월 추정 Gap 이 서로 반대 방향이고 크기가 같은 경우(코드 규칙)."""
    if g["basis"] != "PLAN":
        return False
    return any(o["org"] == g["org"] and o["scenario"] == "ESTIMATE" and o["gap_type"] == "OP_GAP" and o["month"] == g["month"] + 1 and o["diff"] == -g["diff"] for o in gaps)


def verify_against(root: Path, settings) -> dict:
    """정답 JSON(검증 전용)과 현재 조회 DB 를 대조한다. 적재 로직은 이 파일들을 읽지 않는다."""
    out = {"financial": None, "review": None}
    fin = next(iter(sorted(root.rglob("expected_financial_values.json"))), None)
    rev = next(iter(sorted(root.rglob("expected_review_items.json"))), None)
    con = db.open_readonly(settings)
    try:
        vers = {r["version"]: (r["year"], r["month"]) for r in con.execute("SELECT version, year, month FROM dim_version")}
        cur = max((v for v in vers if v.startswith("master-")), key=lambda v: vers[v])
        if fin:
            exp = json.loads(fin.read_text(encoding="utf-8-sig"))
            ok = bad = none = 0
            bad_rows = []
            for e in exp:
                r = con.execute("SELECT revenue_won, cost_won, op_won FROM v_op WHERE version=? AND org_code=? AND month=? AND scenario=?", (cur, e["organization_code"], e["month"], e["scenario"])).fetchone()
                if not r:
                    none += 1
                    continue
                if (str(r["revenue_won"]), str(r["cost_won"]), str(r["op_won"])) == (e["revenue_won"], e["cost_won"], e["op_won"]):
                    ok += 1
                else:
                    bad += 1
                    bad_rows.append({"org": e["organization_code"], "month": e["month"], "scenario": e["scenario"], "expected": [e["revenue_won"], e["cost_won"], e["op_won"]], "db": [r["revenue_won"], r["cost_won"], r["op_won"]]})
            out["financial"] = {"total": len(exp), "ok": ok, "wrong": bad, "missing": none, "wrong_samples": bad_rows[:10]}
        if rev:
            exp = json.loads(rev.read_text(encoding="utf-8-sig"))
            gaps = [dict(r) for r in con.execute("SELECT * FROM gap WHERE version=?", (cur,))]
            res = []
            later = {(x["organization_code"], x["month"], x["scenario"], x["report_item"]) for x in exp if x["submission_version"] != min(y["submission_version"] for y in exp if y["organization_code"] == x["organization_code"])}
            for e in exp:
                key = (e["organization_code"], e["month"], e["scenario"], e["report_item"])
                if key in later and e["submission_version"] == min(y["submission_version"] for y in exp if (y["organization_code"], y["month"], y["scenario"], y["report_item"]) == key):
                    res.append({"id": e["id"], "result": "대조 제외(이전 제출본: 최종 업로드와 비교 불가)"})
                    continue
                if e["report_item"] == "OP":
                    hit = [g for g in gaps if g["org_code"] == e["organization_code"] and g["month"] == e["month"] and g["scenario"] == e["scenario"] and g["gap_type"] == "OP_GAP"]
                    want = {c["basis"]: (int(c["baseline_amount_won"]), int(c["current_amount_won"])) for c in e["comparisons"] if c["exceeded"]}
                    if not hit:
                        res.append({"id": e["id"], "result": "없음"})
                        continue
                    good = any(g["basis"] in want and (g["previous_won"], g["current_won"]) == want[g["basis"]] for g in hit)
                    res.append({"id": e["id"], "result": "맞음" if good else "틀림"})
                else:
                    item = e["report_item"]
                    hit = [g for g in gaps if g["org_code"] == e["organization_code"] and g["month"] == e["month"] and g["scenario"] == e["scenario"] and g["gap_type"] == "AMOUNT_REVISION" and g["item"] == item]
                    want = e["source_changes"]
                    if not hit:
                        res.append({"id": e["id"], "result": "없음"})
                        continue
                    sb, sa = sum(int(c["before_won"]) for c in want), sum(int(c["after_won"]) for c in want)       # 같은 보고 항목 안의 상세 증감은 합쳐서 비교
                    ok2 = any((g["previous_won"], g["current_won"]) == (sb, sa) for g in hit)
                    res.append({"id": e["id"], "result": "맞음" if ok2 else "틀림"})
            extra = [g for g in gaps if not any(x["id"].startswith(g["org_code"]) and f"-{g['month']}-" in x["id"] for x in exp)]
            out["review"] = {"total": len(exp), "ok": sum(r["result"] == "맞음" for r in res), "wrong": sum(r["result"] == "틀림" for r in res), "missing": sum(r["result"] == "없음" for r in res),
                             "items": res, "extra_gaps_not_in_expected": [g["gap_id"] for g in extra][:20]}
    finally:
        con.close()
    return out
