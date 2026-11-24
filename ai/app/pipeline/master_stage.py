"""단계 3: master 생성 (docs/domain-rules.md §6, docs/development/erd.md, db-mapping.md §7).

- 최초 master 는 요청에 고정된 모든 조직(20)의 승인본이 있어야 생성. 하나라도 승인 전이면 생성하지 않는다(잠정 master 는 범위 밖 → 저장하지 않음).
- 버전은 master-YYYY-MM-NN. 이전 master 는 덮어쓰지 않는다.
- 이후 월 제출본이 과거 ACTUAL 월을 바꾸면 해당 과거 월도 새 버전으로 갱신(해당 조직 참조만 교체).
- 소명은 숫자를 바꾸지 않으므로 master 버전에 영향이 없다.
"""
from __future__ import annotations

from collections import Counter
from decimal import Decimal

from .common import REQUEST_MONTH, REQUEST_YEAR, eok
from decimal import Decimal as _D
from .gap_stage import Idx


def source_refs(sub: dict, y: int, mo: int, sc: str) -> list[dict]:
    from .common import compress_rows
    by = {}
    for a in sub["amounts"]:
        if (a["y"], a["mo"], a["sc"]) == (y, mo, sc):
            by.setdefault((a["sheet"], a["col"]), []).append(a["row"])
    return [{"file": sub["rel"], "sheet": sh, "column": col, "rows": compress_rows(rows)} for (sh, col), rows in sorted(by.items())]


def completeness(sub: dict) -> dict:
    """제출 전체 스냅샷 완전성: 2026 실적 1~요청월, 추정 다음 달~12월, 계획 1~12월의 상세 건수가 같은지."""
    cnt = Counter()
    for a in sub["amounts"]:
        if a["y"] == REQUEST_YEAR:
            cnt[(a["kind"], a["sc"], a["mo"])] += 1
    miss = []
    for kind in ("REVENUE", "COST"):
        base = cnt.get((kind, "plan", 1))
        for mo in range(1, 13):
            if cnt.get((kind, "plan", mo)) != base:
                miss.append([kind, "plan", mo])
            sc = "actual" if mo <= REQUEST_MONTH else "estimate"
            if cnt.get((kind, sc, mo)) != base:
                miss.append([kind, sc, mo])
    return {"complete": not miss, "missing_or_inconsistent": miss}


def build(state: str, subs: dict, snapshots: dict, gaps_result: dict) -> dict:
    snap = snapshots["before_last_approval" if state == "provisional" else "all_approved"]
    status = {s["organization_code"]: s for s in snap["sources"]}
    requested = list(status)
    approved = [o for o in requested if status[o]["status"] == "APPROVED"]
    pending = [{"org_code": o, "org_name": subs[o]["_meta"]["name"], "status": status[o]["status"], "submission_id": status[o]["submission_id"]} for o in requested if o not in approved]
    # 승인본으로 쓸 제출본은 최종본이어야 하며 제외·대체본이 아니어야 함
    problems = []
    for o in approved:
        f = subs[o]["FINAL"]
        if f["submission_id"] != status[o]["submission_id"]:
            problems.append(f"{o}: 승인 스냅샷 {status[o]['submission_id']} != 최종본 {f['submission_id']}")
    base = {"state": state, "requested_orgs": len(requested), "approved_orgs": len(approved), "approval_label": f"승인 {len(approved)}/{len(requested)} 조직",
            "source_snapshot_key": "before_last_approval" if state == "provisional" else "all_approved", "problems": problems}

    agg = _aggregate(subs, approved)
    past = _past_month_updates(subs, approved, gaps_result)
    report_inputs = _report_inputs(subs, requested, approved, status)
    contingency = {"value_won": None, "note": "문서상 경영기획 담당자가 연간 계획 확정 시 입력하는 값이며 이 데이터 패키지에 입력이 없음 → 보고 영업이익 = 합산 영업이익(Contingency 미반영)"}
    if len(approved) < len(requested):
        return {**base, "master": None, "master_created": False,
                "why_not_created": f"최초 master 는 고정된 모든 조직의 승인본이 필요함(domain-rules §6). master 미생성 · {base['approval_label']}",
                "unapproved_orgs": pending,
                "provisional_aggregate": {"label": "잠정", "scope": f"승인된 {len(approved)}개 조직의 최종 제출본", "month": f"{REQUEST_YEAR}-{REQUEST_MONTH:02d}", "scenario": "ACTUAL", **agg,
                                          "note": "ERD상 잠정 master 는 범위 밖이므로 master 로 저장하지 않는 참고용 집계"},
                "past_month_updates_on_approval": past, "contingency": contingency,
                "report_inputs": report_inputs}
    complete = {o: completeness(subs[o]["FINAL"]) for o in approved}
    ok = all(c["complete"] for c in complete.values())
    master = {
        "version": f"master-{REQUEST_YEAR}-{REQUEST_MONTH:02d}-01", "month": f"{REQUEST_YEAR}-{REQUEST_MONTH:02d}",
        "creation": "최초 생성(전 조직 승인 후)", "previous_version": None,
        "sources": [{"org_code": o, "org_name": subs[o]["_meta"]["name"], "submission_id": status[o]["submission_id"], "submission_status": status[o]["status"],
                     "file": subs[o]["FINAL"]["rel"], "complete_snapshot": complete[o]["complete"]} for o in approved],
        "source_count": len(approved), "all_snapshots_complete": ok,
        "excluded_not_used": [{"submission_id": subs[o][r]["submission_id"], "role": r, "reason": subs[o][r].get("excluded_reason") or "재제출로 대체됨"}
                              for o in approved for r in ("EXCLUDED", "SUPERSEDED") if r in subs[o]],
        "explanations_effect": "소명은 숫자를 바꾸지 않으므로 master 버전에 영향 없음",
        **agg,
    }
    return {**base, "master": master if ok else None, "master_created": ok, "past_month_updates": past, "contingency": contingency,
            "completeness_by_org": complete, "report_inputs": report_inputs}


