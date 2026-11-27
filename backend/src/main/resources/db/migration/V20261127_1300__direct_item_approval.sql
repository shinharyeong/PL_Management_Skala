ALTER TABLE review_item DROP CONSTRAINT review_item_workflow_state_check;
ALTER TABLE review_item ADD CONSTRAINT review_item_workflow_state_check
CHECK (workflow_state IN ('NEEDS_REVIEW','SELECTED','REQUESTED','APPROVED','EXCLUDED'));
ALTER TABLE review_workflow_event DROP CONSTRAINT review_workflow_event_action_check;
ALTER TABLE review_workflow_event ADD CONSTRAINT review_workflow_event_action_check
CHECK (action IN ('SELECT','EXCLUDE','APPROVE','REQUEST_EXPLANATION'));
-- Preserve old event records, while existing excluded items become direct approvals.
UPDATE review_item SET workflow_state='APPROVED',updated_at=now() WHERE workflow_state='EXCLUDED';
