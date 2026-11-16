"""단계 4: Figma 07 '보고서 자료 뽑는 화면' 자료 계산 (AI 없음).

입력은 master_{state}.json 한 파일(+ 소명 연결 결과)뿐이다. 엑셀·답안지를 직접 합치지 않는다.
- 이번 달 실적: master(또는 잠정 집계) by_org 의 revenue_won/cost_won/op_won
- 비교 기준(지난달·작년 동월·계획·연간·비용 상세): master_*.json 의 report_inputs (각 값에 origin 기재)
억원 표시는 common.eok(소수 1자리, ROUND_HALF_UP) 한 곳에서만 계산한다.
"""
from __future__ import annotations

from collections import defaultdict
from decimal import Decimal

from .common import REQUEST_MONTH, REQUEST_YEAR, eok, fmt_eok, fmt_pct, pct1, rate_percent

Y, M = REQUEST_YEAR, REQUEST_MONTH
UNIT = "억원"
LABEL = {"revenue_won": "매출", "cost_won": "영업비용", "op_won": "영업이익"}
D = Decimal


def _w(x) -> Decimal:
    return x if isinstance(x, Decimal) else D(str(x))


def _cmp(cur: Decimal, base: Decimal | None) -> dict:
    if base is None:
        return {"diff_won": None, "diff_eok": None, "percent": None, "percent_note": "비교값 없음"}
    diff = cur - base
    r = rate_percent(diff, base)
    return {"diff_won": diff, "diff_eok": eok(diff), "percent": pct1(r), "percent_note": None if r is not None else "기준값 0 → 기준 없음",
            "direction": "▲" if diff > 0 else ("▼" if diff < 0 else "－")}


def _history_label(history):
    name = {"REJECTED": "반려", "ACCEPTED": "수락"}
    return " → ".join(f"{h['round']}차 {name.get(h['verdict'], '검토중')}" for h in history)


