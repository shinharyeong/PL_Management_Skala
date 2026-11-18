"""보고서 화면용 데이터 조립(07 화면·Gap 화면 공통). 파이프라인 결과 JSON(dict)을 받아 화면에 필요한 구조로 묶는다.

- 숫자는 입력 JSON 값을 그대로 쓴다(재계산·재반올림 없음). 이연/실제 총액 변화 판정만 원 단위 정수로 계산한다.
- 문장은 narratives 서비스를 쓴다: 기본은 템플릿(또는 캐시된 AI 결과), allow_ai=True 일 때만 확정 버전에 한해 최대 4회 호출한다.
- 나중에 백엔드의 소명·마스터·보고 API(REV-01, MAS-04, REP-01) 응답으로 입력 dict 를 채우면 같은 함수가 그대로 동작한다.
"""
from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal

from . import ai_reasons
from . import narratives as nv

CLS = {"DIRECT_ITEM_REVISION": ("item", "항목 수정"), "CONTRIBUTES_TO_OP_GAP": ("op", "영업이익 기여"), "NO_GAP": ("ref", "Gap 아님")}
KM_ORDER = {"영업이익": 0, "매출": 0, "비용": 0, "소명": 1, "연간": 2, "주의": 3}


def won(s) -> str:
    s = str(s)
    return ("-" if s.startswith("-") else "") + f"{int(s.lstrip('-')):,}원"


def sd(o: dict) -> str:
    """JSON 의 방향·차이·% 문자열을 사실 문장용으로 붙인다(재계산 없음)."""
    d = o.get("direction") or o.get("vs_plan_direction")
    sg = "+" if d == "▲" else "−" if d == "▼" else ""
    diff = o.get("diff_eok", o.get("vs_plan_diff_eok"))
    pct = o.get("percent", o.get("vs_plan_percent"))
    note = o.get("percent_note", o.get("vs_plan_percent_note"))
    ptxt = f"{sg}{str(pct).lstrip('-')}%" if pct is not None else (note or "기준 없음")
    return f"{sg}{str(diff).lstrip('-')}억원({ptxt})"


def ym_label(base: str) -> str:
    y, m = base.split("-")
    return f"{y}년 {int(m)}월"


def _part(e: dict):
    """이연 판정에 쓰는 같은 항목의 변동 한 조각: 11월 실적(계획 대비) 또는 추정 수정(직전 추정 대비)."""
    o = e["observed_item_change"] or {}
    if e["scenario"] == "ACTUAL" and e["month"] == 11 and o.get("vs_plan"):
        return ("당월 실적(계획 대비)", int(o["vs_plan"]["diff_won"]))
    if e["scenario"] == "ESTIMATE" and o.get("vs_baseline"):
        return (f"{e['month']}월 추정(직전 추정 대비)", int(o["vs_baseline"]["diff_won"]))
    return None


def judge_deferral(group: list[dict]) -> tuple[str, str]:
    parts = [p for p in (_part(e) for e in group) if p]
    has_act = any(p[0].startswith("당월") for p in parts)
    has_est = any("추정" in p[0] for p in parts)
    if not (has_act and has_est):
        return "판정 불가", "같은 항목의 당월 실적 변동과 추정 수정이 JSON 에 함께 없음(" + (", ".join(f"{p[0]} {p[1]:+,}원" for p in parts) or "변동 근거 없음") + ")"
    total = sum(p[1] for p in parts)
    expr = " + ".join(f"{p[0]} {p[1]:+,}원" for p in parts)
    return ("이연" if total == 0 else "실제 총액 변화"), f"{expr} = {total:+,}원 · 전제: 직전 추정=계획 여부는 JSON 에서 확인 불가"


