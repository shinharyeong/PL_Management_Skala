"""단계 2: 소명(explanations.json)을 Gap에 연결하고, 시나리오 25건과 대조한다.

- explanations.json 의 gap_key("F10:REV030:2026:11:ACTUAL")의 REV030/OPEX002 는 상세 시트 행을 가리키는 코드다.
  02_scenarios/NN_*.json 의 gap_items[].comparisons[].source_cell 로 시트·셀을 해석하고, 최종본의 같은 셀 보고 항목(관리분류/실적집계기준)으로 바꾼다.
- 같은 gap_key 의 모든 회차를 history 로 보존하고, 현재 상태는 최신 회차의 판정으로 정한다.
"""
from __future__ import annotations

import csv
import json
import re
from collections import defaultdict
from decimal import Decimal

from .common import REQUEST_MONTH, REQUEST_YEAR, inputs, nfc
from .gap_stage import Idx

SC_LOW = {"ACTUAL": "actual", "ESTIMATE": "estimate"}


def load_explanations() -> list[dict]:
    return json.loads(inputs()["EXPLANATIONS_PATH"].read_text(encoding="utf-8"))


def load_key_cells() -> dict:
    """(조직코드, legacy gap_key) -> {sheet, cell, bases}"""
    out = {}
    for f in sorted(inputs()["SCENARIO_DIR"].glob("[0-9][0-9]_*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        for gi in d["gap_items"]:
            c = gi["comparisons"][0]
            out[gi["gap_key"]] = {"division": d["division"], "org": d["organization_code"], "sheet": nfc(c["source_sheet"]), "cell": c["source_cell"],
                                  "bases": [x["basis"] for x in gi["comparisons"]], "scenario_amounts": [(x["basis"], x["baseline_won"], x["target_won"], x["gap_amount_won"]) for x in gi["comparisons"]]}
    return out


def load_targets() -> list[dict]:
    rows, seen = [], set()
    with open(inputs()["SCENARIO_CSV_PATH"], encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            k = (r["사업부"], nfc(r["원본시트"]), r["원본셀"])
            if k in seen:
                continue
            seen.add(k)
            rows.append({"division": int(r["사업부"].replace("사업부", "")), "org_code": r["조직코드"], "sheet": nfc(r["원본시트"]), "cell": r["원본셀"],
                         "month": int(r["대상월"]), "scenario": r["실적_추정"], "csv_basis": r["비교기준"],
                         "csv_diff_won": Decimal(r["차액_천원"]) * 1000, "csv_prev_won": Decimal(r["기준금액_천원"]) * 1000, "csv_cur_won": Decimal(r["변경금액_천원"]) * 1000})
    return rows


def _item_at(sub: dict, sheet: str, cell: str):
    for a in sub["amounts"]:
        if a["sheet"] == sheet and a["cell"] == cell:
            return a["kind"], a["item"], a["y"], a["mo"], a["sc"], a["amount"]
    return None


def observed_change(m: dict, kind, item, y, mo, sc) -> dict:
    """항목 단위 관측 변경(Gap 판정이 아님). 계획·직전 제출본·baseline 과의 금액 차이."""
    final = m["FINAL"]
    fi = Idx(final)
    cur = fi.items.get((kind, item, y, mo, sc))
    out = {"current_won": cur["total"] if cur else None}

    def cmp(sub, scn, label):
        if sub is None:
            return None
        e = Idx(sub).items.get((kind, item, y, mo, scn))
        if not e or not cur:
            return None
        return {"compare_submission_id": sub["submission_id"], "scenario": scn.upper(), "previous_won": e["total"], "diff_won": cur["total"] - e["total"]}
    out["vs_plan"] = cmp(m["PLAN"], "plan", "PLAN") if (sc == "actual" and mo == REQUEST_MONTH) else None
    out["vs_baseline"] = cmp(m["BASELINE"], sc, "BASELINE")
    out["vs_superseded_submission"] = cmp(m.get("SUPERSEDED"), sc, "PREV")
    return out


def op_gaps_contributed(obs: dict | None, live: list, org: str, y: int, mo: int, SC: str) -> list:
    """항목 변동이 해당 영업이익 Gap 의 비교(같은 비교 제출본)에서 0이 아닌 차이를 만든 경우만 연결."""
    if not obs:
        return []
    ids = {x["compare_submission_id"] for x in (obs.get("vs_plan"), obs.get("vs_baseline"), obs.get("vs_superseded_submission")) if x and x["diff_won"] != 0}
    return [g for g in live if g["gap_type"] == "OP_GAP" and g["org_code"] == org and g["year"] == y and g["month"] == mo and g["scenario"] == SC
            and g["compare_submission_id"] in ids]


def link(gaps_result: dict, subs: dict, snapshots: dict) -> dict:
    expl = load_explanations()
    keycells = load_key_cells()
    by_key = defaultdict(list)
    for e in expl:
        by_key[e["gap_key"]].append(e)
    gaps = gaps_result["gaps"]
    live = [g for g in gaps if g["status_in_final"] in ("ACTIVE", "CARRIED_OVER_ELIGIBLE")]
    prov_status = {s["organization_code"]: s["status"] for s in snapshots["before_last_approval"]["sources"]}
    final_status = {s["organization_code"]: s["status"] for s in snapshots["all_approved"]["sources"]}
    exp_links = []
    for gk, items in sorted(by_key.items()):
        org, legacy, y, mo, SC = gk.split(":")
        y, mo = int(y), int(mo)
        kc = keycells.get(gk)
        m = subs[org]
        kind = item = None
        cell_info = None
        if kc:
            hit = _item_at(m["FINAL"], kc["sheet"], kc["cell"])
            if hit:
                kind, item = hit[0], hit[1]
                cell_info = {"sheet": kc["sheet"], "cell": kc["cell"], "final_submission_cell_amount_won": hit[5]}
        hist = sorted(items, key=lambda x: x["round"])
        history = [{"round": h["round"], "answer": h["answer"], "verdict": h["verdict"], "request_note": h.get("request_note"),
                    "explanation_id": h["id"], "submission_id": h["submission_id"]} for h in hist]
        latest = hist[-1]
        direct = [g for g in live if g["gap_type"] == "AMOUNT_REVISION" and g["org_code"] == org and g["item"] == item and g["year"] == y and g["month"] == mo and g["scenario"] == SC]
        obs = observed_change(m, kind, item, y, mo, SC_LOW[SC]) if item else None
        op_links = op_gaps_contributed(obs, live, org, y, mo, SC)
        link_type = "DIRECT_ITEM_REVISION" if direct else ("CONTRIBUTES_TO_OP_GAP" if op_links else "NO_GAP")
        links = [{"gap_id": g["gap_id"], "role": "DIRECT_ITEM_REVISION"} for g in direct] + [{"gap_id": g["gap_id"], "role": "CONTRIBUTES_TO_OP_GAP"} for g in op_links]
        exp_links.append({
            "legacy_gap_key": gk, "org_code": org, "year": y, "month": mo, "scenario": SC, "kind": kind, "item": item, "source": cell_info,
            "history": history, "rounds": len(hist), "has_resubmission_history": len(hist) > 1,
            "latest_round": latest["round"], "latest_verdict": latest["verdict"], "latest_answer": latest["answer"],
            "do_not_invent_causal_amounts": bool(latest.get("do_not_invent_causal_amounts")),
            "amount_attribution": latest.get("amount_attribution"),
            "status_final": "수락" if latest["verdict"] == "ACCEPTED" and final_status.get(org) == "APPROVED" else "검토중",
            "status_provisional": "수락" if latest["verdict"] == "ACCEPTED" and prov_status.get(org) == "APPROVED" else "검토중",
            "org_status_provisional": prov_status.get(org), "org_status_final": final_status.get(org),
            "link_type": link_type, "links": links, "linked_gap_ids": [l["gap_id"] for l in links],
            "observed_item_change": obs,
        })
    # Gap 쪽에 소명 붙이기
    by_gap = defaultdict(list)
    for e in exp_links:
        for l in e["links"]:
            by_gap[l["gap_id"]].append((e, l["role"]))
    out_gaps = []
    for g in gaps:
        es = by_gap.get(g["gap_id"], [])
        gg = dict(g)
        if es:
            gg["explanations"] = [{"legacy_gap_key": e["legacy_gap_key"], "link_role": role, "item": e["item"], "history": e["history"],
                                   "latest_verdict": e["latest_verdict"], "status_final": e["status_final"], "status_provisional": e["status_provisional"],
                                   "do_not_invent_causal_amounts": e["do_not_invent_causal_amounts"]} for e, role in es]
            gg["explanation_status"] = "연결됨"
        else:
            gg["explanations"] = []
            gg["explanation_status"] = "소명 없음 → 검토중" if g["status_in_final"] in ("ACTIVE", "CARRIED_OVER_ELIGIBLE") else "대체됨(step B)"
        out_gaps.append(gg)
    live_gaps = [g for g in out_gaps if g["status_in_final"] in ("ACTIVE", "CARRIED_OVER_ELIGIBLE")]
    counts = {
        "gaps_total": len(out_gaps), "gaps_live": len(live_gaps),
        "gaps_with_explanation": sum(1 for g in live_gaps if g["explanations"]),
        "item_revision_gaps": sum(1 for g in live_gaps if g["gap_type"] == "AMOUNT_REVISION"),
        "item_revision_gaps_with_explanation": sum(1 for g in live_gaps if g["gap_type"] == "AMOUNT_REVISION" and g["explanations"]),
        "op_gaps": sum(1 for g in live_gaps if g["gap_type"] == "OP_GAP"),
        "op_gaps_with_contributing_explanation": sum(1 for g in live_gaps if g["gap_type"] == "OP_GAP" and g["explanations"]),
        "gaps_without_explanation": sum(1 for g in live_gaps if not g["explanations"]),
        "explanation_keys": len(exp_links), "explanation_rows": len(expl),
        "explanations_linked_direct": sum(1 for e in exp_links if e["link_type"] == "DIRECT_ITEM_REVISION"),
        "explanations_linked_op_gap_only": sum(1 for e in exp_links if e["link_type"] == "CONTRIBUTES_TO_OP_GAP"),
        "explanations_also_contributing_to_op_gap": sum(1 for e in exp_links if e["link_type"] == "DIRECT_ITEM_REVISION" and any(l["role"] == "CONTRIBUTES_TO_OP_GAP" for l in e["links"])),
        "explanations_without_gap": sum(1 for e in exp_links if e["link_type"] == "NO_GAP"),
        "keys_with_resubmission_history": sum(1 for e in exp_links if e["has_resubmission_history"]),
    }
    return {"gaps": out_gaps, "explanations": exp_links, "counts": counts}


def scenario_check(gaps_result: dict, subs: dict) -> dict:
    """시나리오 25건(소명사유_전체.csv)과 Gap 결과를 대조한다."""
    targets = load_targets()
    live = [g for g in gaps_result["gaps"] if g["status_in_final"] in ("ACTIVE", "CARRIED_OVER_ELIGIBLE")]
    rows, used = [], set()
    for t in targets:
        org = t["org_code"]
        hit = _item_at(subs[org]["FINAL"], t["sheet"], t["cell"])
        kind, item, y, mo, sc, cur = hit[0], hit[1], hit[2], hit[3], hit[4], hit[5]
        SC = sc.upper()
        direct = [g for g in live if g["gap_type"] == "AMOUNT_REVISION" and g["org_code"] == org and g["item"] == item and g["year"] == y and g["month"] == mo and g["scenario"] == SC]
        obs = observed_change(subs[org], kind, item, y, mo, sc)
        op = op_gaps_contributed(obs, live, org, y, mo, SC)
        # CSV 의 기대 차액과 관측 차액 일치 여부(비교 기준 가능한 것 중 하나라도)
        diffs = [x["diff_won"] for x in (obs["vs_plan"], obs["vs_baseline"], obs["vs_superseded_submission"]) if x]
        for g in direct + op:
            used.add(g["gap_id"])
        rows.append({
            "division": t["division"], "org_code": org, "sheet": t["sheet"], "cell": t["cell"], "year": y, "month": mo, "scenario": SC,
            "item": item, "csv_basis": t["csv_basis"], "csv_diff_won": t["csv_diff_won"],
            "observed_matches_csv_diff": any(df == t["csv_diff_won"] for df in diffs),
            "result": "찾음(항목 수정 Gap)" if direct else ("간접(영업이익 Gap에 기여)" if op else "못 찾음"),
            "item_gap_ids": [g["gap_id"] for g in direct], "op_gap_ids": [g["gap_id"] for g in op],
            "why_if_not_item_gap": None if direct else _why(t, mo, SC),
            "observed_item_change": obs,
        })
    extra = [g for g in live if g["gap_id"] not in used]
    return {"rows": rows, "extra_gaps": extra,
            "summary": {"targets": len(rows), "found_item_gap": sum(1 for r in rows if r["result"].startswith("찾음")),
                        "indirect_op_only": sum(1 for r in rows if r["result"].startswith("간접")),
                        "not_found": sum(1 for r in rows if r["result"] == "못 찾음"), "extra_gaps": len(extra)}}


def _why(t, mo, SC):
    if SC == "ACTUAL" and mo == REQUEST_MONTH:
        return ("이번 달 실적 항목(매출·비용)의 계획 대비 증감은 Gap 대상이 아님: domain-rules §4 는 계획 대비 Gap을 영업이익(OP)에만 적용하고, "
                "직전 제출본에 같은 연월·시나리오(ACTUAL)가 없으면(직전에는 11월이 ESTIMATE) 원천 금액 '수정'도 아님")
    return "원인 불명 — 출력 확인 필요"
