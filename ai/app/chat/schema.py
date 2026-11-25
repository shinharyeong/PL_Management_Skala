"""백엔드 보고서용 뷰 계약과 AI에게 보여 줄 구조 설명. 스키마 생성은 백엔드가 맡는다.

fact 는 합계 행 없이 가장 작은 항목만 담는다. 영업이익은 v_op 뷰가 매출 − 영업비용으로 계산한다.
version 이 곧 시점이다: 확정 master / 잠정 provisional / 연간 계획 annual.
"""
from __future__ import annotations

import hashlib

ALLOWED_TABLES = ("fact", "v_op", "gap", "explanation", "dim_version", "dim_org")
# SQL 에서 쓸 수 있는 인자(이름 있는 바인딩)
PARAMS = ("version", "version_prev", "base_year", "base_month", "scn")

# AI 에게 보내는 구조 설명(데이터 값은 포함하지 않는다)
SCHEMA_DOC = """테이블/뷰(PostgreSQL). 금액 컬럼은 모두 원 단위이고(소수가 있을 수 있다) 이름이 _won 으로 끝난다. 값이 없는 칸은 행이 없다(0 으로 채우지 않는다): 행이 없는 것과 0 을 구분한다.
- fact(version, org_code, org_name, kind('매출'|'영업비용'), cat1, cat2, item, detail, team, manager, src, year, month, scenario('ACTUAL'|'ESTIMATE'|'PLAN'), amount_won)
    합계 행 없이 가장 작은 항목만 있다. cat1/cat2/item 은 항목 계층(대분류/중분류/소분류). 한 version 의 year 는 그 version 의 연도 하나뿐이다(전년 값은 전년도 version 에 있다).
    team 은 팀명, manager 는 담당자다(양식에 적힌 그대로, 비어 있을 수 있다).
- v_op(version, org_code, org_name, year, month, scenario, revenue_won, cost_won, op_won)  -- 조직·월별 매출, 영업비용, 영업이익(= 매출 − 영업비용)
- gap(version, gap_id, gap_type('ITEM_GAP'), org_code, org_name, item, year, month, scenario, basis('PLAN'|'PREVIOUS'), previous_won, current_won, diff_won, rate_percent, status('NEEDS_REVIEW'|'SELECTED'|'IN_PROGRESS'|'RE_REVIEW'|'ACCEPTED'|'EXCLUDED'), explanation_status('수락'|'검토중'|'없음'))
    항목×월×구분 단위의 Gap 이고 임계값을 넘은 것만 있다. previous_won 은 비교 기준(계획 또는 직전 제출본) 금액이다. 임계값 숫자는 없다.
- explanation(version, explanation_id, gap_key, gap_id, org_code, org_name, kind, item, year, month, scenario, link_type, status('수락'|'검토중'), verdict, rounds, answer, src)
    소명 회차마다 한 행이다. answer 는 수락된 소명의 원문만 있고 나머지는 NULL 이다. gap_key 는 소명이 붙은 검토 항목이다.
    link_type 'GAP' 은 임계값을 넘은 항목의 소명이다. 하나의 gap_key에 PLAN/PREVIOUS 비교가 함께 있을 수 있으므로 gap.gap_id = explanation.gap_key || '-' || gap.basis 로 연결한다. explanation.gap_id는 그중 첫 비교만 가리킨다. 'CHANGE' 는 변경 확인 단계의 소명이고 gap_id 가 NULL 이다.
- dim_version(version, kind('master'|'provisional'|'annual'), label, year, month, source), dim_org(org_code, org_name)
    version 은 월마다 하나다: master-YYYY-MM(확정) 또는 provisional-YYYY-MM(master 가 없는 달의 조직별 최신 승인본, 잠정).
    annual-YYYY 는 그해 확정 연간 계획(month 없음)이다. 연간 계획 자체를 물을 때만 version = 'annual-YYYY' 로 쓴다.
사용할 수 있는 인자: :version(조회 기준 버전), :version_prev(이전 버전), :base_year, :base_month, :scn('ACTUAL' 기본)"""

