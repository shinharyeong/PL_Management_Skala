package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** DB 대역으로 최신 마스터·원천 범위·변경 검사의 실패 경계를 확인한다. */
class DecisionReportBasisGuardTest {
    /** 목적: 요청한 마스터와 실제 최신 마스터가 다르면 반환하지 않는다.
     * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 메모리 SQL 대역만. */
    @Test void rejectsOldMasterEvenWithSameMonthLabel() {
        var guard=new DecisionReportBasisGuard(jdbc(4L,1L,101L,100L,"data"));
        assertThatThrownBy(() -> guard.check(snapshot())).isInstanceOf(DecisionAssistantTools.ToolFailure.class);
    }

    /** 목적: 다른 제출본·조직 원천이 같은 월에 섞이면 거부한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 대역만. */
    @Test void rejectsDifferentSourceOrganizationAndSubmission() {
        assertThatThrownBy(() -> new DecisionReportBasisGuard(jdbc(3L,2L,101L,100L,"data")).check(snapshot()))
                .isInstanceOf(DecisionAssistantTools.ToolFailure.class);
        assertThatThrownBy(() -> new DecisionReportBasisGuard(jdbc(3L,1L,102L,100L,"data")).check(snapshot()))
                .isInstanceOf(DecisionAssistantTools.ToolFailure.class);
    }

    /** 목적: 비교 계획·재무·소명 fingerprint 변화가 결과 채택을 막는지 확인한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 대역만. */
    @Test void detectsPlanAndContentsChangeWhilePreservingVersionLabel() {
        var guard=new DecisionReportBasisGuard(jdbc(3L,1L,101L,100L,"before"));
        var before=guard.check(snapshot());
        var changedPlan=new DecisionReportBasisGuard(jdbc(3L,1L,101L,99L,"before")).check(snapshot());
        var changedData=new DecisionReportBasisGuard(jdbc(3L,1L,101L,100L,"after")).check(snapshot());
        assertThat(before.version()).isEqualTo("master-2026-11");
        assertThatThrownBy(() -> guard.requireSame(before,changedPlan)).isInstanceOf(DecisionAssistantTools.ToolFailure.class);
        assertThatThrownBy(() -> guard.requireSame(before,changedData)).isInstanceOf(DecisionAssistantTools.ToolFailure.class);
        guard.requireSame(before,before);
    }

    /** 목적: SQL을 실행하지 않고 mapper가 기대하는 행을 제공한다.
     * Args: masterId 최신 마스터, orgId 조직, submissionId 제출본, planId 계획, contents 원천 해시.
     * Returns: JdbcTemplate 대역. Raises: SQLException 대역 매핑 오류.
     * Side Effects: DB·파일 접근 없음. */
    private JdbcTemplate jdbc(long masterId,long orgId,long submissionId,long planId,String contents) {
        return mock(JdbcTemplate.class, invocation -> {
            String sql=invocation.getArguments().length>0 && invocation.getArgument(0) instanceof String text ? text : "";
            if (sql.contains("SELECT m.id")) return List.of(new long[]{masterId,10L});
            if (sql.contains("SELECT request_id")) {
                ResultSet rs=mock(ResultSet.class);
                when(rs.getLong(1)).thenReturn(10L); when(rs.getLong(2)).thenReturn(orgId);
                when(rs.getLong(3)).thenReturn(submissionId); when(rs.getObject(4)).thenReturn(planId);
                RowMapper<?> mapper=invocation.getArgument(1);
                return List.of(mapper.mapRow(rs,0));
            }
            if (sql.contains("WITH sources")) return contents;
            throw new AssertionError("예상하지 않은 DB 호출");
        });
    }

    /** 목적: 비교할 고정 원천을 만든다. Args: 없음. Returns: 합성 Snapshot.
     * Raises: 없음. Side Effects: 메모리만. */
    private Snapshot snapshot() {
        return new Snapshot("3","master_ver11.01",2026,11,OffsetDateTime.parse("2026-11-27T00:00:00Z"),
                List.of("101"),List.of(new Organization("1","A","사업부","10","5","5")),"5");
    }
}
