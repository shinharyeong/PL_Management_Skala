package io.skala.backend.domain.decisionworkflow;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static io.skala.backend.domain.decisionworkflow.dto.DecisionWorkflowDtos.*;
import static org.assertj.core.api.Assertions.*;

import io.skala.backend.domain.decisionworkflow.service.DecisionWorkflowPolicy;
import io.skala.backend.global.exception.BusinessException;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 실제 DB 없이 상태와 시나리오 승인 경계의 부적합 전이를 검증한다. */
class DecisionWorkflowPolicyTest {
    private final DecisionWorkflowPolicy policy = new DecisionWorkflowPolicy();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-26T00:00:00Z");

    /** 목적: 완료/취소 요청의 재진행과 오래된 revision을 차단한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void rejectsTerminalTransitionAndStaleRevision() {
        WorkItem item = WorkItem.builder().state(State.COMPLETED).build();
        assertThatThrownBy(() -> policy.state(item, State.APPROVED, State.IN_PROGRESS)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.revision(3, 2)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.futureDeadline(now, now)).isInstanceOf(BusinessException.class);
    }

    /** 목적: 후속 master가 다른 연도 또는 다른 조직이면 비교를 차단한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void rejectsDifferentYearAndOrganizationFollowup() {
        WorkItem item = WorkItem.builder().year(2026).baseMonth(11).organizationId("1").build();
        Snapshot nextYear = new Snapshot("2", "master", 2027, 12, now, List.of(), List.of(), "0");
        Snapshot foreignOrg = new Snapshot("3", "master", 2026, 12, now, List.of(),
                List.of(new Organization("2", "B", "다른 조직", "1", "0", "1")), "1");
        assertThatThrownBy(() -> policy.followupOrganization(item, nextYear)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.followupOrganization(item, foreignOrg)).isInstanceOf(BusinessException.class);
    }

    /** 목적: 조건부 가정 또는 계산에서 제외된 후보에 추진 승인을 기록하지 않는다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void excludedEffectCannotBeApproved() {
        Candidate candidate = new Candidate("c", "1", "과제", EffectType.NET_PROFIT, "10", null, "0", "0", 12,
                Category.HEADCOUNT, "", EvidenceStatus.ASSUMPTION, "근거", "ref");
        WorkItem item = WorkItem.builder().candidateId("c").reply(new Reply(Feasibility.FEASIBLE, candidate, "근거", "ref", "20", now)).build();
        Candidate accepted = policy.acceptedCandidate(item);
        Result excluded = new Result("100", "130", "30", "0", "0", "100", "100", "30", "30", "76.9",
                List.of(new Effect("c", "10", false, true, List.of("PROTECTED_CATEGORY"))), List.of());
        Analysis analysis = new Analysis("1", "분석", "목표", 1, "COMPLETED", now, now, "130", List.of(Category.HEADCOUNT),
                null, List.of(accepted), excluded, null);
        assertThatThrownBy(() -> policy.approvable(item, analysis)).isInstanceOf(BusinessException.class);
    }
}
