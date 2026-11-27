package io.skala.backend.domain.request.service;

/** 관리 화면과 알림이 공유하는 요청·조직별 현재 처리 상태. 인자는 내부 SQL 열 이름만 사용한다. */
public final class WorkflowStatusSql {
    private WorkflowStatusSql() {}

    public static String forTarget(String requestId, String organizationId) {
        return """
                (select case
                  when not wt.selected or wt.excluded then 'REQUEST_NEEDED'
                  when ws.status = 'APPROVED' then 'APPROVED'
                  when ws.id is null or ws.status = 'REJECTED' then 'WAITING'
                  when exists (select 1 from review_item ri join review_explanation e on e.review_item_id=ri.id
                    and e.round=ri.current_round where ri.submission_id=ws.id and ri.active
                    and ri.workflow_state not in ('EXCLUDED','APPROVED') and e.request_note is not null and e.answer is not null and e.verdict='PENDING') then 'REVIEW_NEEDED'
                  when exists (select 1 from review_item ri join review_explanation e on e.review_item_id=ri.id
                    and e.round=ri.current_round where ri.submission_id=ws.id and ri.active
                    and ri.workflow_state not in ('EXCLUDED','APPROVED') and e.request_note is not null
                    and e.answer is null and e.verdict='PENDING') then 'WAITING'
                  else 'REVIEW_NEEDED' end
                 from request_target wt left join lateral (
                   select id,status from submission where request_id=wt.request_id and organization_id=wt.organization_id
                     and status <> 'DRAFT' order by seq desc limit 1) ws on true
                 where wt.request_id=%s and wt.organization_id=%s)
                """.formatted(requestId, organizationId);
    }
}
