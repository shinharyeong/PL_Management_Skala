-- Preserve original financial values and explanation history; scope only review records.
ALTER TABLE submission ADD COLUMN review_baseline_submission_id bigint REFERENCES submission(id);
ALTER TABLE submission ADD COLUMN review_policy_version varchar(80);
ALTER TABLE gap_threshold DROP CONSTRAINT gap_threshold_comparison_check;
ALTER TABLE gap_threshold ADD CONSTRAINT gap_threshold_comparison_check CHECK
    (comparison IN ('ACTUAL_VS_PLAN', 'ACTUAL_VS_PREVIOUS', 'ESTIMATE_VS_PREVIOUS', 'ESTIMATE_VS_PLAN'));
-- Carry the configured actual threshold into the new actual/previous comparison.
INSERT INTO gap_threshold(comparison, percent, amount, applied_by, applied_at, percent_enabled, amount_enabled)
SELECT 'ACTUAL_VS_PREVIOUS', percent, amount, applied_by, applied_at, percent_enabled, amount_enabled
FROM gap_threshold WHERE comparison='ACTUAL_VS_PLAN' ORDER BY id DESC LIMIT 1;

UPDATE submission s SET review_baseline_submission_id = COALESCE(
    (SELECT p.id FROM submission p WHERE p.request_id=s.request_id AND p.organization_id=s.organization_id
        AND p.seq<s.seq AND p.status<>'DRAFT' ORDER BY p.seq DESC LIMIT 1),
    CASE WHEN r.month=1 THEN (SELECT t.confirmed_plan_submission_id FROM request_target t
        WHERE t.request_id=r.id AND t.organization_id=s.organization_id)
    ELSE (SELECT p.id FROM submission p JOIN submission_request pr ON pr.id=p.request_id
        WHERE pr.annual_plan_id=r.annual_plan_id AND pr.month=r.month-1 AND pr.type='MONTHLY_ACTUAL'
        AND p.organization_id=s.organization_id AND p.status='APPROVED'
        ORDER BY p.approved_seq DESC LIMIT 1) END), review_policy_version='monthly-comparison/2.0'
FROM submission_request r WHERE r.id=s.request_id AND r.type='MONTHLY_ACTUAL' AND s.status<>'DRAFT';

