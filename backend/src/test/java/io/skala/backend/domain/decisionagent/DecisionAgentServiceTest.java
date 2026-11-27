package io.skala.backend.domain.decisionagent;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.skala.backend.domain.decisionagent.service.*;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 분석 수정 시 고정 기준 보존·경쟁 수정·권한 경계를 검증한다. */
class DecisionAgentServiceTest {
    private final DecisionAnalysisStore store = mock(DecisionAnalysisStore.class);
    private final DecisionBaselineLoader baselines = mock(DecisionBaselineLoader.class);
    private final DecisionNarrativeService narratives = mock(DecisionNarrativeService.class);
    private final DecisionAgentService service = new DecisionAgentService(store, baselines, new DecisionScenarioCalculator(), narratives,
            Clock.fixed(Instant.parse("2026-11-25T01:00:00Z"), ZoneOffset.UTC));
    private final AuthenticatedUser planner = new AuthenticatedUser(10L, UserRole.PLANNER, null);

    /** 목적: 후보 변경은 기준을 재조회하지 않고 기존 결과를 비운다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 저장소 호출. */
    @Test
    void replacingCandidatesKeepsSnapshotAndInvalidatesOldResult() {
        Analysis before = analysis();
        when(store.get(10L, 7L, true)).thenReturn(before);
        when(store.update(eq(10L), eq(3), any())).thenAnswer(invocation -> invocation.getArgument(2));
        Analysis after = service.candidates(planner, 7L, new CandidatesRequest(3, List.of()));
        assertThat(after.snapshot()).isSameAs(before.snapshot());
        assertThat(after.revision()).isEqualTo(4);
        assertThat(after.result()).isNull();
        assertThat(after.narrative()).isNull();
        assertThat(after.status()).isEqualTo("DRAFT");
        verifyNoInteractions(baselines, narratives);
    }

    /** 목적: 오래된 revision을 설명 호출 전에 거절한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 읽기. */
    @Test
    void rejectsStaleRevisionBeforeCalculationOrAi() {
        when(store.get(10L, 7L, true)).thenReturn(analysis());
        assertThatThrownBy(() -> service.run(planner, 7L, new RunRequest(2))).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("RESOURCE_CHANGED"));
        verifyNoInteractions(narratives, baselines);
        verify(store, never()).update(anyLong(), anyInt(), any());
    }

    /** 목적: 조직 담당자의 분석 접근을 저장소 전에 차단한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void rejectsOrganizationManagerAndScopesPlannerReadsByOwner() {
        assertThatThrownBy(() -> service.get(new AuthenticatedUser(20L, UserRole.ORG_MANAGER, 1L), 7L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getCode()).isEqualTo("FORBIDDEN"));
        verifyNoInteractions(store);
        when(store.get(10L, 7L, false)).thenReturn(analysis());
        service.get(planner, 7L);
        verify(store).get(10L, 7L, false);
    }

    /** 목적: 실행은 저장된 고정 전망을 사용한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 DB/AI 호출. */
    @Test
    void runUsesFrozenBaselineAndIncrementsRevision() {
        when(store.get(10L, 7L, true)).thenReturn(analysis());
        when(store.update(eq(10L), eq(3), any())).thenAnswer(invocation -> invocation.getArgument(2));
        Analysis result = service.run(planner, 7L, new RunRequest(3));
        assertThat(result.revision()).isEqualTo(4);
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.result().baselineProfitWon()).isEqualTo("92000000000");
        verifyNoInteractions(baselines);
    }

    /** 목적: 이전 계산 상태가 있는 분석을 제공한다. Args: 없음. Returns: 저장 분석. Raises: 없음. Side Effects: 없음. */
    private Analysis analysis() {
        Snapshot snapshot = DecisionScenarioCalculatorTest.snapshot();
        Result result = new DecisionScenarioCalculator().calculate(snapshot, "105000000000", List.of(), List.of());
        return new Analysis("7", "목표 검토", "목표 달성 방안", 3, "COMPLETED", OffsetDateTime.parse("2026-11-25T00:00:00Z"),
                OffsetDateTime.parse("2026-11-25T00:00:00Z"), "105000000000", List.of(), snapshot, List.of(), result,
                new Narrative("TEMPLATE", "이전 설명", List.of(), List.of(), List.of()));
    }
}
