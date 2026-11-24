"""보고서 파이프라인 전체 흐름: (1) 파싱 → (2) Gap → (3) 소명 연결 → (4) master → (5) 07 자료 → (6) 핵심 메시지.

실행(ai/ 에서): python3 -B -m app.pipeline.run_pipeline [--ai] [--force-ai] [--no-cache]
입력·출력 경로는 설정(INPUT_DIR, EXPLANATIONS_PATH, APPROVALS_PATH, OUTPUT_DIR 등)에서 읽는다.
기본은 AI 호출 없이 템플릿 문장만 만든다(같은 사실 목록의 캐시가 있으면 그 결과를 쓴다). --ai 를 줄 때만 OpenAI 를 호출한다.
"""
from __future__ import annotations

import argparse
import json
import subprocess
from decimal import Decimal

from . import explain_stage, facts_stage, gap_stage, master_compare, master_stage, materials_stage, message_stage, parse_stage, report_stage
from ..config import ConfigError
from .common import REPO, eok, inputs, load_thresholds, out_dir, write_json

EXPECTED = {"매출": Decimal("557.7"), "영업비용": Decimal("379.4"), "영업이익": Decimal("178.3"), "계획 영업이익": Decimal("177.0"), "연간 최신 추정 영업이익": Decimal("1727.7")}


def _git(*a):
    return subprocess.run(["git", *a], cwd=REPO, capture_output=True, text=True).stdout.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ai", action="store_true")
    ap.add_argument("--force-ai", action="store_true")
    ap.add_argument("--no-cache", action="store_true")
    ap.add_argument("--hide-reference-explanations", action="store_true", help="Gap 아님 참고 소명을 07 자료에서 숨김(기본: 표시)")
    args = ap.parse_args()
    th = load_thresholds()
    meta = {"branch": _git("branch", "--show-current"), "commit": _git("rev-parse", "HEAD"), "base_commit_recorded_before_branching": "bb2dd98f80a15936595e565b88e7f8deedca0111 (develop)"}

    # (1) 파싱
    parsed = parse_stage.parse_all(use_cache=not args.no_cache)
    b = parse_stage.build_submissions(parsed)
    subs = b["subs"]
    write_json("parse_report.json", {
        "meta": meta, "excel_root": str(inputs()["INPUT_DIR"]), "parser": "parser/ 룰 파서(extract_workbook + map_workbook), API 키·AI 파서 미사용",
        "folder_counts": {f: sum(1 for p in parsed if p["folder"] == f) for f in parse_stage.FOLDER_ROLE},
        "final_selection_rule": "사업부 17·19는 revisions 파일, 나머지는 submission_2026_11 파일(사업부16의 revisions 파일은 코드오류 최초본으로 제외)",
        "notes": b["notes"], "invalid_business_validation": [c for c in b["cross"] if c["parser_invalid_detail_records"]],
        "files": [{"rel": p["rel"], "org_code": p["org"], "org_name": p["org_name"], "role": p["role"], "submission_id": p["submission_id"], "excluded_reason": p.get("excluded_reason"), "quality": p["quality"]} for p in parsed],
    })

    # (2) Gap
    g = gap_stage.run(subs, th)
    # (3) 소명 연결 + 시나리오 대조
    linked = explain_stage.link(g, subs, b["snapshots"])
    sc = explain_stage.scenario_check(g, subs)
    write_json("gaps.json", {
        "meta": meta, "thresholds": th, "rule_source": "PRD ver1 FR-MONTH-005·006, ai/app/pipeline/common.py 기본 기준",
        "comparison_chain": g["steps"], "gaps": g["gaps"], "op_comparisons": g["op_comparisons"], "issues": g["issues"],
        "scenario_check": sc,
        "notes": ["설명 원문(담당자·거래내역 및 산출근거)은 의미 비교에서 제외: 정규화 규칙이 문서에 없음", "비교 단위는 조직·보고 항목(매출=관리분류, 비용=실적집계기준)·연월·시나리오, 금액은 원 단위 Decimal"],
    })
    write_json("gaps_with_explanations.json", {"meta": meta, "counts": linked["counts"], "gaps": linked["gaps"], "explanations": linked["explanations"]})

    # (4) master + (5) 07 자료 (07 자료는 master_*.json 파일만 읽는다)
    snaps = b["snapshots"]
    out, packs = {}, {}
    for state in ("provisional", "final"):
        m = master_stage.build(state, subs, snaps, g)
        if state == "final" and m["master_created"]:
            m["parser_assemble_master_comparison"] = master_compare.compare(subs, [s["org_code"] for s in m["master"]["sources"]], m)
        write_json(f"master_{state}.json", m)
        doc = json.loads((out_dir() / f"master_{state}.json").read_text(encoding="utf-8"))  # 파일에서 다시 읽음
        mat = materials_stage.build(state, doc, linked["explanations"], linked["counts"], linked["gaps"], show_reference_explanations=not args.hide_reference_explanations)
        write_json(f"report_materials_{state}.json", mat)
        pack = facts_stage.build(state, mat, doc)
        write_json(f"ai_input_{state}.json", message_stage.ai_input_doc(state, pack))
        out[state], packs[state] = (m, mat), pack

    # (6) 핵심 메시지 (AI 호출은 --ai 를 줄 때만)
    msgs = {s: message_stage.generate(s, packs[s], args.ai, args.force_ai) for s in ("provisional", "final")}
    write_json("key_messages.json", {"meta": meta, "model": msgs["final"]["model"], "prompt_version": msgs["final"]["prompt_version"], "settings": msgs["final"]["settings"], **msgs})

    # 검산
    f = out["final"][1]
    rows = {r["metric"]: r for r in f["summary_table"]["rows"]}
    got = {"매출": rows["매출"]["this_month"]["value_eok"], "영업비용": rows["영업비용"]["this_month"]["value_eok"], "영업이익": rows["영업이익"]["this_month"]["value_eok"],
           "계획 영업이익": rows["영업이익"]["plan_this_month"]["value_eok"], "연간 최신 추정 영업이익": f["op_bridge"]["steps"][-1]["value_eok"]}
    check = {k: {"expected": EXPECTED[k], "actual_eok": got[k], "match_at_1dp": got[k] == EXPECTED[k]} for k in EXPECTED}
    write_json("run_manifest.json", {"meta": meta, "expected_check_final": check, "scenario_summary": sc["summary"], "explanation_counts": linked["counts"],
                                     "master": {s: {"created": out[s][0]["master_created"], "approval": out[s][0]["approval_label"]} for s in out},
                                     "ai": {s: {"ai_sentences": msgs[s]["counts"]["ai"], "template_sentences": msgs[s]["counts"]["template"], "tokens": msgs[s]["tokens"], "error": msgs[s]["ai_error"]} for s in msgs}})
    (out_dir() / "summary_report.md").write_text(report_stage.build(meta, parsed, b, th, g, linked, sc, {k: out[k][0] for k in out}, {k: out[k][1] for k in out}, msgs, check), encoding="utf-8")
    print("OK", sc["summary"], linked["counts"])
    for k, v in check.items():
        print(k, v["expected"], v["actual_eok"], v["match_at_1dp"])


if __name__ == "__main__":
    try:
        main()
    except ConfigError as e:
        raise SystemExit(f"설정 오류: {e}")