def build_records(G: dict, RF: dict) -> tuple[list[dict], list[dict]]:
    """소명 기록(키 단위)과 Gap 목록에 화면용 분류·판정·검색 필드를 붙인다."""
    rep_items = {}
    for it in RF["explanation_inline"]["items"] + RF["explanation_inline"]["reference_explanations"]["items"]:
        rep_items[(it["org_code"], it["item"], it["year"], it["month"], it["scenario"])] = it
    org = {r["org_code"]: r["org_name"] for r in RF["org_plan_vs_actual"]["rows"]}
    groups: dict = {}
    for e in G["explanations"]:
        groups.setdefault((e["org_code"], e["item"]), []).append(e)
    verdict = {k: judge_deferral(v) for k, v in groups.items()}
    records = []
    for e in G["explanations"]:
        gt, gc = CLS[e["link_type"]]
        o = e["observed_item_change"] or {}
        rep = rep_items.get((e["org_code"], e["item"], e["year"], e["month"], e["scenario"]), {})
        cands = [("계획 대비", o.get("vs_plan")), ("직전 제출본 대비", o.get("vs_baseline")), ("재제출 전 제출본 대비", o.get("vs_superseded_submission"))]
        cands = [(k, v) for k, v in cands if v]
        basis = next((k for k, v in cands if v["diff_won"] == rep.get("observed_diff_won")), cands[0][0] if cands else "")
        dfr, dfr_basis = verdict[(e["org_code"], e["item"])]
        src = e["source"] or {}
        records.append({
            "id": e["legacy_gap_key"], "org_code": e["org_code"], "org_name": org.get(e["org_code"], e["org_code"]),
            "kind": "매출" if e["kind"] == "REVENUE" else "영업비용", "item": e["item"], "year": e["year"], "month": e["month"],
            "ym": f"{e['year']}-{e['month']:02d}", "scenario": e["scenario"], "gtype": gt, "gclass": gc, "link_type": e["link_type"],
            "st_final": [e["status_final"]], "st_prov": [e["status_provisional"]], "latest_verdict": e["latest_verdict"],
            "history": e["history"], "resub": len(e["history"]) > 1, "latest_answer": e["latest_answer"],
            "diff_won": rep.get("observed_diff_won"), "diff_eok": rep.get("observed_diff_eok"), "diff_basis": basis,
            "diff_basis_full": " · ".join(f"{k} {won(v['diff_won'])}" for k, v in cands),
            "current_won": o.get("current_won"), "dfr": [dfr], "dfr_basis": dfr_basis, "sheet": src.get("sheet"), "cell": src.get("cell"),
            "txt": f"{org.get(e['org_code'], '')} {e['item']} {e['latest_answer'] or ''} {e['legacy_gap_key']}",
        })
    rec = {r["id"]: r for r in records}
    gaps = []
    for g in G["gaps"]:
        g2 = dict(g)
        g2["ym"] = f"{g['year']}-{g['month']:02d}"
        linked = [rec[x["legacy_gap_key"]] for x in g["explanations"] if x["legacy_gap_key"] in rec]
        g2["gtype"] = "item" if g["gap_type"] == "AMOUNT_REVISION" else "op"
        g2["kind"] = "영업이익" if g["gap_type"] == "OP_GAP" else (linked[0]["kind"] if linked else "")
        g2["st_final"] = [x["status_final"] for x in g["explanations"]]
        g2["st_prov"] = [x["status_provisional"] for x in g["explanations"]]
        g2["dfr"] = sorted({d for r in linked for d in r["dfr"]})
        g2["resub"] = any(r["resub"] for r in linked)
        g2["txt"] = f"{g['org_name']} {g['item']} " + " ".join(r["latest_answer"] or "" for r in linked)
        gaps.append(g2)
    return records, gaps