def _aggregate(subs, orgs):
    rows, tr, tc = [], Decimal(0), Decimal(0)
    for o in orgs:
        ix = Idx(subs[o]["FINAL"])
        t = ix.op(REQUEST_YEAR, REQUEST_MONTH, "actual")
        rows.append({"org_code": o, "org_name": subs[o]["_meta"]["name"], "submission_id": subs[o]["FINAL"]["submission_id"],
                     "revenue_won": t["REVENUE"], "cost_won": t["COST"], "op_won": t["OP"],
                     "sources": source_refs(subs[o]["FINAL"], REQUEST_YEAR, REQUEST_MONTH, "actual")})
        tr += t["REVENUE"]; tc += t["COST"]
    return {"answer_sheet": answer_sheets(subs, orgs), "by_org": rows, "totals": {"revenue_won": tr, "cost_won": tc, "op_won": tr - tc, "revenue_eok": eok(tr), "cost_eok": eok(tc), "op_eok": eok(tr - tc),
                                       "formula": "영업이익 = Σ조직 매출 − Σ조직 영업비용 (각 조직 최종 제출본의 2026-11 실적 상세 합)"}}


TOL_WON = 1   # 답안지 값과 상세 합의 허용 차이(원). 엑셀 부동소수 끝자리 때문


def _won(d) -> str:
    """원 단위 정수 문자열(엑셀 부동소수 끝자리 제거: 1원 단위 반올림)."""
    from decimal import ROUND_HALF_UP
    q = d.quantize(Decimal(1), rounding=ROUND_HALF_UP)
    return "0" if q == 0 else str(q)       # '-0' 방지


