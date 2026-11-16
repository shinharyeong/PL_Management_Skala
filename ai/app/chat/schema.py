"""챗봇 조회용 SQLite 구조(DDL)와 AI 에게 보여 줄 구조 설명.

fact 는 합계 행 없이 가장 작은 항목만 담는다. 영업이익은 v_op 뷰가 매출 − 영업비용으로 계산한다.
version 이 곧 시점이다: 확정 master / 잠정 / 월마감(close-<연도>-<월>).
"""
from __future__ import annotations

import hashlib

ALLOWED_TABLES = ("fact", "v_op", "gap", "explanation", "dim_version", "dim_org")
# SQL 에서 쓸 수 있는 인자(이름 있는 바인딩)
PARAMS = ("version", "version_prev", "base_year", "base_month", "scn")

DDL = """
CREATE TABLE IF NOT EXISTS dim_version(
  version TEXT PRIMARY KEY, kind TEXT NOT NULL, label TEXT, year INTEGER, month INTEGER, loaded_at TEXT, source TEXT, row_count INTEGER, is_latest INTEGER DEFAULT 0);
CREATE TABLE IF NOT EXISTS dim_org(org_code TEXT PRIMARY KEY, org_name TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS fact(
  version TEXT NOT NULL, org_code TEXT NOT NULL, org_name TEXT, kind TEXT NOT NULL,
  cat1 TEXT, cat2 TEXT, item TEXT, detail TEXT, team TEXT, manager TEXT, src TEXT,
  year INTEGER NOT NULL, month INTEGER NOT NULL, scenario TEXT NOT NULL, amount_won INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS gap(
  version TEXT NOT NULL, gap_id TEXT, gap_type TEXT, org_code TEXT, org_name TEXT, item TEXT, year INTEGER, month INTEGER, scenario TEXT, basis TEXT,
  previous_won INTEGER, current_won INTEGER, diff_won INTEGER, rate_percent TEXT, status TEXT, explanation_status TEXT);
CREATE TABLE IF NOT EXISTS explanation(
  version TEXT NOT NULL, explanation_id TEXT, gap_key TEXT, gap_id TEXT, org_code TEXT, org_name TEXT, kind TEXT, item TEXT, year INTEGER, month INTEGER, scenario TEXT,
  link_type TEXT, status TEXT, verdict TEXT, rounds INTEGER, answer TEXT, src TEXT);
CREATE VIEW IF NOT EXISTS v_op AS
  SELECT version, org_code, org_name, year, month, scenario,
         SUM(CASE WHEN kind='매출' THEN amount_won ELSE 0 END) AS revenue_won,
         SUM(CASE WHEN kind='영업비용' THEN amount_won ELSE 0 END) AS cost_won,
         SUM(CASE WHEN kind='매출' THEN amount_won ELSE -amount_won END) AS op_won
  FROM fact GROUP BY version, org_code, org_name, year, month, scenario;
"""

INDEXES = """
CREATE INDEX IF NOT EXISTS ix_fact_main ON fact(version, org_code, kind, year, month, scenario);
CREATE INDEX IF NOT EXISTS ix_fact_item ON fact(version, kind, year, month, scenario, cat1, cat2);
CREATE INDEX IF NOT EXISTS ix_gap_v ON gap(version, org_code, year, month);
CREATE INDEX IF NOT EXISTS ix_expl_v ON explanation(version, org_code, year, month);
"""

# AI 에게 보내는 구조 설명(데이터 값은 포함하지 않는다)
SCHEMA_DOC = """테이블/뷰(SQLite). 금액 컬럼은 모두 원 단위 정수이고 이름이 _won 으로 끝난다.
- fact(version, org_code, org_name, kind('매출'|'영업비용'), cat1, cat2, item, detail, team, manager, src, year, month, scenario('ACTUAL'|'ESTIMATE'|'PLAN'), amount_won)
    합계 행 없이 가장 작은 항목만 있다. cat1/cat2/item 은 항목 계층(대분류/중분류/소분류).
- v_op(version, org_code, org_name, year, month, scenario, revenue_won, cost_won, op_won)  -- 조직·월별 매출, 영업비용, 영업이익(= 매출 − 영업비용)
- gap(version, gap_id, gap_type('AMOUNT_REVISION'|'OP_GAP'), org_code, org_name, item, year, month, scenario, basis, previous_won, current_won, diff_won, rate_percent, status, explanation_status)
- explanation(version, explanation_id, gap_key, gap_id, org_code, org_name, kind, item, year, month, scenario, link_type, status('수락'|'검토중'), verdict, rounds, answer, src)
    answer 는 수락된 소명의 원문만 있고 나머지는 NULL 이다.
- dim_version(version, kind, label, year, month, source), dim_org(org_code, org_name)
사용할 수 있는 인자: :version(조회 기준 버전), :version_prev(이전 버전), :base_year, :base_month, :scn('ACTUAL' 기본)"""

RULES = """규칙:
1. SELECT 또는 WITH 한 문장만 쓴다. 세미콜론·주석·INSERT/UPDATE/DELETE/DROP/ATTACH/PRAGMA 는 금지. 데이터를 바꾸거나 지우라는 요청은 SQL 을 만들지 말고 unknown 에 "조회만 가능합니다"라고 쓴다.
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

SCHEMA_HASH = hashlib.sha256((SCHEMA_DOC + RULES + EXAMPLES + DDL).encode("utf-8")).hexdigest()[:16]
