"""요약 리포트(summary_report.md) 생성. 모든 숫자는 위 단계의 출력에서 읽는다."""
from __future__ import annotations

import subprocess
from decimal import Decimal

from .common import REPO, REQUEST_MONTH, eok

SCEN_NOTE = {
    9: "월 이연: 12월 추정 +60백만(직전 대비)은 항목 수정 Gap으로 찾음. 11월 실적 −60백만은 직전에 11월이 '추정'이라 수정이 아니고, 영업이익 계획 대비 Gap(−6.78%, 기준 5% 초과·1천만 초과)에 기여.",
    10: "월 이연: 12월 추정 +60백만은 항목 수정 Gap. 11월 실적 −60백만은 영업이익 계획 대비 Gap(+6.58%)에 기여.",
    13: "기준 0: 12월 추정 0 → 60백만. 항목 수정 Gap으로 찾았고 기준값이 0이라 Gap률은 '기준 없음'으로 표시(0이 아니므로 기준과 무관하게 초과).",
    14: "10월 정정: 과거 달 ACTUAL −25백만이 직전(10월 승인본) 대비 항목 수정 Gap. 11월 실적은 변하지 않음. 10월 영업이익 직전 대비 −25백만(−2.26%)은 5% 미달이라 OP Gap은 없음.",
    15: "음수 조정: 11월 팩토링 0 → −20백만(실적). 계획 대비 항목 비교는 문서상 Gap 대상이 아니고 영업이익 변화 −1.16%로 5% 미달 → Gap 없음(못 찾음).",
    16: "코드 오류: 최초 업로드(F36-2026-11-01-invalid-code)는 파서 업무 검증에서 상세 60건이 invalid라 직전 비교에서 제외. 최종본(seq 2) vs 10월 승인본에서 11월 +20백만은 영업이익 −1.16%로 5% 미달 → Gap 없음(못 찾음).",
    17: "재제출 100→120→145: 11월 실적 직전(seq1=120) 대비 +25백만이 같은 달 재제출의 항목 수정 Gap으로 찾음. 계획 대비 영업이익은 +2.29%로 5% 미달이라 OP Gap 아님.",
    18: "소명 반려 후 보완: 11월 실적 +50백만은 영업이익 계획 대비 Gap(+12.96%)에 기여. 소명 history 1차 REJECTED → 2차 ACCEPTED 보존.",
    19: "복합: 국내교육훈련비 11월 +20백만(재제출에서 새로 생긴 변경)은 항목 수정 Gap, 12월 외주 추정 +70백만은 항목 수정 Gap(step A에서 생성, 최종본에서 이어받기 대상). 11월 매출 +40·외주비 +25백만은 영업이익 계획 대비 +4.0%로 5% 미달이라 Gap 아님.",
    20: "원인 미분해: 11월 매출 +80백만은 영업이익 계획 대비 Gap(+15.46%)에 기여. 원인별 금액은 소명 문장에 없으므로 나누지 않음(do_not_invent_causal_amounts).",
}


def _git(*a):
    return subprocess.run(["git", *a], cwd=REPO, capture_output=True, text=True).stdout