# ------------------------------------------------------------------ 문장용 사실 목록
def cards_facts(state, r, m, G):
    base = ym_label(r["base_month"])
    op = next(x for x in r["summary_table"]["rows"] if x["metric"] == "영업이익")
    b = r["op_bridge"]
    d, dp = b["total_delta_eok"], b["total_delta_percent"]
    c, e = G["counts"], r["explanation_inline"]
    ver = (m.get("master") or {}).get("version")
    sg = lambda x: "−" if x.startswith("-") else "+"
    items = [
        ("summary", "손익", f"{base} 전사 영업이익 실적은 {op['this_month']['value_eok']}억원이며 계획 {op['plan_this_month']['value_eok']}억원 대비 {sd(op['vs_plan'])}입니다."),
        ("bridge", "연간", f"연간 영업이익은 계획 {b['steps'][0]['value_eok']}억원에서 최신 추정 {b['steps'][-1]['value_eok']}억원으로 {sg(d)}{d.lstrip('-')}억원({sg(dp)}{dp.lstrip('-')}%) 차이가 납니다."),
        ("org", "조직", f"전사 {r['scope']['orgs']}개 조직 기준이며 Gap은 {c['gaps_total']}건(항목 수정 {c['item_revision_gaps']}건, 영업이익 {c['op_gaps']}건)입니다."),
        ("causes", "소명", f"수락된 소명 {e['accepted_count']}건을 근거로 했고 검토중 소명은 {e['pending_count']}건입니다."),
        ("changes", "승인", f"{m['approval_label']} 기준이며 " + (f"master 버전은 {ver}입니다." if ver else "공식 master가 아닌 참고용입니다.")),
    ]
    facts = [{"id": f"F{i}", "kind": k, "text": t} for i, (_, k, t) in enumerate(items, 1)]
    return facts, {f"F{i}": {"text": t} for i, (_, _, t) in enumerate(items, 1)}, {f"F{i}": n for i, (n, _, _) in enumerate(items, 1)}


def div_facts(state, r, m, G):
    base = ym_label(r["base_month"])
    facts, tpl, keys = [], {}, {}
    for i, x in enumerate([x for x in r["org_plan_vs_actual"]["rows"] if x.get("values")], 1):
        v = x["values"]
        t = f"{x['org_name']}: {base} 매출 실적 {v['매출']['actual_eok']}억원(계획 대비 {sd(v['매출'])}), 영업이익 실적 {v['영업이익']['actual_eok']}억원(계획 대비 {sd(v['영업이익'])})입니다."
        facts.append({"id": f"F{i}", "kind": "사업부", "text": t})
        tpl[f"F{i}"] = {"text": t}
        keys[f"F{i}"] = x["org_code"]
    return facts, tpl, keys


def cause_facts(state, records):
    facts, tpl, keys = [], {}, {}
    n = 0
    for r in records:
        if r["st_final" if state == "final" else "st_prov"][0] != "수락" or not r["latest_answer"]:
            continue
        n += 1
        fid = f"F{n}"
        facts.append({"id": fid, "kind": "소명", "text": f"{r['org_name']}의 {r['item']} {r['month']}월 {'실적' if r['scenario'] == 'ACTUAL' else '추정'} 수락된 소명 원문: {r['latest_answer']}"})
        tpl[fid] = nv.split_cause_impact(r["latest_answer"])
        keys[fid] = r["id"]
    return facts, tpl, keys


HL_VERSION = "key-messages-v3-highlights"
HL_SYSTEM = ("너는 경영 보고서 초안 작성 보조다. 주어진 사실 목록만 사용한다. 주목 사실마다 '주목할 점' 한 문장, 확인 사실마다 '확인할 일' 한 문장을 쓴다(주목할 점 3개 + 확인할 일 1~2개). "
             "새 숫자, 새 원인, 추측, 전망을 쓰지 않는다. 사실 문자열의 숫자 표기와 띄어쓰기를 바꾸지 않는다. 소명이 없는 변동은 원인을 말하지 않는다. "
             '출력은 JSON {"sentences":[{"text":..., "fact_ids":[...]}]} 만.')


def _eok(won) -> str:
    """원 → 억원(소수 1자리, ROUND_HALF_UP). 표시용 문자열(부호 포함)."""
    v = (Decimal(str(won)) / Decimal(10) ** 8).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP)
    return f"{'+' if v > 0 else '−' if v < 0 else ''}{abs(v)}억원"