RULES = """규칙:
1. SELECT 또는 WITH 한 문장만 쓴다. 세미콜론·주석·INSERT/UPDATE/DELETE/DROP/CREATE/COPY/SET/INTO 는 금지. 데이터를 바꾸거나 지우라는 요청은 SQL 을 만들지 말고 unknown 에 "조회만 가능합니다"라고 쓴다.
2. fact, v_op, gap, explanation 을 쓰면 반드시 WHERE 에 version = :version 조건을 넣는다. 다른 버전과 비교할 때만 version = :version_prev 를 추가로 쓴다.
3. 월·연도·조직은 질문에서 해석된 값이 주어지면 그 값을 쓰고, 없으면 :base_year, :base_month 를 쓴다. 조직은 org_code = '<조직코드>' 처럼 코드로 비교한다(코드는 해석된 값으로 주어진다).
   해석된 값에 조직이 없으면(전사 합산) 조직 필터를 넣지 말고 전사로 합산한다. 이때 되묻지 않는다.
4. fact 나 v_op 를 합산할 때는 scenario 를 반드시 지정한다(ACTUAL 실적 / ESTIMATE 추정 / PLAN 계획). 서로 다른 scenario 를 섞어서 합산하지 않는다.
   월별 추이는 각 월의 ACTUAL 또는 ESTIMATE 만 쓰고(scenario IN ('ACTUAL','ESTIMATE')) PLAN 은 쓰지 않는다. 계획과 비교할 때는 같은 월의 ACTUAL 과 PLAN 을 각각 따로 집계해 나란히 놓는다.
5. 소명 건수는 COUNT(DISTINCT gap_key) 로 센다(재제출 이력 때문에 행 수와 다를 수 있다). Gap 건수는 gap 의 행 수이며, Gap 이 0건인 조직은 목록에 없어도 정상이다(필요하면 dim_org 와 LEFT JOIN 해서 0건을 보여 준다).
6. 비교 대상이나 기준이 빠진 질문(예: 무엇을 지난달과 비교하는지 없음)은 SQL 대신 clarify 에 질문과 선택지를 쓴다. 조직이 없는 질문은 되묻지 않는다.
7. 숫자를 직접 쓰지 말고 컬럼 값만 선택한다(합계는 SUM 등 집계 함수). 금액 컬럼 이름은 _won 을 유지한다.
8. 결과는 1000행 이하가 되게 ORDER BY 와 LIMIT 을 쓴다.
9. 데이터에 없는 것(주별 등)은 sql 대신 unknown 에 이유를 쓴다.
10. PostgreSQL 문법이다: SELECT 의 집계 아닌 컬럼은 모두 GROUP BY 에 넣는다. 정수끼리 나누면 정수가 되므로 비율은 SUM(x) * 1.0 / NULLIF(SUM(y), 0) 처럼 쓴다. 쓸 수 있는 함수는 SUM COUNT AVG MIN MAX ROUND ABS COALESCE NULLIF SUBSTR LOWER UPPER LENGTH TRIM STRING_AGG ROW_NUMBER RANK DENSE_RANK LAG LEAD CAST GREATEST LEAST FLOOR CEIL 뿐이다(IFNULL·IIF·PRINTF·GROUP_CONCAT 은 없다). 스키마 이름을 붙이지 않는다.
응답 JSON: {"sql": "...", "clarify": null, "unknown": null}  또는 {"sql": null, "clarify": {"question": "...", "options": ["..."]}, "unknown": null}  또는 {"sql": null, "clarify": null, "unknown": "이유"}"""

EXAMPLES = """예시(질문 형식 → sql). <조직코드>·<월>은 형식 자리표시이며 실제 값은 해석된 값을 쓴다.
- "<월>월 조직별 영업이익" → SELECT org_name, revenue_won, cost_won, op_won FROM v_op WHERE version = :version AND year = :base_year AND month = :base_month AND scenario = :scn ORDER BY op_won DESC
- "<조직> 비용이 큰 항목 N개" → SELECT cat1, cat2, item, SUM(amount_won) AS amount_won FROM fact WHERE version = :version AND org_code = '<조직코드>' AND kind = '영업비용' AND year = :base_year AND month = :base_month AND scenario = :scn GROUP BY cat1, cat2, item ORDER BY amount_won DESC LIMIT N
- "Gap 목록" → SELECT gap_id, org_name, item, month, scenario, previous_won, current_won, diff_won, explanation_status FROM gap WHERE version = :version ORDER BY ABS(diff_won) DESC LIMIT 200
- "이전 버전 대비 <조직> 영업이익 변화" → SELECT a.month, a.op_won AS op_won, b.op_won AS prev_op_won, a.op_won - b.op_won AS diff_won FROM v_op a JOIN v_op b ON a.org_code = b.org_code AND a.year = b.year AND a.month = b.month AND a.scenario = b.scenario WHERE a.version = :version AND b.version = :version_prev AND a.org_code = '<조직코드>' ORDER BY a.month
- "<조직> 월별 매출 추이" → SELECT month, scenario, revenue_won FROM v_op WHERE version = :version AND org_code = '<조직코드>' AND year = :base_year AND scenario IN ('ACTUAL', 'ESTIMATE') ORDER BY month
- "소명은 모두 몇 건" → SELECT COUNT(DISTINCT gap_key) AS explanation_count FROM explanation WHERE version = :version
- "Gap 종류별 건수" → SELECT gap_type, COUNT(*) AS gap_count FROM gap WHERE version = :version GROUP BY gap_type ORDER BY gap_count DESC
- "<월>월 조직별 영업이익을 계획과 비교" → SELECT a.org_name, a.op_won AS actual_op_won, p.op_won AS plan_op_won, a.op_won - p.op_won AS diff_won FROM v_op a JOIN v_op p ON a.org_code = p.org_code AND a.year = p.year AND a.month = p.month WHERE a.version = :version AND p.version = :version AND a.scenario = 'ACTUAL' AND p.scenario = 'PLAN' AND a.year = :base_year AND a.month = :base_month ORDER BY diff_won"""

SCHEMA_HASH = hashlib.sha256((SCHEMA_DOC + RULES + EXAMPLES).encode("utf-8")).hexdigest()[:16]
