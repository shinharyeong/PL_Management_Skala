"""AI 입력(사실 목록)과 템플릿 문장 생성. AI와 템플릿은 같은 사실 목록에서 만든다.

사실에는 계산된 억원 문자열과 수락된 소명 원문만 넣는다. 제외: 반려된 소명 문장, 반려 사유, 엑셀 원본 값, API 키,
submission_id·gap_key 등 내부 식별자(사업부 이름만 사용).
"""
from __future__ import annotations

import re

from .common import REQUEST_MONTH, REQUEST_YEAR, fmt_eok, fmt_pct

Y, M = REQUEST_YEAR, REQUEST_MONTH
NUM = re.compile(r"\d[\d,]*(?:\.\d+)?")
SC_KO = {"ACTUAL": "실적", "ESTIMATE": "추정"}
STATUS_KO = {"PENDING": "검토 대기(PENDING)"}


def numbers_in(text: str) -> list[str]:
    return sorted({n.replace(",", "") for n in NUM.findall(text)}, key=lambda x: (len(x), x))


def _pc(c) -> str:
    return fmt_pct(c["percent"]) if c["percent"] is not None else "기준 없음"


def build(state: str, mat: dict, master_doc: dict) -> dict:
    facts = []

    def add(kind, text, data=None):
        f = {"id": f"F{len(facts) + 1}", "kind": kind, "text": text, "numbers": numbers_in(text), "data": data or {}}
        facts.append(f)
        return f

    rows = {r["metric"]: r for r in mat["summary_table"]["rows"]}
    base_ids = {}
    for metric in ("매출", "영업비용", "영업이익"):
        r = rows[metric]
        d = {"metric": metric, "this": fmt_eok(r["this_month"]["value_eok"]), "plan": fmt_eok(r["plan_this_month"]["value_eok"]),
             "plan_diff": fmt_eok(r["vs_plan"]["diff_eok"], True), "plan_pct": _pc(r["vs_plan"]),
             "mom": fmt_eok(r["vs_last_month"]["diff_eok"], True), "mom_pct": _pc(r["vs_last_month"]),
             "yoy": fmt_eok(r["vs_last_year"]["diff_eok"], True), "yoy_pct": _pc(r["vs_last_year"])}
        t = (f"{Y}년 {M}월 전사 {metric} 실적은 {d['this']}억원이며, 계획 {d['plan']}억원 대비 {d['plan_diff']}억원({d['plan_pct']}), "
             f"전월 대비 {d['mom']}억원({d['mom_pct']}), 전년 동월 대비 {d['yoy']}억원({d['yoy_pct']})입니다.")
        base_ids[metric] = add(metric if metric != "영업비용" else "비용", t, d)
    b = mat["op_bridge"]
    tops = [s for s in b["steps"] if s["type"] == "delta"][:3]
    ad = {"plan": fmt_eok(b["steps"][0]["value_eok"]), "latest": fmt_eok(b["steps"][-1]["value_eok"]), "delta": fmt_eok(b["total_delta_eok"], True),
          "pct": fmt_pct(b["total_delta_percent"]) or "기준 없음",
          "tops": [{"org": s["label"].split("(")[0], "delta": fmt_eok(s["delta_eok"], True)} for s in tops]}
    at = (f"연간 영업이익은 계획 {ad['plan']}억원에서 최신 추정 {ad['latest']}억원으로 {ad['delta']}억원({ad['pct']}) 차이가 나며, 증감이 큰 사업부는 "
          + ", ".join(f"{x['org']} {x['delta']}억원" for x in ad["tops"]) + "입니다.")
    f_annual = add("연간", at, ad)

    inline = mat["explanation_inline"]["items"]
    acc = [i for i in inline if i["status"] == "수락" and i["included_in_scope"] and i["observed_diff_won"] is not None]
    per_org = {}
    for i in acc:
        if i["org_code"] not in per_org or abs(i["observed_diff_won"]) > abs(per_org[i["org_code"]]["observed_diff_won"]):
            per_org[i["org_code"]] = i
    top_orgs = sorted(per_org.values(), key=lambda i: abs(i["observed_diff_won"]), reverse=True)[:3]
    expl_facts, undist = [], []
    for i in top_orgs:
        undistributed = "구분되지" in i["text"]
        cause = " ".join(s.strip() for s in re.split(r"(?<=\.)\s+", i["text"]) if s.strip() and "구분되지" not in s)
        d = {"org": i["org_name"], "item": i["item"], "month": i["month"], "scenario": SC_KO[i["scenario"]], "diff": fmt_eok(i["observed_diff_eok"], True),
             "text": i["text"], "cause_quote": cause.rstrip("."), "has_undistributed_note": undistributed}
        t = f"{i['org_name']}의 {i['item']} {i['month']}월 {SC_KO[i['scenario']]} 변동은 {d['diff']}억원이며, 수락된 소명 원문은 \"{i['text']}\"입니다."
        expl_facts.append(add("소명", t, d))
        if undistributed:
            n = re.search(r"(\d+)백만원", i["text"])
            amt = f" 증가분 {n.group(1)}백만원의" if n else ""
            undist.append(add("주의", f"{i['org_name']}의 {i['month']}월 변동에서{amt} 원인별 금액은 미분해 상태이므로 원인 금액을 나누어 쓰지 않는다.",
                              {"undistributed": True, "org": i["org_name"]}))
    for i in inline:
        if i["has_resubmission_history"] and i["status"] == "수락" and i["included_in_scope"]:
            add("소명", f"{i['org_name']}의 {i['item']} {i['month']}월 소명은 {i['history_label']} 이력을 거쳐 최종 수락되었습니다.", {"org": i["org_name"], "history": i["history_label"]})
    notice = []
    if state == "provisional":
        ex = [o for o in master_doc["report_inputs"]["all_orgs"] if not o["included"]]
        for o in ex:
            notice.append(add("주의", f"이 수치는 승인 {master_doc['approved_orgs']}/{master_doc['requested_orgs']} 조직 기준 잠정 집계이며 {o['org_name']}은 {STATUS_KO.get(o['approval_status'], o['approval_status'])}로 제외되었다.",
                              {"provisional": True, "excluded_org": o["org_name"]}))
    if not undist and not notice:
        notice.append(add("주의", "수락된 소명이 없는 증감은 원인을 말하지 않는다.", {"general": True}))

    # ---- 템플릿 문장(같은 사실에서 생성) ----
    d1, d2, d3 = (base_ids[m]["data"] for m in ("매출", "영업비용", "영업이익"))
    T = [
        {"text": (f"{Y}년 {M}월 전사 매출은 {d1['this']}억원으로 계획 대비 {d1['plan_diff']}억원({d1['plan_pct']}), 전월 대비 {d1['mom']}억원({d1['mom_pct']})이며, "
                  f"영업비용은 {d2['this']}억원으로 계획 대비 {d2['plan_diff']}억원({d2['plan_pct']}), 전월 대비 {d2['mom']}억원({d2['mom_pct']})입니다."),
         "fact_ids": [base_ids["매출"]["id"], base_ids["영업비용"]["id"]]},
        {"text": f"영업이익은 {d3['this']}억원으로 계획 {d3['plan']}억원 대비 {d3['plan_diff']}억원({d3['plan_pct']}), 전월 대비 {d3['mom']}억원({d3['mom_pct']}), 전년 동월 대비 {d3['yoy']}억원({d3['yoy_pct']})입니다.",
         "fact_ids": [base_ids["영업이익"]["id"]]},
        {"text": (f"연간 영업이익은 계획 {ad['plan']}억원 대비 최신 추정 {ad['latest']}억원으로 {ad['delta']}억원({ad['pct']}) 차이가 나며, 증감이 큰 사업부는 "
                  + ", ".join(f"{x['org']}({x['delta']}억원)" for x in ad["tops"]) + "입니다."),
         "fact_ids": [f_annual["id"]]},
    ]
    if expl_facts:
        e = expl_facts[0]["data"]
        quote = e["cause_quote"] or e["text"]
        T.append({"text": f"{e['org']}의 {e['item']} {e['month']}월 {e['scenario']} 변동({e['diff']}억원)은 \"{quote}\"라는 소명이 수락되었습니다.", "fact_ids": [expl_facts[0]["id"]]})
    if undist:
        u = undist[0]
        T.append({"text": u["text"].replace("나누어 쓰지 않는다.", "나누어 설명하지 않습니다."), "fact_ids": [u["id"]]})
    elif notice:
        n = notice[0]
        T.append({"text": n["text"].replace("제외되었다.", "제외되었습니다.").replace("말하지 않는다.", "기재하지 않습니다."), "fact_ids": [n["id"]]})
    T = T[:5]
    for t in T:
        t["numbers_used"] = numbers_in(t["text"])
    counts = {}
    for f in facts:
        counts[f["kind"]] = counts.get(f["kind"], 0) + 1
    return {"facts": facts, "templates": T, "counts_by_kind": counts}