def answer_sheet(sub: dict) -> dict:
    """한 조직의 답안지_IFRS15後 전체 표(월 1~12 × 항목 계층)와 합계 검증.

    값은 답안지 시트에서 읽은 그대로(원 단위 정수). 현재 값 = 1~요청월 실적 + 다음 달~12월 추정, 계획 = 1~12월 계획.
    검증: ① 답안지 매출·영업비용 = 상세 시트 합 ② 영업이익 = 매출 − 영업비용 ③ 부모 항목 = 직계 자식 합.
    """
    cur, plan, meta = {}, {}, {}
    for path, sc, mo, amt, row, cell, level in sub["sheet_all"]:
        meta.setdefault(row, {"path": path, "level": level})
        if sc == "plan":
            plan.setdefault(row, {})[mo] = amt
        elif (sc == "actual" and mo <= REQUEST_MONTH) or (sc == "estimate" and mo > REQUEST_MONTH):
            cur.setdefault(row, {})[mo] = amt
    order = sorted(meta)                                   # 행 번호 순(시트 순서)
    months = list(range(1, 13))
    parent, stack = {}, []                                 # 부모 = 바로 위의 한 단계 위 깊이 행
    for r in order:
        lv = meta[r]["level"]
        while stack and meta[stack[-1]]["level"] >= lv:
            stack.pop()
        parent[r] = stack[-1] if stack else None
        stack.append(r)
    rows = [{"path": list(meta[r]["path"]), "level": meta[r]["level"], "row": r,
             "current": {str(m): _won(cur[r][m]) for m in months if m in cur.get(r, {})},
             "plan": {str(m): _won(plan[r][m]) for m in months if m in plan.get(r, {})}} for r in order]
    # 검증
    det = {}
    for a in sub["amounts"]:
        if a["y"] == REQUEST_YEAR:
            det[(a["kind"], a["mo"], a["sc"])] = det.get((a["kind"], a["mo"], a["sc"]), Decimal(0)) + a["amount"]
    top_row = {meta[r]["path"][0]: r for r in order if meta[r]["level"] == 1 and len(meta[r]["path"]) == 1}
    top = {n: cur.get(top_row.get(n), {}) for n in ("매출", "영업비용", "영업이익")}
    chk, ok = [], True
    for m in months:
        sc = "actual" if m <= REQUEST_MONTH else "estimate"
        ar, ac, ao = (top[n].get(m) for n in ("매출", "영업비용", "영업이익"))
        dr, dc = det.get(("REVENUE", m, sc), Decimal(0)), det.get(("COST", m, sc), Decimal(0))
        if None in (ar, ac, ao):
            chk.append({"month": m, "scenario": sc, "missing": True})
            ok = False
            continue
        e = {"month": m, "scenario": sc, "missing": False,
             "revenue": {"answer": _won(ar), "detail": _won(dr), "diff": _won(ar - dr)}, "cost": {"answer": _won(ac), "detail": _won(dc), "diff": _won(ac - dc)},
             "op": {"answer": _won(ao), "answer_rev_minus_cost": _won(ar - ac), "diff": _won(ao - (ar - ac)), "detail_rev_minus_cost": _won(dr - dc), "diff_vs_detail": _won(ao - (dr - dc))}}
        e["ok"] = all(abs(Decimal(x)) <= TOL_WON for x in (e["revenue"]["diff"], e["cost"]["diff"], e["op"]["diff"], e["op"]["diff_vs_detail"]))
        ok &= e["ok"]
        chk.append(e)
    kids = {}
    for r in order:
        if parent[r] is not None:
            kids.setdefault(parent[r], []).append(r)
    bad = []
    for r, ks in kids.items():
        for m in months:
            if m in cur.get(r, {}) and all(m in cur.get(c, {}) for c in ks):
                d = cur[r][m] - sum((cur[c][m] for c in ks), Decimal(0))
                if abs(d) > TOL_WON:
                    bad.append({"path": list(meta[r]["path"]), "row": r, "month": m, "diff": _won(d)})
    return {"unit": "원", "months": months, "scenario_by_month": {str(m): ("actual" if m <= REQUEST_MONTH else "estimate") for m in months}, "rows": rows,
            "check": {"months": chk, "hierarchy_mismatches": bad[:50], "hierarchy_mismatch_count": len(bad), "tolerance_won": TOL_WON, "all_ok": ok and not bad,
                      "row_count": len(rows), "item_count_by_level": {str(k): sum(1 for r in rows if r["level"] == k) for k in sorted({r["level"] for r in rows})},
                      "parents_checked": len(kids)}}


def answer_sheets(subs, orgs):
    return {o: {"org_code": o, "org_name": subs[o]["_meta"]["name"], "submission_id": subs[o]["FINAL"]["submission_id"], **answer_sheet(subs[o]["FINAL"])} for o in orgs}


def _past_month_updates(subs, approved, gaps_result):
    out = {}
    for g in gaps_result["gaps"]:
        if g["gap_type"] == "AMOUNT_REVISION" and g["scenario"] == "ACTUAL" and g["month"] < REQUEST_MONTH and g["org_code"] in approved and g["status_in_final"] in ("ACTIVE", "CARRIED_OVER_ELIGIBLE"):
            out.setdefault(g["month"], []).append(g)
    res = []
    for mo, gs in sorted(out.items()):
        orgs = sorted({g["org_code"] for g in gs})
        res.append({
            "month": f"{REQUEST_YEAR}-{mo:02d}", "previous_version": f"master-{REQUEST_YEAR}-{mo:02d}-01", "new_version": f"master-{REQUEST_YEAR}-{mo:02d}-02",
            "replaced_sources": [{"org_code": o, "from": subs[o]["BASELINE"]["submission_id"], "to": subs[o]["FINAL"]["submission_id"]} for o in orgs],
            "changed_items": [{"org_code": g["org_code"], "item": g["item"], "diff_won": g["diff_won"]} for g in gs],
            "basis": "이후 월 제출본이 과거 ACTUAL 을 바꾸면 해당 월 master 를 새 버전으로 갱신(domain-rules §6). 이전 master 구성은 이 패키지에 파일이 없어 baseline_2026_10 승인본으로 가정함",
        })
    return res