def highlights(state: str, r: dict, m: dict, G: dict, records: list[dict]) -> tuple[list[dict], list[dict]]:
    """핵심 메시지용 사실: 주목할 점 3개 + 확인할 일. 모두 코드가 계산한다(조직별 계획 대비 순위, Gap 기여도, 소명 있음/없음, 이연 판정)."""
    key = "st_final" if state == "final" else "st_prov"
    st_of = {x["id"]: x[key][0] for x in records}
    rows = [x for x in r["org_plan_vs_actual"]["rows"] if x.get("values") and x["values"].get("영업이익")]
    by_org = sorted(rows, key=lambda x: int(Decimal(x["values"]["영업이익"]["vs_plan_diff_won"])))
    gaps_live = [g for g in G["gaps"] if g.get("status_in_final") in ("ACTIVE", "CARRIED_OVER_ELIGIBLE")]

    def gap_status(g) -> str:
        ex = g.get("explanations") or []
        if not ex:
            return "없음"
        sts = {st_of.get(e["legacy_gap_key"]) for e in ex}
        return "수락" if sts == {"수락"} else "검토중"

    def org_status(code) -> str:
        gs = [g for g in gaps_live if g["org_code"] == code and g["gap_type"] == "OP_GAP"]
        return gap_status(gs[0]) if gs else "Gap 없음"
    facts, tpl = [], []

    def add(kind, text):
        fid = f"F{len(facts) + 1}"
        facts.append({"id": fid, "kind": kind, "text": text})
        tpl.append({"text": ("주목할 점: " if kind == "주목" else "확인할 일: ") + text, "fact_ids": [fid], "numbers_used": []})
    if by_org:
        lo, hi = by_org[0], by_org[-1]
        add("주목", f"계획 대비 영업이익이 가장 크게 늘어난 조직은 {hi['org_name']}({_eok(hi['values']['영업이익']['vs_plan_diff_won'])})이고 소명은 {org_status(hi['org_code'])}, "
                    f"가장 크게 줄어든 조직은 {lo['org_name']}({_eok(lo['values']['영업이익']['vs_plan_diff_won'])})이고 소명은 {org_status(lo['org_code'])}입니다.")
    if gaps_live:
        top = max(gaps_live, key=lambda g: abs(int(Decimal(g["diff_won"]))))
        add("주목", f"금액이 가장 큰 Gap은 {top['org_name']}의 {top['item']} {top['month']}월 {'추정' if top['scenario'] == 'ESTIMATE' else '실적'} 변동 {_eok(top['diff_won'])}이고 소명은 {gap_status(top)}입니다.")
    from collections import Counter
    cnt = Counter(x["dfr"][0] for x in records)
    defer = sorted({f"{x['org_name']} {x['item']}" for x in records if x["dfr"][0] == "이연"})
    add("주목", f"소명 {len(records)}건을 코드로 판정하면 이연 {cnt.get('이연', 0)}건, 실제 총액 변화 {cnt.get('실제 총액 변화', 0)}건, 판정 불가 {cnt.get('판정 불가', 0)}건입니다." +
        (f" 이연으로 판정된 항목은 {', '.join(defer)}입니다." if defer else ""))
    pend = sum(1 for x in records if x[key][0] != "수락")
    no_expl = sum(1 for g in gaps_live if not g.get("explanations"))
    excluded = [x["org_name"] for x in r["org_plan_vs_actual"]["rows"] if not x.get("values")]
    parts = [f"소명 검토중 {pend}건" if pend else None, f"소명이 없는 Gap {no_expl}건" if no_expl else None, (f"승인 대기로 잠정 집계에서 제외된 조직 {', '.join(excluded)}" if excluded else None)]
    parts = [p for p in parts if p]
    add("확인", ("확인이 필요한 항목: " + ", ".join(parts) + "입니다." if parts else "수락 대기 중인 소명과 소명 없는 Gap은 없습니다.") + f" 판정 불가 {cnt.get('판정 불가', 0)}건은 팀 확인이 필요합니다.")
    return facts, tpl


