package io.skala.backend.domain.decisionworkflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.decisionworkflow.controller.DecisionWorkflowController;
import io.skala.backend.domain.decisionworkflow.dto.DecisionWorkflowDtos.*;
import io.skala.backend.domain.decisionworkflow.service.DecisionWorkflowService;
import io.skala.backend.domain.decisionworkflow.service.DecisionWorkflowStore;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** DB 접근 없이 조직 범위 SQL과 멱등 응답 재사용 전 권한 검사를 확인한다. */
class DecisionWorkflowAuthorizationTest {
    /** 목적: 조직 요청 조회가 자기 조직과 발행 상태로 제한됨을 검증한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 JDBC 호출. */
    @Test
    void organizationQueryNeverIncludesDraftOrOtherOrganization() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DecisionWorkflowStore store = new DecisionWorkflowStore(jdbc, new ObjectMapper());
        assertThatThrownBy(() -> store.get(new AuthenticatedUser(20L, UserRole.ORG_MANAGER, 3L), 7L, false)).isInstanceOf(BusinessException.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("organization_id=? AND state_code<>'DRAFT'", "body @> '{\"events\":[{\"action\":\"ISSUED\"}]}'::jsonb", "AND id=?");
        assertThat(args.getValue()).containsExactly(3L, 7L);
    }

    /** 목적: 다른 기획자의 요청을 조회할 수 없도록 소유자 SQL을 검증한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 JDBC 호출. */
    @Test
    void plannerQueryAlwaysIncludesOwnerId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DecisionWorkflowStore store = new DecisionWorkflowStore(jdbc, new ObjectMapper());
        store.list(new AuthenticatedUser(10L, UserRole.PLANNER, null), 5L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("owner_id=? AND analysis_id=?");
        assertThat(args.getValue()).containsExactly(10L, 5L);
    }

    /** 목적: 조직 이동/권한 변경 후 이전 멱등키로 자료를 다시 받을 수 없음을 검증한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 권한 조회. */
    @Test
    void currentOrganizationIsCheckedBeforeIdempotencyReplay() {
        DecisionWorkflowService service = mock(DecisionWorkflowService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        IdempotentCommandExecutor idempotency = mock(IdempotentCommandExecutor.class);
        AuthenticatedUser movedUser = new AuthenticatedUser(20L, UserRole.ORG_MANAGER, 9L);
        when(currentUser.get()).thenReturn(movedUser);
        when(service.get(movedUser, 7L)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        DecisionWorkflowController controller = new DecisionWorkflowController(service, currentUser, idempotency);
        assertThatThrownBy(() -> controller.reply(7L, "old-request-key", new ReplyRequest(1, Feasibility.NOT_FEASIBLE, null, "회신", "ref")))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(idempotency);
    }
}