def build(meta, parsed, b, th, g, linked, sc, masters, mats, msgs, check) -> str:
    L = []
    w = L.append
    w("# AI 보고서 파이프라인 요약 리포트\n")
    w(f"- 브랜치: `{meta['branch']}` · 커밋(작업 시작점): `{meta['base_commit_recorded_before_branching']}` · 현재 HEAD: `{meta['commit'][:7]}` (커밋·푸시 안 함)")
    w("- 파서: `parser/` 룰 파서만 사용(API 키·AI 파서 미사용). 엑셀 63개를 메모리 API로 파싱.\n")
    w("## 입력 구성\n")
    fc = {}
    for p in parsed:
        fc[p["folder"]] = fc.get(p["folder"], 0) + 1
    w("| 폴더 | 파일 수 |\n|---|---|")
    for k, v in fc.items():
        w(f"| {k} | {v} |")
    w(f"\n- 최종본 기준: 사업부 17·19는 revisions, 나머지는 submission_2026_11. 사업부16의 revisions 파일은 코드오류 최초본(제외).")
    w(f"- 비교 체인: 사업부17·19 = baseline → seq1(superseded) → final, 사업부16 = baseline → final(코드오류본 제외), 나머지 = baseline → final.\n")
    w("## 적용한 규칙과 기준값\n")
    w(f"- Gap 규칙: `docs/domain-rules.md` §4. 원천 금액 수정은 임계값과 무관하게 소명 필수, 영업이익 Gap은 영업이익 행에서만, %와 금액 둘 다 엄격 초과(>)일 때 기준 초과.")
    w(f"- 기준값: 실적 {th['ACTUAL']['percent']}%·{eok(th['ACTUAL']['amount_won'])}억원({th['ACTUAL']['amount_won']:,}원), 추정 {th['ESTIMATE']['percent']}%·{eok(th['ESTIMATE']['amount_won'])}억원({th['ESTIMATE']['amount_won']:,}원). "
      f"**문서에는 '예)'로만 적혀 있는 예시값**이며 확정 기준이 아닙니다(시나리오_설명.md의 테스트 제안값과 같음).\n")
    s = sc["summary"]
    w("## ② 시나리오 25건 대조 (단계 1)\n")
    w(f"- 항목 수정 Gap으로 찾음 **{s['found_item_gap']}건** / 영업이익 Gap에 기여(간접) **{s['indirect_op_only']}건** / 못 찾음 **{s['not_found']}건** / 시나리오에 없는 추가 Gap **{s['extra_gaps']}건**\n")
    w("| 사업부 | 셀 | 월·구분 | 항목 | 결과 | CSV 차액(원) | 관측 차액 일치 |\n|---|---|---|---|---|---|---|")
    for r in sc["rows"]:
        w(f"| {r['division']} ({r['org_code']}) | {r['cell']} | {r['month']}월 {r['scenario']} | {r['item']} | {r['result']} | {Decimal(r['csv_diff_won']):,.0f} | {'예' if r['observed_matches_csv_diff'] else '아니오'} |")
    w("\n**못 찾은 8건의 이유:** 이번 달 실적 항목의 계획 대비 증감은 문서상 Gap 대상이 아니고(Gap은 영업이익에만), 직전 제출본에서 11월이 '추정'이라 같은 연월·시나리오 비교 대상이 없어 '수정'도 아닙니다. 영업이익 변화가 5% 이하면 영업이익 Gap도 생기지 않습니다. 시나리오 데이터는 항목 단위 Gap을 가정하고 Figma 03 Gap 화면도 항목 계층 표라서 **문서(영업이익 중심)와 시나리오·Figma가 다릅니다.** 문서가 우선이라 고치지 않았습니다.\n")
    w("**개별 결과**\n")
    for d, t in SCEN_NOTE.items():
        w(f"- 사업부{d}: {t}")
    w(f"- 그 외 사업부(1~8, 11, 12): 항목 수정 Gap 또는 영업이익 Gap 여부는 위 표 참조.\n")
    c = linked["counts"]
    w("## ③ 소명 연결 (단계 2)\n")
    w(f"- Gap(현재 유효) {c['gaps_live']}건: 항목 수정 Gap {c['item_revision_gaps']}건(소명 연결 {c['item_revision_gaps_with_explanation']}), 영업이익 Gap {c['op_gaps']}건(기여 소명 연결 {c['op_gaps_with_contributing_explanation']}). **소명 없음 {c['gaps_without_explanation']}건**.")
    w(f"- 소명 25키(26행) 중 항목 수정 Gap에 직접 연결 {c['explanations_linked_direct']}건, 영업이익 Gap에만 기여 {c['explanations_linked_op_gap_only']}건, **소명은 있는데 Gap 없음 {c['explanations_without_gap']}건**, 재소명 이력 있는 키 {c['keys_with_resubmission_history']}건(사업부18: 1차 REJECTED → 2차 ACCEPTED, 반려 사유 보존).")
    w("- 영업이익 Gap은 원래 소명 하나를 받아야 하지만, 데이터의 소명은 모두 항목 단위입니다. 항목 변동이 그 Gap의 비교 제출본에서 0이 아닌 차이를 만든 경우에만 '기여 소명'으로 연결했습니다(이 연결은 데이터 연결 방식이며 문서 규칙이 아닙니다).")
    w("- `do_not_invent_causal_amounts` 표시 건은 원인 금액을 나누거나 만들지 않았습니다.\n")
    w("## ④ master (단계 3)\n")
    for st, m in masters.items():
        w(f"- **{'잠정' if st == 'provisional' else '확정'}**: {m['approval_label']} → " + (f"`{m['master']['version']}` 생성(조직 {m['master']['source_count']}개 제출본 ID 기록, 완전 스냅샷 {m['master']['all_snapshots_complete']})" if m["master_created"] else f"master 미생성. 잠정 집계만(승인 {m['approved_orgs']}개, 미승인 {', '.join(u['org_code'] + ' ' + u['status'] for u in m['unapproved_orgs'])})"))
        pu = m.get("past_month_updates") or m.get("past_month_updates_on_approval")
        for p in pu:
            w(f"  - 과거 월 갱신(사업부14 10월 정정 반영): `{p['previous_version']}` → `{p['new_version']}` (F38 승인본만 교체). 이전 master 구성은 파일이 없어 baseline 승인본으로 가정")
    cmpd = masters["final"].get("parser_assemble_master_comparison")
    if cmpd:
        w("\n**파서 assemble_master 와 비교(최종본 20개 입력)**\n")
        w("| 확인 항목 | 파서 assemble_master | ai/app/pipeline | 일치 |\n|---|---|---|---|")
        for r in cmpd["rows"]:
            w(f"| {r['check']} | {r['parser_assemble_master']} | {r['ai_pipeline']} | {'일치' if r['match'] else '불일치'} |")
        w(f"\n- 파서 상태 `{cmpd['parser_status']}`, 이슈 {cmpd['parser_issue_counts']}. 호출 방식 전환: **안 함** — {cmpd['reason']}")
    w("- Contingency 입력은 데이터에 없어 반영하지 않음(보고 영업이익 = 합산 영업이익).\n")
    w("## ⑤ 07 자료 검산 (단계 4)\n")
    w("| 항목 | 내 기대값(억) | 계산값(억) | 일치(소수 1자리) |\n|---|---|---|---|")
    for k, v in check.items():
        w(f"| {k} | {v['expected']} | {v['actual_eok']} | {'예' if v['match_at_1dp'] else '아니오'} |")
    f = mats["final"]
    w(f"\n- 07 자료의 모든 숫자는 `{f['input_master_file']}` 에서만 읽음(이번 달 = master by_org, 비교 기준 = report_inputs, 각 숫자의 source 필드에 파일·경로·원천 기재). 반올림은 억원 소수 1자리 ROUND_HALF_UP 한 곳.")
    w(f"- 연간 브리지: 연간 계획 {f['op_bridge']['steps'][0]['value_eok']}억 → 최신 추정 {f['op_bridge']['steps'][-1]['value_eok']}억(증감 {f['op_bridge']['total_delta_eok']}억, 합계 검산 {f['op_bridge']['check_sum_ok']}).")
    w("- 주별 매출 증감률: **데이터 없음**(주 단위 값이 입력에 없음).")
    w(f"- 비용 상세: 중분류→소분류까지 가능(합계 {f['cost_detail']['total_this_month_eok']}억 = 영업비용). 상세 시트 A열 '대분류'는 룰 파서가 차원으로 내보내지 않아 **모름**.\n")
    w("## ⑥ 핵심 메시지 (단계 5)\n")
    for st, m in msgs.items():
        w(f"- **{'잠정' if st == 'provisional' else '확정'}**: AI 문장 {m['counts']['ai']}개 / 템플릿 {m['counts']['template']}개, 토큰 {m['tokens']}, AI 호출 시도 {m['ai_attempted']}, 오류 {m['ai_error']}")
    w("- AI 입력(사실 목록)은 `ai_input_provisional.json`, `ai_input_final.json`. 이 실행에서는 AI를 호출하지 않았고(터미널에서 `--ai --force-ai`로 실행), 문장은 같은 사실 목록에서 만든 템플릿입니다.\n")
    w("## ⑦ 해결 못 한 문제\n")
    w("1. **AI 호출 미실행**: 이 환경은 외부 접속이 막혀 있어 호출하지 않음. 터미널에서 `(ai/ 에서) python3 -B -m app.pipeline.run_pipeline --ai --force-ai` 실행 시 상태(잠정·확정)당 1회 호출(재시도 없음).")
    w("2. 문서(영업이익 Gap) vs 시나리오·Figma(항목 단위 Gap) 불일치로 시나리오 25건 중 8건이 Gap으로 안 잡힘. 문서에 따라 고치지 않음.")
    w("3. 기준값은 문서 '예)' 예시값만 있음 — 확정 기준이 필요하면 경영기획 합의가 필요.")
    w("4. 파서 assemble_master 호출 불가: 더미에 상세 ID가 없음(위 마스터 절 참조). 비용 상세 대분류·주별 증감률·Contingency는 데이터·파서 한계로 만들지 못함.")
    w("5. 영업이익 Gap의 소명은 항목 소명으로 갈음해 연결했으며, 영업이익 단위 소명 자체는 데이터에 없음.\n")
    w("## ⑧ git status\n")
    w("```\n" + (_git("status", "--short") or "(변경 없음)") + "```")
    return "\n".join(L) + "\n"