def key_messages(state: str, r: dict, m: dict, G: dict, records: list[dict], allow: bool, force: bool, settings) -> dict:
    facts, tpl = highlights(state, r, m, G, records)
    res = nv.key_messages(facts, tpl, state, allow_call=allow, force=force, settings=settings, system_prompt=HL_SYSTEM, prompt_version=HL_VERSION)
    sents = [{"text": s["text"], "fact_ids": s["fact_ids"], "source": s["source"], "reason": s["reason"]} for s in res["sentences"]]
    why = next((s["reason"] for s in sents if s["source"] == "template" and s["reason"]), None)
    n_ai = sum(s["source"] == "ai" for s in sents)
    err = ai_reasons.call_failed(res["error"]) if res["error"] else (why if not n_ai else None)       # 예외 이름에도 'AI_CALL_FAILED:' 를 붙여 실제 사유로 보이게
    return {"sentences": sents, "counts": {"ai": n_ai, "template": len(sents) - n_ai}, "ai_error": err, "facts": [{"id": f["id"], "kind": f["kind"]} for f in facts],
            "usage": res["usage"], "attempted": res["ai_attempted"]}


# ------------------------------------------------------------------ 화면 데이터 조립
def build_data(G: dict, RF: dict, RP: dict, MF: dict, MP: dict, AIF: dict, AIP: dict, *, allow_ai: bool = False, force_ai: bool = False,
               settings=None) -> tuple[dict, list]:
    """반환: (DATA, call_report). DATA 는 report.html·flow.html 이 window.DATA 로 쓰는 구조."""
    RS, MS, AI = {"final": RF, "provisional": RP}, {"final": MF, "provisional": MP}, {"final": AIF, "provisional": AIP}
    records, gaps = build_records(G, RF)
    org_names = {r["org_code"]: r["org_name"] for r in RF["org_plan_vs_actual"]["rows"]}
    kms, narr, calls, detail = {}, {}, [], []
    for state in ("final", "provisional"):
        allow = allow_ai and state == "final"                       # AI 는 확정 버전의 핵심 메시지 1회만(카드·사업부·소명 문장은 템플릿)
        kms[state] = key_messages(state, RS[state], MS[state], G, records, allow, force_ai, settings)
        calls.append((state, "key_messages", kms[state]["counts"], kms[state]["usage"], kms[state]["ai_error"]))
        detail.append({"state": state, "task": "key_messages", "attempted": kms[state]["attempted"], "cached": kms[state]["usage"]["cached"], "ai": kms[state]["counts"]["ai"],
                       "template": kms[state]["counts"]["template"], "input_tokens": kms[state]["usage"]["input_tokens"], "output_tokens": kms[state]["usage"]["output_tokens"],
                       "reason": kms[state]["ai_error"]})
        n = {}
        for task, fn in (("cards", lambda: cards_facts(state, RS[state], MS[state], G)), ("divisions", lambda: div_facts(state, RS[state], MS[state], G)),
                         ("causes", lambda: cause_facts(state, records))):
            facts, tpl, keys = fn()
            n[task] = {keys[k]: {**v, "source": "template", "reason": None} for k, v in tpl.items()}
        narr[state] = n

    def submissions(state):
        m = MS[state]["report_inputs"]
        appr = {x["org_code"]: x.get("approval_status") for x in m["all_orgs"]}
        return [{"org_code": o["org_code"], "org_name": o["org_name"], "submission_id": o.get("submission_id"), "approval_status": appr.get(o["org_code"])} for o in m["orgs"]]

    def basis_of(state):
        r, m = RS[state], MS[state]
        s = next(x for x in r["summary_table"]["rows"] if x["metric"] == "영업이익")
        ex = [org_names.get(c, c) for c in r["scope"]["excluded_orgs"]]
        return {"period": f"{ym_label(r['base_month'])} (당월)", "scope": f"전사 {r['scope']['orgs']}개 조직" + (f" · 제외 {', '.join(ex)}" if ex else ""), "unit": r["unit"],
                "compare": f"전월 {s['last_month']['period']} · 전년 {s['last_year_same_month']['period']} · 계획 {s['plan_this_month']['period']}",
                "master": (m.get("master") or {}).get("version") or "없음(잠정 · 공식 master 아님)", "snapshot": m.get("source_snapshot_key") or "데이터 없음"}

    fin = [d for d in detail if d["state"] == "final"]
    data = {
        "ai": ai_summary(allow_ai, getattr(settings, "openai_model", None), fin),
        "report": RS, "keyMessages": kms, "narr": narr, "records": records, "gaps": gaps, "gapCounts": G["counts"], "orgNames": org_names,
        "master": {s: {**{k: MS[s].get(k) for k in ("state", "requested_orgs", "approved_orgs", "approval_label")}, "version": (MS[s].get("master") or {}).get("version"),
                       "month": (MS[s].get("master") or {}).get("month")} for s in RS},
        "submissions": {s: submissions(s) for s in RS}, "basis": {s: basis_of(s) for s in RS},
        "changes": {"prevRevisions": [{k: g[k] for k in ("org_name", "item", "ym", "previous_value_won", "current_value_won", "diff_won")} for g in gaps
                                      if g["basis"] in ("PREVIOUS", "PREVIOUS_SAME_MONTH") and g["scenario"] == "ACTUAL"],
                    "pastUpdates": MF.get("past_month_updates") or []},
    }
    return data, calls