def build(state: str, master_doc: dict, expl_links: list[dict], gap_counts: dict, gaps: list[dict], show_reference_explanations: bool = True) -> dict:
    mfile = f"master_{state}.json"
    block_key = "master" if master_doc.get("master") else "provisional_aggregate"
    block = master_doc[block_key]
    by_org = {r["org_code"]: r for r in block["by_org"]}
    ri = master_doc["report_inputs"]
    ref = {o["org_code"]: o for o in ri["orgs"]}
    allo = {o["org_code"]: o for o in ri["all_orgs"]}
    orgs = [o["org_code"] for o in ri["orgs"]]
    names = {c: v["org_name"] for c, v in allo.items()}
    st_key = "status_provisional" if state == "provisional" else "status_final"

    def sum_this(f):
        return sum((_w(by_org[o][f]) for o in orgs), D(0))

    def sum_ref(grp, f):
        return sum((_w(ref[o][grp][f]) for o in orgs), D(0))

    def src_this(f):
        return {"file": mfile, "path": f"{block_key}.by_org[*].{f}", "aggregation": f"Σ {len(orgs)}개 조직", "origin": f"각 조직 최종 제출본 {Y}-{M:02d} ACTUAL 상세 합(엑셀 출처 셀은 by_org[*].sources)"}

    def src_ref(grp, f):
        return {"file": mfile, "path": f"report_inputs.orgs[*].{grp}.{f}", "aggregation": f"Σ {len(orgs)}개 조직", "origin": ref[orgs[0]][grp]["origin"], "cell_refs": f"report_inputs.orgs[*].{grp}.refs"}

    def cell(won, formula, source):
        return {"value_won": won, "value_eok": eok(won), "formula": formula, "source": source}

    fields = ("revenue_won", "cost_won", "op_won")
    this_ = {f: sum_this(f) for f in fields}
    last_ = {f: sum_ref("last_month", f) for f in fields}
    ly_ = {f: sum_ref("last_year_same_month", f) for f in fields}
    plan_ = {f: sum_ref("plan_month", f) for f in fields}
    rows = []
    for f in fields:
        rows.append({
            "metric": LABEL[f],
            "last_year_same_month": {**cell(ly_[f], f"Σ 조직 {LABEL[f]} ({Y-1}-{M:02d} 실적)", src_ref("last_year_same_month", f)), "period": f"{Y-1}-{M:02d} ACTUAL"},
            "last_month": {**cell(last_[f], f"Σ 조직 {LABEL[f]} ({Y}-{M-1:02d} 실적)", src_ref("last_month", f)), "period": f"{Y}-{M-1:02d} ACTUAL"},
            "this_month": {**cell(this_[f], f"Σ 조직 {LABEL[f]} ({Y}-{M:02d} 실적)", src_this(f)), "period": f"{Y}-{M:02d} ACTUAL"},
            "plan_this_month": {**cell(plan_[f], f"Σ 조직 {LABEL[f]} ({Y}-{M:02d} 계획)", src_ref("plan_month", f)), "period": f"{Y}-{M:02d} PLAN"},
            "vs_last_month": {**_cmp(this_[f], last_[f]), "formula": "이번달 − 지난달(원 단위 차이를 억원으로 변환); 비율 = 차이 ÷ |지난달|"},
            "vs_last_year": {**_cmp(this_[f], ly_[f]), "formula": "이번달 − 작년 동월; 비율 = 차이 ÷ |작년 동월|"},
            "vs_plan": {**_cmp(this_[f], plan_[f]), "formula": "이번달 − 계획; 비율 = 차이 ÷ |계획|"},
        })
    op_check = this_["revenue_won"] - this_["cost_won"] == this_["op_won"]

    # 조직별 계획 대비 실적
    live = [g for g in gaps if g["status_in_final"] in ("ACTIVE", "CARRIED_OVER_ELIGIBLE")]
    org_rows = []
    for o in sorted(allo, key=lambda x: allo[x]["division"]):
        if o not in orgs:
            org_rows.append({"division": allo[o]["division"], "org_code": o, "org_name": names[o], "included": False,
                             "note": f"제출본 {allo[o]['approval_status']} → 잠정 집계에서 제외", "values": None})
            continue
        r = {"division": allo[o]["division"], "org_code": o, "org_name": names[o], "included": True, "values": {}}
        for f in fields:
            a, p = _w(by_org[o][f]), _w(ref[o]["plan_month"][f])
            r["values"][LABEL[f]] = {"plan_won": p, "plan_eok": eok(p), "actual_won": a, "actual_eok": eok(a), **{f"vs_plan_{k}": v for k, v in _cmp(a, p).items()}}
        r["source"] = {"actual": {"file": mfile, "path": f"{block_key}.by_org[{o}]", "cell_refs": f"{block_key}.by_org[{o}].sources"},
                       "plan": {"file": mfile, "path": f"report_inputs.orgs[{o}].plan_month", "origin": ref[o]["plan_month"]["origin"]}}
        r["gap_flags"] = [{"gap_type": g["gap_type"], "item": g["item"], "scenario": g["scenario"], "month": g["month"], "basis": g["basis"]} for g in live if g["org_code"] == o]
        org_rows.append(r)

    # 연간 영업이익 브리지
    bridge_rows = []
    plan_total = latest_total = D(0)
    for o in orgs:
        a = ref[o]["annual"]
        pw, lw = _w(a["plan_op_won"]), _w(a["latest_estimate_op_won"])
        bridge_rows.append({"org_code": o, "org_name": names[o], "delta_won": lw - pw})
        plan_total += pw; latest_total += lw
    bridge_rows.sort(key=lambda x: abs(x["delta_won"]), reverse=True)
    top, rest = bridge_rows[:5], bridge_rows[5:]
    asrc = {"file": mfile, "path": "report_inputs.orgs[*].annual.{plan_op_won,latest_estimate_op_won}", "origin": ref[orgs[0]]["annual"]["origin"]}
    steps = [{"label": "연간 계획", "value_won": plan_total, "value_eok": eok(plan_total), "type": "total", "source": asrc}]
    for r in top:
        steps.append({"label": f"{r['org_name']}({r['org_code']})", "delta_won": r["delta_won"], "delta_eok": eok(r["delta_won"]), "type": "delta", "source": asrc})
    rest_sum = sum((r["delta_won"] for r in rest), D(0))
    steps.append({"label": f"기타({len(rest)}개 조직)", "delta_won": rest_sum, "delta_eok": eok(rest_sum), "type": "delta", "source": asrc})
    steps.append({"label": "최신 추정", "value_won": latest_total, "value_eok": eok(latest_total), "type": "total", "source": asrc})
    total_delta = latest_total - plan_total
    bridge = {"title": "연간 영업이익 브리지", "unit": UNIT, "scope": f"{len(orgs)}개 조직", "steps": steps,
              "total_delta_won": total_delta, "total_delta_eok": eok(total_delta), "total_delta_percent": pct1(rate_percent(total_delta, plan_total)),
              "check_sum_ok": plan_total + sum((s["delta_won"] for s in steps if s["type"] == "delta"), D(0)) == latest_total,
              "formula": "조직 증감 = 최신 추정 연간 영업이익 − 연간 계획 영업이익. 큰 순(절대값) 5개 + 기타",
              "contingency": master_doc["contingency"]}

    # 소명 인라인 + 참고 소명
    items, reference = [], []
    for e in expl_links:
        vs = e.get("observed_item_change") or {}
        obs = vs.get("vs_plan") or vs.get("vs_baseline") or vs.get("vs_superseded_submission")
        status = e[st_key]
        rec = {
            "org_code": e["org_code"], "org_name": names[e["org_code"]], "item": e["item"], "year": e["year"], "month": e["month"], "scenario": e["scenario"],
            "observed_diff_won": obs["diff_won"] if obs else None, "observed_diff_eok": eok(obs["diff_won"]) if obs else None,
            "status": status, "status_label": "수락" if status == "수락" else "검토중",
            "text": e["latest_answer"] if status == "수락" else None,
            "history_label": _history_label(e["history"]), "has_resubmission_history": e["has_resubmission_history"],
            "history": [{"round": h["round"], "verdict": h["verdict"], "answer": h["answer"] if h["verdict"] == "ACCEPTED" else None, "request_note": h["request_note"]} for h in e["history"]] if e["has_resubmission_history"] else None,
            "link_type": e["link_type"], "do_not_invent_causal_amounts": e["do_not_invent_causal_amounts"], "included_in_scope": e["org_code"] in orgs,
        }
        if e["link_type"] == "NO_GAP":
            rec["label"] = "Gap 아님(참고 소명)"
            reference.append(rec)
        else:
            items.append(rec)
    key = lambda x: (allo[x["org_code"]]["division"], x["month"])
    items.sort(key=key); reference.sort(key=key)
    inline = {"title": "소명 사유 인라인", "rule": "수락된 소명은 문장 표시, 재소명 이력은 '1차 반려 → 2차 수락', 검토중·반려는 '검토중'(문장 숨김)",
              "accepted_count": sum(1 for i in items if i["status"] == "수락"), "pending_count": sum(1 for i in items if i["status"] != "수락"), "items": items}
    if show_reference_explanations:
        inline["reference_explanations"] = {"label": "Gap 아님 — 참고 소명(문서 규칙상 Gap 대상이 아닌 변동에 달린 소명)", "count": len(reference), "items": reference}
    else:
        inline["reference_explanations"] = {"label": "숨김(show_reference_explanations=false)", "count": len(reference), "items": []}

    # 비용 상세 (중분류 → 소분류)
    agg = defaultdict(lambda: {"this": D(0), "last": D(0), "plan": D(0)})
    for o in orgs:
        for c in ref[o]["cost_detail"]:
            k = (c["mid"], c["small"])
            agg[k]["this"] += _w(c["this_won"]); agg[k]["last"] += _w(c["last_won"]); agg[k]["plan"] += _w(c["plan_won"])
    mids = defaultdict(lambda: {"this": D(0), "last": D(0), "plan": D(0), "children": []})
    for (m1, m2), v in sorted(agg.items(), key=lambda kv: str(kv[0])):
        for k in ("this", "last", "plan"):
            mids[m1][k] += v[k]
        mids[m1]["children"].append({"name": m2, "this_month_eok": eok(v["this"]), "last_month_eok": eok(v["last"]), "plan_eok": eok(v["plan"]),
                                     "vs_last_month": _cmp(v["this"], v["last"]), "vs_plan": _cmp(v["this"], v["plan"])})
    cost_rows = [{"name": m1, "this_month_won": v["this"], "this_month_eok": eok(v["this"]), "last_month_eok": eok(v["last"]), "plan_eok": eok(v["plan"]),
                  "vs_last_month": _cmp(v["this"], v["last"]), "vs_plan": _cmp(v["this"], v["plan"]), "children": v["children"]} for m1, v in sorted(mids.items(), key=lambda kv: str(kv[0]))]
    cost_total = sum((r["this_month_won"] for r in cost_rows), D(0))
    cost = {"title": "비용 상세", "unit": UNIT, "level_reached": "중분류 → 소분류(상세 시트 값 합산)", "rows": cost_rows,
            "source": {"file": mfile, "path": "report_inputs.orgs[*].cost_detail", "origin": ref[orgs[0]]["cost_detail_origin"]},
            "limits": "'대분류'(마케팅비용 등)는 룰 파서가 차원으로 내보내지 않아 모름. 계정·팀·과제 단위는 포함하지 않음",
            "total_this_month_eok": eok(cost_total), "total_matches_master_cost": cost_total == this_["cost_won"]}
    return {
        "state": state, "data_status": "잠정" if state == "provisional" else "확정",
        "input_master_file": mfile, "all_numbers_from_master_file": True,
        "master_version": master_doc["master"]["version"] if master_doc.get("master") else None,
        "master_note": f"master 생성 전({master_doc['approval_label']}) · 잠정 워터마크 · 미수락 소명은 '검토중'" if state == "provisional" else "master 생성 완료 · 확정",
        "base_month": f"{Y}-{M:02d}", "unit": UNIT, "rounding": "소수 1자리 ROUND_HALF_UP(common.eok·pct1 한 곳)",
        "scope": {"orgs": len(orgs), "org_codes": orgs, "excluded_orgs": [o for o in allo if o not in orgs]},
        "show_reference_explanations": show_reference_explanations,
        "figma_ref": {"file": "ZW7ZlgvtY4phycygZqir3z", "node": "2:1916 07 보고서 자료 뽑는 화면", "note": "흐름 기준. 단위·이름은 데이터를 따름"},
        "summary_table": {"title": "손익 요약표", "subtitle": f"{Y}년 {M}월 · 전사({len(orgs)}개 조직) · {UNIT}", "rows": rows, "op_equals_revenue_minus_cost": op_check},
        "org_plan_vs_actual": {"title": "조직별 계획 대비 실적", "unit": UNIT, "rows": org_rows},
        "op_bridge": bridge, "explanation_inline": inline,
        "weekly_revenue_growth": {"title": "주별 매출 증감률", "status": "데이터 없음", "reason": "입력 엑셀에 주 단위 값이 없어 만들지 않음(월별 상세만 존재)"},
        "cost_detail": cost,
    }
