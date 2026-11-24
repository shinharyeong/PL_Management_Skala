-- AI 보고서용 뷰 6개 초안 (이슈 #60). AI 가 지금 조회하는 표와 같은 이름·같은 컬럼이다.
--   fact · v_op · gap · explanation · dim_version · dim_org   (스키마 report)
-- 이 파일은 AI 담당자가 쓴 초안이다. 백엔드가 V{yyyyMMdd_HHmm}__report_views.sql 로 넣고 검토한다(마이그레이션은 백엔드 몫).
-- AI 쪽은 AI_DATABASE_URL 을 읽기 전용 계정으로, AI_DB_SCHEMA=report 로 바꾸기만 하면 된다.
--
-- 아래 규칙은 가이드의 '추천'을 따른 것이다. 회의로 확정되기 전까지는 초안이다.
--   결정 1(Gap 정의): 백엔드 정의(gap_comparison: 항목×월×구분, 켜고 끄는 임계값, ≥)를 그대로 쓴다. gap_type 은 'ITEM_GAP' 하나이고
--           조직 영업이익 Gap(OP_GAP)·이월(CARRIED_OVER_ELIGIBLE)은 만들지 않았다(→ 아직 AI 의 'Gap 카드' 화면은 비어 보인다).
--   결정 2(version): 확정 = 그 달 요청의 최신 master, 잠정 = master 가 없는 달의 조직별 최신 승인본. 홈(PnlSnapshotLoader)과 같은 규칙이다.
--           version 문자열은 master-YYYY-MM / provisional-YYYY-MM. 한 달에 version 은 하나다.
--   계획(PLAN) 값은 월간 요청 때 고정한 확정 연간 계획 제출본(request_target.confirmed_plan_submission_id)에서 가져온다(홈과 같음).
--   요청 연도 값만 쓴다(financial_value.year = 요청 연도). 전년 비교는 전년도 version 으로 한다(#66·#68).
--   금액은 numeric(30,6) 원이고 빈칸은 행이 없다(0 으로 채우지 않는다). 매출 = REVENUE 항목 합, 영업비용 = COST 항목 합(홈과 같음, DERIVED 제외).
--   fact.manager 는 #58 전까지 NULL 이다. gap 에는 임계값 숫자를 내보내지 않는다.
--
-- 테이블 이름은 접두 없이 쓴다(백엔드 마이그레이션은 기본 스키마에서 실행된다). 뷰는 report 스키마에 만든다.

CREATE SCHEMA IF NOT EXISTS report;

-- ---------------------------------------------------------------------------
-- (내부 보조) 보고 항목 경로: 루트 → 말단. cat1 = 루트 바로 아래, cat2 = 그 아래(깊이 4 이상일 때), item = 말단. 읽기 전용 역할에는 열지 않는다.
CREATE OR REPLACE VIEW report.report_item_path AS
WITH RECURSIVE up AS (
    SELECT i.id AS leaf_id, i.id AS cur_id, i.parent_id, i.name, 0 AS depth FROM report_item i
    UNION ALL
    SELECT up.leaf_id, p.id, p.parent_id, p.name, up.depth + 1 FROM up JOIN report_item p ON p.id = up.parent_id
), paths AS (
    SELECT leaf_id, array_agg(name ORDER BY depth DESC) AS path FROM up GROUP BY leaf_id
)
SELECT leaf_id,
       CASE WHEN cardinality(path) >= 2 THEN path[2] END AS cat1,
       CASE WHEN cardinality(path) >= 4 THEN path[3] END AS cat2,
       path[cardinality(path)] AS item
FROM paths;

-- (내부 보조) version 별 출처: 조직마다 어느 제출본이 그 version 의 값인가, 계획은 어느 제출본에서 가져오는가.
CREATE OR REPLACE VIEW report.report_source AS
WITH req AS (
    SELECT r.id AS request_id, p.year, r.month
      FROM submission_request r JOIN annual_plan p ON p.id = r.annual_plan_id
     WHERE r.type = 'MONTHLY_ACTUAL'
), latest_master AS (
    SELECT DISTINCT ON (m.request_id) m.id AS master_id, m.request_id, m.created_at
      FROM master_version m ORDER BY m.request_id, m.seq DESC
), picked AS (
    SELECT 'master'::text AS kind, req.year, req.month, req.request_id, ms.organization_id, ms.submission_id, lm.created_at AS loaded_at
      FROM latest_master lm
      JOIN req ON req.request_id = lm.request_id
      JOIN master_source ms ON ms.master_id = lm.master_id
    UNION ALL
    SELECT 'provisional'::text, req.year, req.month, req.request_id, t.organization_id, la.id, la.reviewed_at
      FROM req
      JOIN request_target t ON t.request_id = req.request_id AND t.selected AND NOT t.excluded
      JOIN LATERAL (SELECT s.id, s.reviewed_at FROM submission s
                     WHERE s.request_id = t.request_id AND s.organization_id = t.organization_id AND s.status = 'APPROVED'
                     ORDER BY s.approved_seq DESC LIMIT 1) la ON true
     WHERE NOT EXISTS (SELECT 1 FROM master_version m WHERE m.request_id = req.request_id)
)
SELECT pk.kind || '-' || pk.year || '-' || lpad(pk.month::text, 2, '0') AS version,
       pk.kind, pk.year, pk.month, pk.request_id, pk.organization_id, pk.submission_id, pk.loaded_at,
       rt.confirmed_plan_submission_id AS plan_submission_id
  FROM picked pk
  LEFT JOIN request_target rt ON rt.request_id = pk.request_id AND rt.organization_id = pk.organization_id;

-- ---------------------------------------------------------------------------
-- dim_org: 코드·이름만(계층 없음)
CREATE OR REPLACE VIEW report.dim_org AS
SELECT o.code AS org_code, o.name AS org_name FROM organization o WHERE o.active;

-- dim_version: 한 달에 하나. is_latest = 가장 최근 달
CREATE OR REPLACE VIEW report.dim_version AS
SELECT v.version, v.kind,
       CASE v.kind WHEN 'master' THEN '확정 ' ELSE '잠정 ' END || v.year || '년 ' || v.month || '월' AS label,
       v.year, v.month,
       to_char(v.loaded_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SS') AS loaded_at,
       CASE v.kind WHEN 'master' THEN 'master' ELSE 'approved' END AS source,
       NULL::integer AS row_count,
       (row_number() OVER (ORDER BY v.year DESC, v.month DESC) = 1)::integer AS is_latest
  FROM (SELECT version, kind, year, month, max(loaded_at) AS loaded_at
          FROM report.report_source GROUP BY version, kind, year, month) v;

-- fact: 말단 항목만(합계·DERIVED 없음). ACTUAL·ESTIMATE 는 그 version 의 제출본, PLAN 은 확정 연간 계획 제출본
CREATE OR REPLACE VIEW report.fact AS
SELECT s.version, o.code AS org_code, o.name AS org_name,
       CASE i.kind WHEN 'REVENUE' THEN '매출' ELSE '영업비용' END AS kind,
       p.cat1, p.cat2, p.item, fl.description AS detail, fl.team_code AS team, NULL::text AS manager,
       sub.original_filename || ' / ' || fl.source_sheet || '!' || fv.source_cell AS src,
       fv.year, fv.month, fv.scenario, fv.amount AS amount_won
  FROM report.report_source s
  JOIN organization o ON o.id = s.organization_id
  JOIN submission sub ON sub.id = s.submission_id
  JOIN financial_line fl ON fl.submission_id = s.submission_id
  JOIN financial_value fv ON fv.line_id = fl.id AND fv.year = s.year AND fv.scenario IN ('ACTUAL', 'ESTIMATE')
  JOIN report_item i ON i.id = fl.report_item_id AND i.kind IN ('REVENUE', 'COST')
  JOIN report.report_item_path p ON p.leaf_id = i.id
UNION ALL
SELECT s.version, o.code, o.name,
       CASE i.kind WHEN 'REVENUE' THEN '매출' ELSE '영업비용' END,
       p.cat1, p.cat2, p.item, fl.description, fl.team_code, NULL::text,
       sub.original_filename || ' / ' || fl.source_sheet || '!' || fv.source_cell,
       fv.year, fv.month, fv.scenario, fv.amount
  FROM report.report_source s
  JOIN organization o ON o.id = s.organization_id
  JOIN submission sub ON sub.id = s.plan_submission_id
  JOIN financial_line fl ON fl.submission_id = s.plan_submission_id
  JOIN financial_value fv ON fv.line_id = fl.id AND fv.year = s.year AND fv.scenario = 'PLAN'
  JOIN report_item i ON i.id = fl.report_item_id AND i.kind IN ('REVENUE', 'COST')
  JOIN report.report_item_path p ON p.leaf_id = i.id;

-- v_op: 조직·월·구분별 매출·영업비용·영업이익(= 매출 − 영업비용). fact 에서 계산한다(홈 HOME-03~06 과 같은 규칙)
CREATE OR REPLACE VIEW report.v_op AS
SELECT version, org_code, org_name, year, month, scenario,
       SUM(CASE WHEN kind = '매출' THEN amount_won ELSE 0 END) AS revenue_won,
       SUM(CASE WHEN kind = '영업비용' THEN amount_won ELSE 0 END) AS cost_won,
       SUM(CASE WHEN kind = '매출' THEN amount_won ELSE -amount_won END) AS op_won
  FROM report.fact
 GROUP BY version, org_code, org_name, year, month, scenario;

-- gap: 임계값을 넘은 비교(gap_comparison.exceeded). 상태는 백엔드 탭 규칙(ReviewTabs)과 같다. 임계값 숫자는 내보내지 않는다.
--   gap_id = 'RI-<review_item.id>-<basis>'. rate_percent 는 백엔드가 이미 % 로 저장한다.
CREATE OR REPLACE VIEW report.gap AS
SELECT s.version, 'RI-' || ri.id || '-' || gc.basis AS gap_id, 'ITEM_GAP'::text AS gap_type,
       o.code AS org_code, o.name AS org_name, p.item, ri.year, ri.month, ri.scenario, gc.basis,
       gc.baseline_amount AS previous_won, gc.current_amount AS current_won, gc.gap_amount AS diff_won,
       round(gc.gap_rate, 2)::text AS rate_percent,
       CASE WHEN ri.workflow_state = 'EXCLUDED' THEN 'EXCLUDED'
            WHEN cur.verdict = 'ACCEPTED' THEN 'ACCEPTED'
            WHEN cur.requested_at IS NOT NULL AND cur.verdict = 'PENDING' AND (cur.answer IS NULL OR btrim(cur.answer) = '') THEN 'IN_PROGRESS'   -- isAnswered(): 답이 비어 있지 않음
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

-- explanation: 소명 회차별. 원문(answer)은 수락된 것만. 연결 키는 review_item(gap_key = 'RI-<id>', gap_id 는 gap 행마다 같은 키로 이어진다)
CREATE OR REPLACE VIEW report.explanation AS
SELECT s.version, 'EX-' || re.id AS explanation_id, 'RI-' || ri.id AS gap_key, 'RI-' || ri.id || '-' || gc.basis AS gap_id,
       o.code AS org_code, o.name AS org_name,
       CASE i.kind WHEN 'REVENUE' THEN '매출' ELSE '영업비용' END AS kind, p.item, ri.year, ri.month, ri.scenario,
       'LINKED'::text AS link_type,
       CASE WHEN re.verdict = 'ACCEPTED' THEN '수락' ELSE '검토중' END AS status,
       re.verdict, re.round AS rounds,
       CASE WHEN re.verdict = 'ACCEPTED' THEN re.answer END AS answer,
       NULL::text AS src
  FROM report.report_source s
  JOIN review_item ri ON ri.submission_id = s.submission_id AND ri.active AND ri.year = s.year
  JOIN gap_comparison gc ON gc.review_item_id = ri.id AND gc.exceeded
  JOIN review_explanation re ON re.review_item_id = ri.id
  JOIN report_item i ON i.id = ri.report_item_id AND i.kind IN ('REVENUE', 'COST')
  JOIN report.report_item_path p ON p.leaf_id = i.id
  JOIN organization o ON o.id = s.organization_id;

-- ---------------------------------------------------------------------------
-- 읽기 전용 역할(예시, 운영 측 승인 필요): 보고서용 뷰 6개에만 SELECT. 내부 보조 뷰와 원본 테이블은 열지 않는다
-- (뷰는 만든 사람의 권한으로 아래 테이블을 읽으므로, 읽기 전용 역할에는 원본 테이블 권한이 필요 없다).
--   CREATE ROLE report_ro LOGIN PASSWORD '<운영에서 지정>';
--   GRANT USAGE ON SCHEMA report TO report_ro;
--   GRANT SELECT ON report.fact, report.v_op, report.gap, report.explanation, report.dim_version, report.dim_org TO report_ro;
--   ALTER ROLE report_ro SET default_transaction_read_only = on;
--   ALTER ROLE report_ro SET statement_timeout = '20s';