def reason_ko(r: str | None) -> str | None:
    return ai_reasons.ko(r)


def ai_summary(requested: bool, model: str | None, tasks: list[dict]) -> dict:
    """확정 버전 기준 AI 사용 요약: 실제 호출 횟수·성공/실패·사유. 잠정 버전은 호출하지 않는다(요약 대상 아님)."""
    api = [t for t in tasks if t["attempted"]]
    failed = [t for t in api if t["ai"] == 0 and t["reason"]]
    return {"requested": requested, "model": model, "api_calls": len(api), "succeeded": len(api) - len(failed), "failed": len(failed), "cache_hits": sum(1 for t in tasks if t["cached"]),
            "ai_sentences": sum(t["ai"] for t in tasks), "template_sentences": sum(t["template"] for t in tasks),
            "input_tokens": sum(t["input_tokens"] for t in tasks), "output_tokens": sum(t["output_tokens"] for t in tasks),
            "tasks": [{**t, "reason_ko": reason_ko(t["reason"])} for t in tasks],
            "reasons": sorted({reason_ko(t["reason"]) for t in tasks if t["reason"] and t["ai"] < t["ai"] + t["template"] and reason_ko(t["reason"])})}


def report_safe(d: dict) -> dict:
    """보고서 화면에는 반려된 소명 문장 원문을 넣지 않는다(검토자용 Gap 화면에서만 표시)."""
    import json
    d = json.loads(json.dumps(d, ensure_ascii=False))
    for r in d["records"]:
        for x in r["history"]:
            if x["verdict"] != "ACCEPTED":
                x["answer"] = None
        if r["latest_verdict"] != "ACCEPTED":
            r["latest_answer"] = None
    for g in d["gaps"]:
        for e in g["explanations"]:
            for x in e["history"]:
                if x["verdict"] != "ACCEPTED":
                    x["answer"] = None
    return d


# ---------------------------------------------------------------------------------------------
# 프론트 보고서 화면(PlannerReports.vue)용 요약. 숫자는 보고서 데이터의 값을 그대로 쓰고 서식(콤마·부호)만 입힌다.
def _num(eok: str | None) -> str:
    if eok is None:
        return "-"
    neg = eok.startswith("-")
    a = eok.lstrip("-")
    head, _, frac = a.partition(".")
    out = f"{int(head):,}" + (f".{frac}" if frac else "")
    return ("−" if neg else "") + out


