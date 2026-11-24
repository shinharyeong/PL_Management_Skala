-- AI 보고서용 뷰(#60). report-ai 가 읽기 전용으로 조회하는 6개: fact · v_op · gap · explanation · dim_version · dim_org
-- AI 앱이 쓰던 표와 이름·컬럼이 같아서 AI 는 AI_DATABASE_URL(읽기 전용 계정)과 AI_DB_SCHEMA=report 만 바꾸면 된다.
-- 초안: ai/docs/report_views.sql(#73). 결정(2026-11-24):
--   1. Gap 은 백엔드 정의(gap_comparison: 보고 항목 × 월 × 구분, 조건별 임계값, ≥) 하나. gap_type = 'ITEM_GAP'
--   2. version: 월간 확정 = 그 달 요청의 최신 master(master-YYYY-MM), 잠정 = master 가 없는 달의 조직별 최신 승인본
--      (provisional-YYYY-MM, 홈 dataStatus=PROVISIONAL 과 같은 규칙), 연간 확정 계획 = 최신 연간 master(annual-YYYY)
--   3. 계획(PLAN)은 월간 요청 때 고정한 확정 연간 계획 제출본(request_target.confirmed_plan_submission_id)에서 가져온다(홈과 같음)
--   4. 요청 연도 금액만(financial_value.year = 요청 연도). 양식의 전년 열은 참고값이라 넣지 않는다(#66·#68). 전년 비교는 전년도 version
-- 금액은 numeric(30,6) 원이고 빈칸은 행이 없다(0 으로 채우지 않음). 매출 = REVENUE 항목, 영업비용 = COST 항목. 임계값 숫자는 내보내지 않는다.

CREATE SCHEMA IF NOT EXISTS report;

-- (내부) 보고 항목 경로. cat1 = 루트(매출·영업비용) 바로 아래, cat2 = 그 아래(깊이 4 이상), item = 말단
CREATE VIEW report.report_item_path AS
WITH RECURSIVE up AS (
    SELECT i.id AS leaf_id, i.parent_id, i.name, 0 AS depth FROM report_item i
    UNION ALL
    SELECT up.leaf_id, p.parent_id, p.name, up.depth + 1 FROM up JOIN report_item p ON p.id = up.parent_id
), paths AS (
    SELECT leaf_id, array_agg(name ORDER BY depth DESC) AS path FROM up GROUP BY leaf_id
)
SELECT leaf_id,
       CASE WHEN cardinality(path) >= 2 THEN path[2] END AS cat1,
       CASE WHEN cardinality(path) >= 4 THEN path[3] END AS cat2,
       path[cardinality(path)] AS item
  FROM paths;

-- (내부) version 별로 조직마다 어느 제출본이 값인지, 계획은 어느 제출본에서 가져오는지
CREATE VIEW report.report_source AS
WITH monthly AS (
    SELECT r.id AS request_id, p.year, r.month
      FROM submission_request r JOIN annual_plan p ON p.id = r.annual_plan_id
     WHERE r.type = 'MONTHLY_ACTUAL'
), latest_master AS (
    SELECT DISTINCT ON (m.request_id) m.id AS master_id, m.request_id, m.created_at
      FROM master_version m ORDER BY m.request_id, m.seq DESC
), latest_annual AS (
    SELECT DISTINCT ON (p.year) a.id AS master_id, a.request_id, p.year, a.created_at
      FROM annual_master a
      JOIN submission_request r ON r.id = a.request_id
      JOIN annual_plan p ON p.id = r.annual_plan_id
     ORDER BY p.year, a.created_at DESC, a.seq DESC
), picked AS (
    SELECT 'master'::text AS kind, mo.year, mo.month, mo.request_id, ms.organization_id, ms.submission_id,
           lm.created_at AS loaded_at
      FROM latest_master lm
      JOIN monthly mo ON mo.request_id = lm.request_id
      JOIN master_source ms ON ms.master_id = lm.master_id
    UNION ALL
    SELECT 'provisional'::text, mo.year, mo.month, mo.request_id, t.organization_id, la.id, la.reviewed_at
      FROM monthly mo
      JOIN request_target t ON t.request_id = mo.request_id AND t.selected AND NOT t.excluded
      JOIN LATERAL (SELECT s.id, s.reviewed_at FROM submission s
                     WHERE s.request_id = t.request_id AND s.organization_id = t.organization_id
                       AND s.status = 'APPROVED'
                     ORDER BY s.approved_seq DESC NULLS LAST, s.id DESC LIMIT 1) la ON true
     WHERE NOT EXISTS (SELECT 1 FROM master_version m WHERE m.request_id = mo.request_id)
    UNION ALL
    SELECT 'annual'::text, la.year, NULL::integer, la.request_id, ams.organization_id, ams.submission_id, la.created_at
      FROM latest_annual la
      JOIN annual_master_source ams ON ams.master_id = la.master_id
)
SELECT CASE WHEN pk.kind = 'annual' THEN 'annual-' || pk.year
            ELSE pk.kind || '-' || pk.year || '-' || lpad(pk.month::text, 2, '0') END AS version,
       pk.kind, pk.year, pk.month, pk.request_id, pk.organization_id, pk.submission_id, pk.loaded_at,
       CASE WHEN pk.kind = 'annual' THEN NULL ELSE rt.confirmed_plan_submission_id END AS plan_submission_id
  FROM picked pk
  LEFT JOIN request_target rt ON rt.request_id = pk.request_id AND rt.organization_id = pk.organization_id;

-- dim_org: 코드·이름만(조직 계층은 아직 없음)
CREATE VIEW report.dim_org AS
SELECT o.code AS org_code, o.name AS org_name FROM organization o WHERE o.active;

-- dim_version: is_latest = 기본 기준(RPT-07 "최근 확정 월"). 월간 master 가 있으면 가장 최근 master,
-- 없으면 가장 최근 잠정 달, 그것도 없으면 가장 최근 연간 계획
CREATE VIEW report.dim_version AS
SELECT v.version, v.kind,
       CASE v.kind WHEN 'master' THEN '확정 ' || v.year || '년 ' || v.month || '월'
                   WHEN 'provisional' THEN '잠정 ' || v.year || '년 ' || v.month || '월'
                   ELSE v.year || '년 연간 계획' END AS label,
       v.year, v.month,
       to_char(v.loaded_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SS') AS loaded_at,
       CASE v.kind WHEN 'master' THEN 'master' WHEN 'annual' THEN 'annual_master' ELSE 'approved' END AS source,
       NULL::integer AS row_count,
       (row_number() OVER (ORDER BY CASE v.kind WHEN 'master' THEN 0 WHEN 'provisional' THEN 1 ELSE 2 END,
                                    v.year DESC, v.month DESC NULLS LAST) = 1)::integer AS is_latest
  FROM (SELECT version, kind, year, month, max(loaded_at) AS loaded_at
          FROM report.report_source GROUP BY version, kind, year, month) v;

-- fact: 말단 항목만(합계 없음). 월간 version 은 실적·추정을 그 제출본에서, 계획을 확정 연간 계획에서.
-- 연간 version 은 연간 계획 제출본의 그해 값 전부
CREATE VIEW report.fact AS
SELECT s.version, o.code AS org_code, o.name AS org_name,
       CASE i.kind WHEN 'REVENUE' THEN '매출' ELSE '영업비용' END AS kind,
       p.cat1, p.cat2, p.item, fl.description AS detail, fl.team_name AS team, fl.person_in_charge AS manager,
       sub.original_filename || ' / ' || fl.source_sheet || '!' || fv.source_cell AS src,
       fv.year, fv.month, fv.scenario, fv.amount AS amount_won
  FROM report.report_source s
  JOIN organization o ON o.id = s.organization_id
  JOIN submission sub ON sub.id = s.submission_id
  JOIN financial_line fl ON fl.submission_id = s.submission_id
  JOIN financial_value fv ON fv.line_id = fl.id AND fv.year = s.year
                         AND (s.kind = 'annual' OR fv.scenario IN ('ACTUAL', 'ESTIMATE'))
  JOIN report_item i ON i.id = fl.report_item_id AND i.kind IN ('REVENUE', 'COST')
  JOIN report.report_item_path p ON p.leaf_id = i.id
UNION ALL
SELECT s.version, o.code, o.name,
       CASE i.kind WHEN 'REVENUE' THEN '매출' ELSE '영업비용' END,
       p.cat1, p.cat2, p.item, fl.description, fl.team_name, fl.person_in_charge,
       sub.original_filename || ' / ' || fl.source_sheet || '!' || fv.source_cell,
       fv.year, fv.month, fv.scenario, fv.amount
  FROM report.report_source s
  JOIN organization o ON o.id = s.organization_id
  JOIN submission sub ON sub.id = s.plan_submission_id
  JOIN financial_line fl ON fl.submission_id = s.plan_submission_id
  JOIN financial_value fv ON fv.line_id = fl.id AND fv.year = s.year AND fv.scenario = 'PLAN'
  JOIN report_item i ON i.id = fl.report_item_id AND i.kind IN ('REVENUE', 'COST')
  JOIN report.report_item_path p ON p.leaf_id = i.id;

-- v_op: 조직·월·구분별 매출·영업비용·영업이익(= 매출 − 영업비용). 홈 HOME-03~06 과 같은 규칙
CREATE VIEW report.v_op AS
SELECT version, org_code, org_name, year, month, scenario,
       SUM(CASE WHEN kind = '매출' THEN amount_won ELSE 0 END) AS revenue_won,
       SUM(CASE WHEN kind = '영업비용' THEN amount_won ELSE 0 END) AS cost_won,
       SUM(CASE WHEN kind = '매출' THEN amount_won ELSE -amount_won END) AS op_won
  FROM report.fact
 GROUP BY version, org_code, org_name, year, month, scenario;

-- gap: 임계값을 넘은 비교만. 상태는 백엔드 ReviewTabs 와 같다. gap_id = 'RI-<review_item.id>-<basis>', rate_percent 는 %
CREATE VIEW report.gap AS
SELECT s.version, 'RI-' || ri.id || '-' || gc.basis AS gap_id, 'ITEM_GAP'::text AS gap_type,
       o.code AS org_code, o.name AS org_name, p.item, ri.year, ri.month, ri.scenario, gc.basis,
       gc.baseline_amount AS previous_won, gc.current_amount AS current_won, gc.gap_amount AS diff_won,
       round(gc.gap_rate, 2)::text AS rate_percent,
       CASE WHEN ri.workflow_state = 'EXCLUDED' THEN 'EXCLUDED'
            WHEN cur.verdict = 'ACCEPTED' THEN 'ACCEPTED'
            WHEN cur.requested_at IS NOT NULL AND cur.verdict = 'PENDING'
                 AND (cur.answer IS NULL OR btrim(cur.answer) = '') THEN 'IN_PROGRESS'
            WHEN cur.requested_at IS NOT NULL AND cur.verdict = 'PENDING' THEN 'RE_REVIEW'
            WHEN ri.workflow_state = 'SELECTED' THEN 'SELECTED'
            ELSE 'NEEDS_REVIEW' END AS status,
       CASE WHEN cur.verdict = 'ACCEPTED' THEN '수락'
            WHEN cur.id IS NOT NULL THEN '검토중'
            ELSE '없음' END AS explanation_status
  FROM report.report_source s
  JOIN review_item ri ON ri.submission_id = s.submission_id AND ri.active AND ri.year = s.year
  JOIN gap_comparison gc ON gc.review_item_id = ri.id AND gc.exceeded
  JOIN report_item i ON i.id = ri.report_item_id AND i.kind IN ('REVENUE', 'COST')
  JOIN report.report_item_path p ON p.leaf_id = i.id
  JOIN organization o ON o.id = s.organization_id
  LEFT JOIN review_explanation cur ON cur.review_item_id = ri.id AND cur.round = ri.current_round;

-- explanation: 소명 회차마다 한 행(같은 소명을 Gap 비교 기준 수만큼 반복하지 않음). 원문(answer)은 수락된 것만.
-- link_type: GAP = 임계값을 넘은 항목의 소명(gap_id 로 gap 과 이어짐), CHANGE = 변경 확인 단계의 소명(gap_id 없음)
CREATE VIEW report.explanation AS
SELECT s.version, 'EX-' || re.id AS explanation_id, 'RI-' || ri.id AS gap_key,
       CASE WHEN g.basis IS NOT NULL THEN 'RI-' || ri.id || '-' || g.basis END AS gap_id,
       o.code AS org_code, o.name AS org_name,
       CASE i.kind WHEN 'REVENUE' THEN '매출' ELSE '영업비용' END AS kind, p.item, ri.year, ri.month, ri.scenario,
       CASE WHEN g.basis IS NOT NULL THEN 'GAP' ELSE 'CHANGE' END AS link_type,
       CASE WHEN re.verdict = 'ACCEPTED' THEN '수락' ELSE '검토중' END AS status,
       re.verdict, re.round AS rounds,
       CASE WHEN re.verdict = 'ACCEPTED' THEN re.answer END AS answer,
       NULL::text AS src
  FROM report.report_source s
  JOIN review_item ri ON ri.submission_id = s.submission_id AND ri.active AND ri.year = s.year
  JOIN review_explanation re ON re.review_item_id = ri.id
  JOIN report_item i ON i.id = ri.report_item_id AND i.kind IN ('REVENUE', 'COST')
  JOIN report.report_item_path p ON p.leaf_id = i.id
  JOIN organization o ON o.id = s.organization_id
  LEFT JOIN LATERAL (SELECT gc.basis FROM gap_comparison gc
                      WHERE gc.review_item_id = ri.id AND gc.exceeded
                      ORDER BY gc.basis LIMIT 1) g ON true;

-- 읽기 전용 계정은 운영에서 만든다(마이그레이션 계정에는 CREATE ROLE 권한이 없을 수 있음). 뷰는 만든 계정 권한으로
-- 원본 테이블을 읽으므로 report_ro 에는 아래 6개 뷰의 SELECT 만 준다. 내부 뷰(report_item_path, report_source)는 열지 않는다.
--   CREATE ROLE report_ro LOGIN PASSWORD '<운영에서 지정>';
--   GRANT USAGE ON SCHEMA report TO report_ro;
--   GRANT SELECT ON report.fact, report.v_op, report.gap, report.explanation, report.dim_version, report.dim_org TO report_ro;
--   ALTER ROLE report_ro SET default_transaction_read_only = on;
--   ALTER ROLE report_ro SET statement_timeout = '20s';
