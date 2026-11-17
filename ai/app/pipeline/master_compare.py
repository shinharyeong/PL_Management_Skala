"""작업 1(a): 파서 assemble_master 와 ai/ 마스터 단계를 같은 입력(최종본 파일)으로 비교한다. 파서 코드는 import 해서 읽기 전용으로만 쓴다."""
from __future__ import annotations

import sys
from collections import Counter, defaultdict
from decimal import Decimal
from pathlib import Path

from .common import PARSER_SRC, REQUEST_MONTH, REQUEST_YEAR

sys.path.insert(0, str(PARSER_SRC))
from excel_parser.benchmark.reconstruction import parse_submission  # noqa: E402
from excel_parser.master import assemble_master  # noqa: E402


def compare(subs: dict, orgs: list[str], our_master: dict) -> dict:
    files = [str(Path(subs[o]["FINAL"]["path"]).resolve()) for o in orgs]
    closing = f"{REQUEST_YEAR}-{REQUEST_MONTH:02d}"
    submissions = [parse_submission((p, "ai-report-pipeline", closing))["submission"] for p in files]
    pm = assemble_master(submissions, expected_organizations=orgs, closing_month=closing, type_name="ai-report-pipeline")
    # 파서의 평탄 상세 레코드(assemble_master 입력)에서 직접 합산한 이번 달 실적(참고)
    flat = defaultdict(Decimal)
    flat_n = Counter()
    for s in submissions:
        for r in s["records"]:
            if r["year"] == REQUEST_YEAR and r["month"] == REQUEST_MONTH and r["scenario"] == "actual" and r["aggregation"] == "month" and r["confirmed"] and r["normalized_value"] is not None:
                flat[r["domain"]] += Decimal(r["normalized_value"])
                flat_n[r["domain"]] += 1
    parser_company = {(t["domain"], t["scenario"]): Decimal(t["normalized_value"]) for t in pm["totals"] if t["scope"] == "company" and t["year"] == REQUEST_YEAR and t["month"] == REQUEST_MONTH}
    ours = our_master["master"]["totals"]
    sel_files = sorted(s["source_file"] for s in pm["selected_submissions"])
    our_files = sorted(str(Path(subs[o]["FINAL"]["path"]).resolve()) for o in orgs)
    rows = [
        {"check": "선택된 제출본 목록(파일 경로)", "parser_assemble_master": f"{len(sel_files)}개", "ai_pipeline": f"{len(our_files)}개", "match": sel_files == our_files},
        {"check": "선택된 조직 수", "parser_assemble_master": pm["selected_organization_count"], "ai_pipeline": our_master["master"]["source_count"], "match": pm["selected_organization_count"] == our_master["master"]["source_count"]},
        {"check": "조직 상세(detail) 건수", "parser_assemble_master": pm["detail_count"], "ai_pipeline": "상세 시트 월별 금액 사용", "match": False},
        {"check": "전사 이번 달 실적 매출 합계(원)", "parser_assemble_master": str(parser_company.get(("revenue", "actual"), "합계 없음")), "ai_pipeline": str(ours["revenue_won"]), "match": parser_company.get(("revenue", "actual")) == ours["revenue_won"]},
        {"check": "전사 이번 달 실적 비용 합계(원)", "parser_assemble_master": str(parser_company.get(("expense", "actual"), "합계 없음")), "ai_pipeline": str(ours["cost_won"]), "match": parser_company.get(("expense", "actual")) == ours["cost_won"]},
        {"check": "(참고) 파서 평탄 상세 레코드 직접 합산 매출(원)", "parser_assemble_master": str(flat["revenue"]), "ai_pipeline": str(ours["revenue_won"]), "match": flat["revenue"] == ours["revenue_won"]},
        {"check": "(참고) 파서 평탄 상세 레코드 직접 합산 비용(원)", "parser_assemble_master": str(flat["expense"]), "ai_pipeline": str(ours["cost_won"]), "match": flat["expense"] == ours["cost_won"]},
    ]
    issues = Counter(i["code"] for i in pm["issues"])
    return {
        "parser_function": "excel_parser.master.assemble_master (입력: excel_parser.benchmark.reconstruction.parse_submission 의 평탄 상세 레코드)",
        "parser_status": pm["status"], "parser_issue_counts": dict(issues), "excluded_record_count": pm["excluded_record_count"],
        "rows": rows,
        "can_call_assemble_master": False,
        "reason": ("더미 엑셀에는 명시적 상세 ID(detail_id)가 없어 assemble_master 가 상세 전부를 detail_identity_unresolved 로 제외하고 partial 을 반환함. "
                   "호출하려면 행 번호나 파일명으로 상세 ID를 만들어야 하는데 db-mapping §4 가 금지함(행 번호·파일명으로 업무 ID를 발급하지 않음). 억지로 맞추지 않고 기존 방식을 유지함. "
                   "조직별 제출본 선택(조직당 1개)은 파서와 동일하게 일치함."),
        "flat_detail_counts": dict(flat_n),
    }
