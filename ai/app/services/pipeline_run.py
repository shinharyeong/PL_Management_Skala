"""업로드 → 파싱 → Gap → 마스터 → 보고서 자료 5단계 실행(확인용). 단계별로 나중에 백엔드 API 로 교체될 예정이다.

기존 파이프라인 단계(app/pipeline/*)의 계산은 그대로 쓰고, 경로 설정만 요청별로 바꿔(config.use_settings) 호출한다.
교체 예정 대응: 업로드→SUB-01, 파싱→SUB-03/06/07, Gap·소명→REV-01, 마스터→MAS-04, 보고서→REP-01.
"""
from __future__ import annotations

import json
import re
import shutil
import time
import traceback
import uuid
from datetime import datetime
from decimal import Decimal
from pathlib import Path

from ..config import Settings, get_settings, use_settings
from ..pipeline import explain_stage, facts_stage, gap_stage, master_stage, materials_stage, message_stage, parse_stage
from ..pipeline.common import jdefault, load_thresholds, write_json
from . import report_view, uploads

RUN_ID_RE = re.compile(r"^[A-Za-z0-9_-]{6,64}$")
STAGES = ("upload", "parse", "gaps", "master", "report")


def new_run_id() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:6]


def run_dirs(s: Settings, run_id: str) -> tuple[Path, Path]:
    return s.ai_cache_dir / "uploads" / run_id, s.ai_cache_dir / "runs" / run_id


def _stage(status: str, message: str, **kw) -> dict:
    return {"status": status, "message": message, **kw}


def _skipped(reason: str) -> dict:
    return _stage("SKIPPED", reason)


