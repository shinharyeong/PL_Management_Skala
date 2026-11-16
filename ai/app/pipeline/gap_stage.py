"""단계 1: docs/domain-rules.md §4 의 Gap 규칙 구현.

- 원천 금액 수정(AMOUNT_REVISION): 직전 제출본에 같은 연월·시나리오가 있는 보고 항목의 상세 금액 구성(multiset)이 다르면 임계값과 무관하게 소명 필수.
- 영업이익 Gap(OP_GAP_*): 영업이익에만 적용. 퍼센트·금액 모두 엄격 초과(>)일 때 기준 초과. 비교 값이 0이고 이번 값이 0이 아니면 기준 초과(Gap률 '기준 없음').
- 비교 기준: 해당 월 실적=계획(같은 달 재제출이면 직전도), 추정=직전의 같은 월 추정, 과거 달 실적=직전의 같은 월 실적.
이 문서에 없는 규칙은 추가하지 않는다.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from decimal import Decimal

from .common import REQUEST_MONTH, REQUEST_YEAR, rate_percent
from .parse_stage import DESC_DIMS

SC_UP = {"actual": "ACTUAL", "estimate": "ESTIMATE", "plan": "PLAN"}


def _meaning(a: dict) -> tuple:
    dims = tuple(sorted((k, v) for k, v in a["dims"].items() if k not in DESC_DIMS))
    return (a["icode"], a["iname"], dims)


class Idx:
    """제출본 한 개의 조회용 색인."""

    def __init__(self, sub: dict):
        self.sub = sub
        self.items = defaultdict(lambda: {"ms": Counter(), "total": Decimal(0), "cells": []})
        self.tot = defaultdict(lambda: {"REVENUE": Decimal(0), "COST": Decimal(0), "n": 0})
        self.bad_items = 0
        for a in sub["amounts"]:
            if a["item"] is None:
                self.bad_items += 1
                continue
            e = self.items[(a["kind"], a["item"], a["y"], a["mo"], a["sc"])]
            e["ms"][(_meaning(a), a["amount"])] += 1
            e["total"] += a["amount"]
            e["cells"].append((a["sheet"], a["cell"]))
            t = self.tot[(a["y"], a["mo"], a["sc"])]
            t[a["kind"]] += a["amount"]
            t["n"] += 1

    def op(self, y, mo, sc):
        t = self.tot.get((y, mo, sc))
        if not t or not t["REVENUE"] and not t["COST"]:
            return None
        return {"REVENUE": t["REVENUE"], "COST": t["COST"], "OP": t["REVENUE"] - t["COST"]}

    def keys(self):
        return self.items.keys()


def _judge(cur: Decimal, base: Decimal, th: dict):
    diff = cur - base
    rate = rate_percent(diff, base)
    if base == 0:
        exceeded = cur != 0
        why = "기준값 0 → 이번 값이 0이 아니면 기준과 무관하게 초과(Gap률 '기준 없음')" if cur != 0 else "기준값 0, 이번 값 0"
    else:
        exceeded = abs(rate) > th["percent"] and abs(diff) > th["amount_won"]
        why = f"|{rate:.4f}%| > {th['percent']}% 그리고 |{diff}| > {th['amount_won']} 원 → {'둘 다 초과' if exceeded else '하나 이상 이하'}"
    return diff, rate, exceeded, why


def compare(cur_sub: dict, prev_sub: dict, plan_sub: dict, step: str, th: dict) -> dict:
    cur, prev, plan = Idx(cur_sub), Idx(prev_sub), Idx(plan_sub)
    org = cur_sub["org"]
    gaps, ops, issues = [], [], []
    cid, pid = cur_sub["submission_id"], prev_sub["submission_id"]

    # 1) 원천 금액 수정: 같은 연월·시나리오(ACTUAL/ESTIMATE)의 보고 항목별 상세 구성 비교
    for key in sorted(set(cur.keys()) | set(prev.keys()), key=str):
        kind, item, y, mo, sc = key
        if y != REQUEST_YEAR or sc not in ("actual", "estimate"):
            continue
        c, p = cur.items.get(key), prev.items.get(key)
        if c is None:
            # 이번 제출본에 같은 연월·시나리오 자체가 없으면(예: 추정 → 실적 전환) 누락이 아님
            if not any(k[2:] == (y, mo, sc) for k in cur.keys()):
                continue
            issues.append({"type": "MISSING_IN_CURRENT", "key": [kind, item, y, mo, sc], "note": "직전에는 있고 이번에는 없음. 0이나 삭제로 취급하지 않음"})
            continue
        if p is None:
            # 직전에 같은 연월·시나리오가 아예 없으면 새로 들어온 달 → 수정 아님
            if not any(k[2:] == (y, mo, sc) for k in prev.keys()):
                continue
            issues.append({"type": "ITEM_NOT_IN_PREVIOUS_SAME_PERIOD", "key": [kind, item, y, mo, sc]})
            continue
        if c["ms"] == p["ms"]:
            continue
        diff = c["total"] - p["total"]
        added = c["ms"] - p["ms"]
        removed = p["ms"] - c["ms"]
        gaps.append({
            "gap_type": "AMOUNT_REVISION", "reason_code": "AMOUNT_REVISION" + ("_OFFSETTING" if diff == 0 else ""),
            "step": step, "org_code": org, "org_name": cur_sub["org_name"], "item_kind": kind, "item": item,
            "year": y, "month": mo, "scenario": SC_UP[sc], "basis": "PREVIOUS", "current_submission_id": cid, "compare_submission_id": pid,
            "previous_value_won": p["total"], "current_value_won": c["total"], "diff_won": diff,
            "rate_percent": rate_percent(diff, p["total"]),
            "threshold": None, "threshold_exceeded": None,
            "judgement": "원천 금액 수정은 임계값과 무관하게 소명 필수(domain-rules §4)",
            "explanation_required": True,
            "changed_detail": {"added": [{"meaning": [m[0][0], m[0][1]], "amount_won": m[1], "n": n} for m, n in added.items()],
                               "removed": [{"meaning": [m[0][0], m[0][1]], "amount_won": m[1], "n": n} for m, n in removed.items()]},
            "source_cells": {"current": c["cells"], "previous": p["cells"]},
            "attribute_changes": _attr_changes(added, removed),
        })

    # 2) 영업이익 Gap
    for (y, mo, sc) in sorted(k for k in cur.tot if k[0] == REQUEST_YEAR and k[2] in ("actual", "estimate")):
        cur_op = cur.op(y, mo, sc)
        if cur_op is None:
            continue
        th_key = SC_UP[sc]
        comps = []
        if sc == "actual":
            if mo == REQUEST_MONTH:
                comps.append(("PLAN", plan, "plan", "OP_GAP_ACTUAL_VS_PLAN", plan_sub))
                if prev.op(y, mo, "actual"):
                    comps.append(("PREVIOUS_SAME_MONTH", prev, "actual", "OP_GAP_ACTUAL_VS_PREVIOUS_SAME_MONTH", prev_sub))
            else:
                if prev.op(y, mo, "actual"):
                    comps.append(("PREVIOUS", prev, "actual", "OP_GAP_PAST_ACTUAL_VS_PREVIOUS", prev_sub))
        else:
            if prev.op(y, mo, "estimate"):
                comps.append(("PREVIOUS", prev, "estimate", "OP_GAP_ESTIMATE_VS_PREVIOUS", prev_sub))
            else:
                issues.append({"type": "COMPARISON_UNAVAILABLE", "key": [y, mo, sc], "note": "직전 추정이 없음. 계획·0으로 대체하지 않음"})
        for basis, idx, bsc, code, bsub in comps:
            b = idx.op(y, mo, bsc)
            if b is None:
                issues.append({"type": "COMPARISON_UNAVAILABLE", "key": [y, mo, sc, basis]})
                continue
            diff, rate, exceeded, why = _judge(cur_op["OP"], b["OP"], th[th_key])
            rec = {
                "gap_type": "OP_GAP", "reason_code": code if exceeded else code + "_NOT_EXCEEDED", "step": step,
                "org_code": org, "org_name": cur_sub["org_name"], "item_kind": "OP", "item": "영업이익",
                "year": y, "month": mo, "scenario": th_key, "basis": basis, "current_submission_id": cid,
                "compare_submission_id": bsub["submission_id"],
                "previous_value_won": b["OP"], "current_value_won": cur_op["OP"], "diff_won": diff,
                "rate_percent": rate, "rate_note": None if rate is not None else "기준 없음",
                "threshold": {"percent": th[th_key]["percent"], "amount_won": th[th_key]["amount_won"]},
                "threshold_exceeded": exceeded, "judgement": why, "explanation_required": exceeded,
                "formula": "영업이익 = 총매출 − 총영업비용 (원천 상세 시트 합계)",
                "current_parts": {"revenue_won": cur_op["REVENUE"], "cost_won": cur_op["COST"]},
                "basis_parts": {"revenue_won": b["REVENUE"], "cost_won": b["COST"]},
            }
            ops.append(rec)
            if exceeded:
                gaps.append(rec)
    for g in gaps:
        g["gap_key"] = f"{g['org_code']}:{g['item']}:{g['year']}:{g['month']}:{g['scenario']}"
    return {"gaps": gaps, "op_comparisons": ops, "issues": issues, "cur_index": cur, "prev_index": prev}


def _attr_changes(added, removed):
    """금액은 같고 속성(예산코드 등)만 바뀐 경우를 보이기 위한 속성 차이."""
    out = []
    ra = {m[0][0]: m for m in removed}
    for m in added:
        o = ra.get(m[0][0])
        if o:
            da, do = dict(m[0][2]), dict(o[0][2])
            ch = {k: [do.get(k), da.get(k)] for k in set(da) | set(do) if da.get(k) != do.get(k)}
            if ch or m[1] != o[1]:
                out.append({"icode": m[0][0], "attributes": ch, "amount_won": [o[1], m[1]]})
    return out


def run(subs: dict, th: dict) -> dict:
    steps, all_gaps, all_ops, all_issues = [], [], [], []
    per_org = {}
    for org, m in subs.items():
        final, base, plan = m["FINAL"], m["BASELINE"], m["PLAN"]
        sup = m.get("SUPERSEDED")
        chain = []
        if sup:
            a = compare(sup, base, plan, "A_seq1_vs_baseline", th)
            b = compare(final, sup, plan, "B_final_vs_seq1", th)
            chain = [(a, "A_seq1_vs_baseline", sup), (b, "B_final_vs_seq1", final)]
            fidx, sidx = b["cur_index"], a["cur_index"]
            for g in a["gaps"]:
                g["status_in_final"] = _carry_status(g, fidx, sidx, b["gaps"])
            for g in b["gaps"]:
                g["status_in_final"] = "ACTIVE"
        else:
            a = compare(final, base, plan, "final_vs_baseline", th)
            chain = [(a, "final_vs_baseline", final)]
            for g in a["gaps"]:
                g["status_in_final"] = "ACTIVE"
        excl = m.get("EXCLUDED")
        for res, step, cs in chain:
            all_gaps += res["gaps"]
            all_ops += res["op_comparisons"]
            all_issues += [{"org": org, "step": step, **i} for i in res["issues"]]
        steps.append({
            "org_code": org, "org_name": m["_meta"]["name"], "chain": [s for _, s, _ in chain],
            "baseline": base["submission_id"], "plan": plan["submission_id"],
            "superseded_previous": sup["submission_id"] if sup else None, "final": final["submission_id"],
            "excluded_from_comparison": [{"submission_id": excl["submission_id"], "reason": excl.get("excluded_reason"),
                                          "parser_invalid_detail_records": excl["quality"]["business_validation"].get("invalid", 0)}] if excl else [],
        })
        per_org[org] = {"final_index": chain[-1][0]["cur_index"]}
    for g in all_gaps:
        g["gap_id"] = f"{g['org_code']}|{g['item_kind']}|{g['item']}|{g['year']}-{g['month']:02d}|{g['scenario']}|{g['basis']}|{g['step']}"
    return {"steps": steps, "gaps": all_gaps, "op_comparisons": all_ops, "issues": all_issues, "per_org": per_org}


def _carry_status(g, fidx: Idx, sidx: Idx, b_gaps) -> str:
    """step A 의 Gap 이 최종본에서 그대로 이어지는지(이어받기) 판단."""
    if any(x["gap_key"] == g["gap_key"] and x["basis"] == g["basis"] for x in b_gaps):
        return "SUPERSEDED_BY_STEP_B"
    sc = {"ACTUAL": "actual", "ESTIMATE": "estimate"}[g["scenario"]]
    if g["gap_type"] == "AMOUNT_REVISION":
        key = (g["item_kind"], g["item"], g["year"], g["month"], sc)
        same = fidx.items.get(key) and sidx.items.get(key) and fidx.items[key]["ms"] == sidx.items[key]["ms"]
    else:
        a, b = fidx.op(g["year"], g["month"], sc), sidx.op(g["year"], g["month"], sc)
        same = a and b and a["OP"] == b["OP"]
    return "CARRIED_OVER_ELIGIBLE" if same else "CHANGED_IN_FINAL"