def _report_inputs(subs, requested, approved, status):
    """07 자료가 master 밖에서 가져오는 비교 기준(지난달·작년 동월·계획·연간·비용 상세). 각 값에 출처를 붙인다.

    이번 달 실적은 master(by_org)의 값을 쓰고, 여기에는 비교용 값만 둔다. 07 자료는 이 파일(master_*.json)만 읽는다.
    """
    Y, M = REQUEST_YEAR, REQUEST_MONTH
    from collections import defaultdict
    orgs = []
    for o in approved:
        F, P = subs[o]["FINAL"], subs[o]["PLAN"]
        fi, pi = Idx(F), Idx(P)

        def trio(t):
            return {"revenue_won": t["REVENUE"], "cost_won": t["COST"], "op_won": t["OP"]}
        last, ly, plan = fi.op(Y, M - 1, "actual"), fi.op(Y - 1, M, "actual"), pi.op(Y, M, "plan")
        annual_plan = sum((pi.op(Y, mo, "plan")["OP"] for mo in range(1, 13)), _D(0))
        latest = sum((fi.op(Y, mo, "actual" if mo <= M else "estimate")["OP"] for mo in range(1, 13)), _D(0))
        cd = defaultdict(lambda: {"this_won": _D(0), "last_won": _D(0), "plan_won": _D(0)})
        for scope, key, per in (("FINAL", "this_won", (Y, M, "actual")), ("FINAL", "last_won", (Y, M - 1, "actual")), ("PLAN", "plan_won", (Y, M, "plan"))):
            for a in subs[o][scope]["amounts"]:
                if a["kind"] == "COST" and (a["y"], a["mo"], a["sc"]) == per:
                    cd[(a["dims"].get("중분류"), a["dims"].get("소분류"))][key] += a["amount"]
        orgs.append({
            "org_code": o, "org_name": subs[o]["_meta"]["name"], "division": subs[o]["_meta"]["division"], "submission_id": F["submission_id"],
            "last_month": {**trio(last), "origin": f"최종 제출본 {Y}-{M-1:02d} ACTUAL 블록(= master-{Y}-{M-1:02d}-02 구성과 같은 값, 사업부14 정정 반영)", "refs": source_refs(F, Y, M - 1, "actual")},
            "last_year_same_month": {**trio(ly), "origin": f"최종 제출본 파일의 {Y-1} 실적 블록({Y-1}-{M:02d} master 파일은 없음)", "refs": source_refs(F, Y - 1, M, "actual")},
            "plan_month": {**trio(plan), "origin": f"annual_plan 파일 {Y} PLAN 블록", "refs": source_refs(P, Y, M, "plan")},
            "annual": {"plan_op_won": annual_plan, "latest_estimate_op_won": latest,
                       "origin": f"계획 = annual_plan 파일 {Y} PLAN 1~12월 영업이익 합, 최신 추정 = 최종 제출본 1~{M}월 실적 + {M+1}~12월 추정 영업이익 합",
                       "files": [P["rel"], F["rel"]]},
            "cost_detail": [{"mid": k[0], "small": k[1], **v} for k, v in sorted(cd.items(), key=lambda kv: str(kv[0]))],
            "cost_detail_origin": "OpEx_세부 상세행을 중분류·소분류로 합산(최종 제출본 이번/지난달 실적, annual_plan 계획). '대분류'는 파서가 내보내지 않아 없음",
        })
    all_orgs = [{"org_code": o, "org_name": subs[o]["_meta"]["name"], "division": subs[o]["_meta"]["division"], "included": o in approved,
                 "approval_status": status[o]["status"]} for o in requested]
    # 품질 확인용(07 숫자의 출처가 아님): 파서가 읽은 답안지 총계와 master 합계의 차이
    ans = {"매출": _D(0), "영업비용": _D(0), "영업이익": _D(0)}
    for o in approved:
        for a in subs[o]["FINAL"]["ans"]:
            if (a["y"], a["mo"], a["sc"]) == (Y, M, "actual"):
                ans[a["name"]] += a["amount"]
    return {"base_month": f"{Y}-{M:02d}", "all_orgs": all_orgs, "orgs": orgs,
            "quality_check_answer_sheet": {"note": "참고용 점검. 07 자료 숫자의 출처로 쓰지 않음", "answer_sheet_totals_won": ans}}