def _read(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def _fail_message(e: Exception) -> str:
    return f"{type(e).__name__}: {str(e)[:300]}"


# ------------------------------------------------------------------ 단계별 요약
def _parse_summary(parsed: list[dict], b: dict) -> dict:
    files, orgs = [], {}
    for p in parsed:
        q = p["quality"]
        problems = []
        if not q["raw_extraction_complete"]:
            problems.append("원본 추출이 불완전합니다")
        if not q["provenance_passed"]:
            problems.append("원본 위치 검증에 실패했습니다")
        bad = q["business_validation"].get("invalid", 0)
        if bad:
            problems.append(f"업무 검증 오류 {bad}건(제외 대상일 수 있음)")
        files.append({"rel": p["rel"], "org_code": p["org"], "org_name": p["org_name"], "role": p["role"], "submission_id": p["submission_id"],
                      "ok": not problems or p.get("excluded_reason") is not None, "cells": q["detail_amount_records"],
                      "seconds": round(p["timing"]["total"], 2) if p.get("timing") else None, "cached": bool(p.get("cached")), "records": q["standard_records"],
                      "mapped": q["mapped"], "diagnostics": q["diagnostics"], "problems": problems, "excluded_reason": p.get("excluded_reason")})
        o = orgs.setdefault(p["org"], {"org_code": p["org"], "org_name": p["org_name"], "files": 0, "cells": 0, "final_submission_id": None})
        o["files"] += 1
        o["cells"] += q["detail_amount_records"]
        if p["role"] == "FINAL":
            o["final_submission_id"] = p["submission_id"]
    errors = [{"rel": f["rel"], "message": "; ".join(f["problems"])} for f in files if f["problems"]]
    ok_n = sum(1 for f in files if f["ok"])
    status = "OK" if not errors else "WARN"
    return {"files": files, "orgs": sorted(orgs.values(), key=lambda x: x["org_code"]), "errors": errors, "counts": {"files": len(files), "ok": ok_n, "failed": len(files) - ok_n},
            "notes": b["notes"], "status": status,
            "perf": {"files": len(files), "from_cache": sum(1 for f in files if f["cached"]), "avg_file_seconds": round(sum(f["seconds"] or 0 for f in files if not f["cached"]) / max(1, sum(1 for f in files if not f["cached"])), 2)},
            "message": f"엑셀 {len(files)}개를 파싱했습니다(성공 {ok_n}개" + (f", 이전 결과 재사용 {sum(1 for f in files if f['cached'])}개" if any(f["cached"] for f in files) else "") + ")." + (f" 확인이 필요한 파일 {len(errors)}개." if errors else "")}


def _gap_summary(linked: dict, has_scenarios: bool) -> dict:
    c = linked["counts"]
    gaps = [{k: g.get(k) for k in ("gap_id", "gap_type", "reason_code", "org_code", "org_name", "item", "year", "month", "scenario", "basis", "previous_value_won", "current_value_won",
                                     "diff_won", "rate_percent", "rate_note", "status_in_final", "explanation_status")} |
            {"explanations": [{"item": x["item"], "link_role": x["link_role"], "latest_verdict": x["latest_verdict"], "status_final": x["status_final"],
                               "status_provisional": x["status_provisional"], "history": x["history"]} for x in g["explanations"]]} for g in linked["gaps"]]
    resub = [{"org_code": e["org_code"], "item": e["item"], "month": e["month"], "history": [{"round": h["round"], "verdict": h["verdict"]} for h in e["history"]]}
             for e in linked["explanations"] if e["has_resubmission_history"]]
    status, msg = "OK", f"Gap {c['gaps_live']}건(항목 수정 {c['item_revision_gaps']} · 영업이익 {c['op_gaps']}) · 소명 {c['explanation_keys']}건 연결."
    if c["gaps_without_explanation"]:
        status, msg = "WARN", msg + f" 소명이 없는 Gap {c['gaps_without_explanation']}건은 '검토중'으로 처리됩니다."
    if not has_scenarios and c["explanation_keys"] and c["explanations_linked_direct"] + c["explanations_linked_op_gap_only"] == 0:
        status = "WARN"
        msg = ("소명의 gap_key(REV030 등)를 엑셀 셀로 풀 자료(시나리오 JSON 02_scenarios/NN_*.json)가 없어 소명을 Gap 에 연결하지 못했습니다. "
               "나중에는 백엔드의 검토 항목(REV-01)이 이 연결을 대신합니다.")
    return {"status": status, "message": msg, "counts": c, "gaps": gaps, "explanations": [
        {k: e[k] for k in ("legacy_gap_key", "org_code", "year", "month", "scenario", "kind", "item", "link_type", "latest_verdict", "status_final", "status_provisional",
                           "has_resubmission_history", "rounds")} for e in linked["explanations"]], "resubmission_history": resub}


def _master_summary(MF: dict, MP: dict, RF: dict, RP: dict) -> dict:
    def block(M, R):
        m = M.get("master")
        rows = {r["metric"]: r for r in R["summary_table"]["rows"]}
        tot = {"revenue_won": rows["매출"]["this_month"]["value_won"], "cost_won": rows["영업비용"]["this_month"]["value_won"], "op_won": rows["영업이익"]["this_month"]["value_won"],
               "revenue_eok": rows["매출"]["this_month"]["value_eok"], "cost_eok": rows["영업비용"]["this_month"]["value_eok"], "op_eok": rows["영업이익"]["this_month"]["value_eok"]}
        st = R["op_bridge"]["steps"]
        by_org = [{k: o[k] for k in ("org_code", "org_name", "submission_id", "revenue_won", "cost_won", "op_won")} for o in ((m or {}).get("by_org") or (M.get("provisional_aggregate") or {}).get("by_org") or [])]
        return {"state": M["state"], "approved_orgs": M["approved_orgs"], "requested_orgs": M["requested_orgs"], "approval_label": M["approval_label"],
                "master_created": M["master_created"], "version": (m or {}).get("version"), "month": (m or {}).get("month"), "totals": tot,
                "annual_plan_eok": st[0]["value_eok"], "annual_latest_estimate_eok": st[-1]["value_eok"], "by_org": by_org,
                "unapproved_orgs": M.get("unapproved_orgs"), "why_not_created": M.get("why_not_created"), "problems": M.get("problems"),
                "past_month_updates": M.get("past_month_updates") or M.get("past_month_updates_on_approval")}
    fin, prov = block(MF, RF), block(MP, RP)
    ans = (MF.get("master") or {}).get("answer_sheet") or (MP.get("provisional_aggregate") or {}).get("answer_sheet") or {}
    approved_prov = {o["org_code"] for o in (MP.get("provisional_aggregate") or {}).get("by_org", [])}
    ans_sum = {"orgs": len(ans), "all_ok": all(a["check"]["all_ok"] for a in ans.values()) if ans else False, "ok_orgs": sum(1 for a in ans.values() if a["check"]["all_ok"]),
               "rows_per_org": (next(iter(ans.values()))["check"]["row_count"] if ans else 0), "months": 12}
    if fin["master_created"]:
        status, msg = "OK", f"승인 {fin['approved_orgs']}/{fin['requested_orgs']} → {fin['version']} 생성(확정). 잠정 집계는 승인 {prov['approved_orgs']}/{prov['requested_orgs']} 기준으로 따로 있습니다."
    else:
        status, msg = "WARN", f"승인 {fin['approved_orgs']}/{fin['requested_orgs']}라서 master 를 만들지 않고 잠정 집계만 보여 줍니다."
    return {"status": status, "message": msg, "final": fin, "provisional": prov, "answer_sheet": ans, "answer_sheet_summary": ans_sum, "provisional_orgs": sorted(approved_prov)}


# ------------------------------------------------------------------ 실행
def execute(run_id: str, upload_report: dict, s: Settings, data_dir: Path, out_dir: Path, ai: bool, timings: dict | None = None) -> dict:
    """업로드가 통과한 뒤 파싱→보고서를 실행한다. 단계가 실패하면 그 단계를 FAIL 로 표시하고 이후 단계는 건너뛴다."""
    res = {"parse": _skipped("업로드 단계가 통과해야 실행합니다."), "gaps": _skipped("파싱이 끝나야 실행합니다."),
           "master": _skipped("Gap 단계가 끝나야 실행합니다."), "report": _skipped("마스터 단계가 끝나야 실행합니다.")}
    if upload_report["status"] == "FAIL":
        return res
    scen_dir = data_dir / "02_scenarios"
    scen_dir.mkdir(parents=True, exist_ok=True)
    has_scen = any(scen_dir.glob("[0-9][0-9]_*.json"))
    paths = dict(input_dir=data_dir / "01_excel", explanations_path=scen_dir / "explanations.json", approvals_path=scen_dir / "approval_snapshots.json",
                 scenario_dir=scen_dir, scenario_csv_path=None, output_dir=out_dir, log_dir=s.log_dir or s.output_dir)
    T = timings if timings is not None else {}
    with use_settings(**paths, optional=("SCENARIO_CSV_PATH",)) as rs:
        # --- 파싱
        try:
            t0 = time.perf_counter()
            parsed = parse_stage.parse_all(use_cache=True)
            b = parse_stage.build_submissions(parsed)
            T["parse"] = round(time.perf_counter() - t0, 2)
            res["parse"] = _parse_summary(parsed, b)
        except Exception as e:  # noqa: BLE001
            res["parse"] = _stage("FAIL", f"엑셀을 읽는 중 오류가 났습니다. 파일이 손상되었거나 양식이 다를 수 있습니다. ({_fail_message(e)})", files=[], orgs=[], errors=[{"message": _fail_message(e)}])
            return res
        # --- Gap + 소명
        try:
            th = load_thresholds()
            t0 = time.perf_counter()
            g = gap_stage.run(b["subs"], th)
            T["gaps"] = round(time.perf_counter() - t0, 2)
            t0 = time.perf_counter()
            linked = explain_stage.link(g, b["subs"], b["snapshots"])
            T["explanations"] = round(time.perf_counter() - t0, 2)
            write_json("gaps_with_explanations.json", {"counts": linked["counts"], "gaps": linked["gaps"], "explanations": linked["explanations"]})
            res["gaps"] = _gap_summary(json.loads(json.dumps(linked, default=jdefault)), has_scen)
        except Exception as e:  # noqa: BLE001
            res["gaps"] = _stage("FAIL", f"Gap 계산 중 오류가 났습니다. 업로드한 파일 구성(조직별 계획·baseline·최종 제출본)을 확인하세요. ({_fail_message(e)})", gaps=[], counts={})
            return res
        # --- 마스터 + 07 자료
        try:
            t0 = time.perf_counter()
            out, packs = {}, {}
            for state in ("provisional", "final"):
                m = master_stage.build(state, b["subs"], b["snapshots"], g)
                write_json(f"master_{state}.json", m)
                doc = _read(out_dir / f"master_{state}.json")
                mat = materials_stage.build(state, doc, linked["explanations"], linked["counts"], linked["gaps"], show_reference_explanations=True)
                write_json(f"report_materials_{state}.json", mat)
                pack = facts_stage.build(state, mat, doc)
                write_json(f"ai_input_{state}.json", message_stage.ai_input_doc(state, pack))
            MF, MP = _read(out_dir / "master_final.json"), _read(out_dir / "master_provisional.json")
            RF, RP = _read(out_dir / "report_materials_final.json"), _read(out_dir / "report_materials_provisional.json")
            T["master"] = round(time.perf_counter() - t0, 2)
            res["master"] = _master_summary(MF, MP, RF, RP)
        except Exception as e:  # noqa: BLE001
            res["master"] = _stage("FAIL", f"마스터·보고 자료를 만드는 중 오류가 났습니다. 승인 현황과 제출 파일이 맞는지 확인하세요. ({_fail_message(e)})", final=None, provisional=None)
            return res
        # --- 보고서(핵심 메시지·화면 데이터). AI 는 ai=True 일 때만, 확정 버전에 한해 호출
        try:
            t0 = time.perf_counter()
            G = _read(out_dir / "gaps_with_explanations.json")
            data, calls = report_view.build_data(G, RF, RP, MF, MP, _read(out_dir / "ai_input_final.json"), _read(out_dir / "ai_input_provisional.json"), allow_ai=ai, settings=rs)
            T["report"] = round(time.perf_counter() - t0, 2)
            km = data["keyMessages"]
            ai_n = sum(v["counts"]["ai"] for v in km.values())
            msg = "07 보고서 자료와 핵심 메시지를 만들었습니다." + (f" AI 문장 {ai_n}개." if ai else " AI 를 쓰지 않아 템플릿 문장입니다.")
            res["report"] = _stage("OK", msg, data=data, key_messages=km, ai_requested=ai, calls=[{"state": c[0], "task": c[1], "ai": c[2]["ai"], "template": c[2]["template"],
                                                                                                         "input_tokens": c[3]["input_tokens"], "output_tokens": c[3]["output_tokens"], "cached": c[3]["cached"], "note": c[4]} for c in calls])
        except Exception as e:  # noqa: BLE001
            res["report"] = _stage("FAIL", f"보고서 화면 데이터를 만드는 중 오류가 났습니다. ({_fail_message(e)})", data=None)
    return res


def run(files: list[tuple[str, bytes]], *, ai: bool = False, keep_files: bool | None = None, settings: Settings | None = None) -> dict:
    s = settings or get_settings()
    run_id = new_run_id()
    up_dir, rn_dir = run_dirs(s, run_id)
    data_dir = up_dir / "data"
    data_dir.mkdir(parents=True, exist_ok=True)
    rn_dir.mkdir(parents=True, exist_ok=True)
    timings: dict = {}
    t0 = time.perf_counter()
    entries, issues = uploads.expand(files, s)
    comp = uploads.completeness(entries)
    uploads.save(entries, data_dir)
    up = uploads.summarize(entries, issues, comp)
    timings["upload"] = round(time.perf_counter() - t0, 2)
    t_all = time.perf_counter()
    result = {"run_id": run_id, "created_at": datetime.now().isoformat(timespec="seconds"), "draft": True,
              "options": {"ai": ai, "keep_files": s.upload_keep_files if keep_files is None else keep_files}, "stages": {"upload": up}}
    try:
        result["stages"].update(execute(run_id, up, s, data_dir, rn_dir / "output", ai, timings))
    except Exception as e:  # noqa: BLE001 — 예상 밖 오류도 단계 FAIL 로 알린다(스택은 응답에 넣지 않음)
        result["stages"].setdefault("parse", _stage("FAIL", f"예상하지 못한 오류가 났습니다. ({type(e).__name__})"))
        traceback.print_exc()
    for name in STAGES:
        result["stages"].setdefault(name, _skipped("앞 단계 결과가 없어 건너뜀"))
    timings["total_after_upload"] = round(time.perf_counter() - t_all, 2)
    result["timings"] = timings
    result["overall"] = "OK" if all(result["stages"][n]["status"] in ("OK", "WARN") for n in STAGES) else "FAIL"
    (rn_dir / "result.json").write_text(json.dumps(result, ensure_ascii=False, default=jdefault), encoding="utf-8")
    if result["overall"] == "OK":      # 챗봇이 바로 쓸 수 있게 조회 DB 에 적재한다(실패해도 실행 결과에는 영향 없음)
        try:
            from ..chat import loader_run
            t_db = time.perf_counter()
            summary = loader_run.load_run(run_id, s)
            result["chat_db"] = {"loaded": True, "versions": [v["version"] for v in summary["versions"]], "seconds": round(time.perf_counter() - t_db, 2)}
        except Exception as e:  # noqa: BLE001
            result["chat_db"] = {"loaded": False, "reason": f"{type(e).__name__}"}
        (rn_dir / "result.json").write_text(json.dumps(result, ensure_ascii=False, default=jdefault), encoding="utf-8")
    if not result["options"]["keep_files"]:
        shutil.rmtree(up_dir, ignore_errors=True)
    return result


def load_run(run_id: str, settings: Settings | None = None) -> dict | None:
    if not RUN_ID_RE.match(run_id or ""):
        return None
    s = settings or get_settings()
    p = run_dirs(s, run_id)[1] / "result.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None