def _delta(d: dict | None) -> tuple[str, str]:
    """(▲18.5, +11.6%) 형태. 값이 없으면 ('-', '-')."""
    if not d or d.get("diff_eok") is None:
        return "-", "-"
    diff = d["diff_eok"]
    arrow = d.get("direction") or ("▼" if diff.startswith("-") else "▲")
    pct = d.get("percent")
    p = "-" if pct is None else ("−" if pct.startswith("-") else "+") + _num(pct.lstrip("-")) + "%"
    return f"{arrow}{_num(diff.lstrip('-'))}", p


def screen_payload(data: dict, state: str = "final") -> dict:
    """보고서 화면에 필요한 값만 추린다(원천·수식 설명은 뺀다). 주별 매출 증감률은 만들지 않는다(weekly=None)."""
    r, km, m = data["report"][state], data["keyMessages"][state], data["master"][state]
    st = r["summary_table"]
    rows = []
    for x in st["rows"]:
        d1, p1 = _delta(x.get("vs_last_month"))
        d2, p2 = _delta(x.get("vs_last_year"))
        rows.append([x["metric"], _num(x["last_year_same_month"]["value_eok"]), _num(x["last_month"]["value_eok"]), _num(x["this_month"]["value_eok"]), d1, p1, d2, p2])
    b = r["op_bridge"]
    steps = [{"label": s["label"], "value": (_num(s["value_eok"]) if s["type"] == "total" else ("+" if not s["delta_eok"].startswith("-") else "−") + _num(s["delta_eok"].lstrip("-"))),
              "eok": float(s["value_eok"] if s["type"] == "total" else s["delta_eok"]), "type": s["type"]} for s in b["steps"]]
    sign = "−" if b["total_delta_eok"].startswith("-") else "+"
    plan, latest = steps[0], steps[-1]
    caption = f"연간 계획 {plan['value']} → 최신 추정 {latest['value']} ({sign}{_num(b['total_delta_eok'].lstrip('-'))}, {sign}{_num(b['total_delta_percent'].lstrip('-'))}%) · {b['unit']}"
    ex = r["explanation_inline"]
    acc = [i for i in ex["items"] if i.get("status") == "수락" and i.get("text") and i.get("included_in_scope")]
    acc.sort(key=lambda i: -abs(int(i["observed_diff_won"])))
    reason = None
    if acc:
        i = acc[0]
        arrow = "▼" if int(i["observed_diff_won"]) < 0 else "▲"
        reason = {"title": f"{arrow} {i['item']} 소명 · [{i['org_name']}]", "cause": i["text"]}
    label = r["data_status"]
    return {
        "state": state, "data_status": label, "master_version": m.get("version") or r.get("master_version"), "base_month": r["base_month"],
        "unit": r["unit"], "scope": f"전사({r['scope']['orgs']}개 조직)", "approval_label": m.get("approval_label"),
        "banner": None if label == "확정" else f"{label} {m.get('approval_label') or ''} · 공식 master 생성 전 · 미수락 소명 {ex['pending_count']}건은 “검토중”으로 표기".strip(),
        "summary": {"caption": st["subtitle"], "rows": rows}, "bridge": {"caption": caption, "steps": steps},
        "message": [s["text"] for s in km["sentences"]], "reason": reason, "weekly": None,
        "gap_counts": {k: data["gapCounts"].get(k) for k in ("gaps_total", "item_revision_gaps", "op_gaps", "gaps_without_explanation", "explanation_keys")},
        "explanations": [{"org_name": x["org_name"], "item": x["item"], "ym": x["ym"], "gclass": x["gclass"], "diff_eok": x["diff_eok"], "basis": x["diff_basis"],
                          "status": (x["st_final"] or ["-"])[0], "dfr": (x["dfr"] or [None])[0], "answer": x["latest_answer"], "resub": x["resub"]}
                         for x in data["records"]],
    }