-- Keep the original derived data for audit/recovery before migrating the policy.
CREATE TABLE monthly_review_policy_archive (
    submission_id bigint PRIMARY KEY REFERENCES submission(id),
    previous_review jsonb NOT NULL,
    migrated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO monthly_review_policy_archive(submission_id,previous_review)
SELECT s.id,jsonb_build_object('items',COALESCE((SELECT jsonb_agg(to_jsonb(i)) FROM review_item i WHERE i.submission_id=s.id),'[]'::jsonb),
    'gaps',COALESCE((SELECT jsonb_agg(to_jsonb(g)) FROM gap_comparison g JOIN review_item i ON i.id=g.review_item_id WHERE i.submission_id=s.id),'[]'::jsonb))
FROM submission s JOIN submission_request r ON r.id=s.request_id
WHERE r.type='MONTHLY_ACTUAL' AND EXISTS (SELECT 1 FROM review_item i WHERE i.submission_id=s.id);

UPDATE review_item i SET active=false, gap_exceeded=false, updated_at=now()
FROM submission s, submission_request r WHERE s.id=i.submission_id AND r.id=s.request_id
    AND r.type='MONTHLY_ACTUAL' AND NOT
        ((i.scenario='ACTUAL' AND i.month=r.month) OR (i.scenario='ESTIMATE' AND i.month>r.month));

-- Rebuild derived Gap records only. The source file, financial values and explanations remain untouched.
CREATE TEMP TABLE monthly_policy_gaps ON COMMIT DROP AS
SELECT i.id AS review_item_id,c.basis,c.source_id AS baseline_submission_id,c.source_scenario AS baseline_scenario,
    b.amount AS baseline_amount,i.current_amount,th.id AS threshold_id,
    ((th.percent_enabled OR th.amount_enabled)
      AND (NOT th.amount_enabled OR abs(i.current_amount-b.amount)>=th.amount)
      AND (NOT th.percent_enabled OR CASE WHEN b.amount=0 THEN i.current_amount<>0
           ELSE abs(i.current_amount-b.amount)*100>=abs(b.amount)*th.percent END)) AS exceeded
FROM review_item i JOIN submission s ON s.id=i.submission_id
JOIN submission_request r ON r.id=s.request_id AND r.type='MONTHLY_ACTUAL'
LEFT JOIN submission prev ON prev.id=s.review_baseline_submission_id
JOIN request_target target ON target.request_id=r.id AND target.organization_id=s.organization_id
CROSS JOIN LATERAL (
    SELECT 'PLAN'::varchar AS basis,target.confirmed_plan_submission_id AS source_id,'PLAN'::varchar AS source_scenario,'ACTUAL_VS_PLAN' AS comparison
    WHERE i.scenario='ACTUAL' AND (prev.id IS NULL OR prev.request_id<>r.id)
    UNION ALL
    SELECT 'PREVIOUS',prev.id,CASE WHEN prev.request_id=r.id THEN i.scenario ELSE 'ESTIMATE' END,
        CASE WHEN i.scenario='ACTUAL' THEN 'ACTUAL_VS_PREVIOUS' ELSE 'ESTIMATE_VS_PREVIOUS' END
    WHERE prev.id IS NOT NULL AND prev.id IS DISTINCT FROM target.confirmed_plan_submission_id
) c
JOIN LATERAL (SELECT sum(v.amount) AS amount FROM financial_line l JOIN financial_value v ON v.line_id=l.id
    WHERE l.submission_id=c.source_id AND l.report_item_id=i.report_item_id
        AND v.year=i.year AND v.month=i.month AND v.scenario=c.source_scenario) b ON b.amount IS NOT NULL
JOIN LATERAL (
    SELECT threshold.* FROM gap_threshold threshold WHERE threshold.id=COALESCE(
        (SELECT old.threshold_id FROM gap_comparison old WHERE old.review_item_id=i.id AND old.basis=c.basis LIMIT 1),
        (SELECT t.id FROM gap_threshold t WHERE t.comparison=c.comparison AND t.applied_at<=s.submitted_at ORDER BY t.id DESC LIMIT 1),
        CASE WHEN c.comparison='ACTUAL_VS_PREVIOUS' THEN
            COALESCE((SELECT old.threshold_id FROM gap_comparison old WHERE old.review_item_id=i.id AND old.basis='PLAN' LIMIT 1),
                (SELECT t.id FROM gap_threshold t WHERE t.comparison='ACTUAL_VS_PLAN' AND t.applied_at<=s.submitted_at ORDER BY t.id DESC LIMIT 1)) END)
) th ON true
WHERE i.active AND s.status<>'DRAFT' AND i.current_amount IS NOT NULL;

DELETE FROM gap_comparison g USING review_item i,submission s,submission_request r
WHERE g.review_item_id=i.id AND s.id=i.submission_id AND r.id=s.request_id
    AND r.type='MONTHLY_ACTUAL' AND s.status<>'DRAFT';
INSERT INTO gap_comparison(review_item_id,basis,baseline_submission_id,baseline_scenario,baseline_amount,current_amount,gap_amount,gap_rate,threshold_id,exceeded)
SELECT review_item_id,basis,baseline_submission_id,baseline_scenario,baseline_amount,current_amount,current_amount-baseline_amount,
    CASE WHEN baseline_amount=0 THEN NULL ELSE round((current_amount-baseline_amount)*100/abs(baseline_amount),8) END,threshold_id,exceeded
FROM monthly_policy_gaps;
UPDATE review_item i SET gap_exceeded=EXISTS(SELECT 1 FROM monthly_policy_gaps g WHERE g.review_item_id=i.id AND g.exceeded),
    review_policy_version='monthly-comparison/2.0'
FROM submission s,submission_request r WHERE i.submission_id=s.id AND r.id=s.request_id AND r.type='MONTHLY_ACTUAL' AND i.active AND s.status<>'DRAFT';
