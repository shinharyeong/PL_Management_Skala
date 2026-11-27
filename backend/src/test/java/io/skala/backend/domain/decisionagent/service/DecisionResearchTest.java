package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static io.skala.backend.domain.decisionagent.dto.DecisionResearchDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.report.service.ReportAiClient;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** DB·모델 없이 근거 범위와 미검증 후보의 숫자 경계를 검증한다. */
class DecisionResearchTest {
    private final DecisionAnalysisStore analyses = mock(DecisionAnalysisStore.class);
    private final DecisionResearchStore research = mock(DecisionResearchStore.class);
    private final ReportAiClient ai = mock(ReportAiClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);
    private final DecisionEvidenceTool tool = new DecisionEvidenceTool(clock);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DecisionDiscoveryService service = new DecisionDiscoveryService(analyses, research, tool, new DecisionScenarioCalculator(), ai, mapper);
    private final AuthenticatedUser planner = new AuthenticatedUser(10L, UserRole.PLANNER, null);

    /** 목적: 읽기 토큰은 분석·revision·참조에 한정되며 폐기 후 거부한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 권한 생성·삭제. */
    @Test
    void capabilityCannotReadAnotherAnalysisRevisionOrReference() {
        String token = tool.grant("1", 2, List.of(evidence()));
        assertThat(tool.read(token, new ReadEvidenceRequest("1", 2, List.of("explanation-2"))).items()).hasSize(1);
        for (ReadEvidenceRequest request : List.of(new ReadEvidenceRequest("9", 2, List.of("explanation-2")),
                new ReadEvidenceRequest("1", 3, List.of("explanation-2")), new ReadEvidenceRequest("1", 2, List.of("foreign")),
                new ReadEvidenceRequest("1", 2, List.of("explanation-2", "explanation-2")))) {
            assertThatThrownBy(() -> tool.read(token, request)).isInstanceOf(BusinessException.class);
        }
        tool.revoke(token);
        assertThatThrownBy(() -> tool.read(token, new ReadEvidenceRequest("1", 2, List.of("explanation-2")))).isInstanceOf(BusinessException.class);
    }

    /** 목적: 만료된 토큰은 남아 있어도 읽지 못한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 시계 변경. */
    @Test
    void expiredCapabilityIsDenied() {
        Clock advancing = mock(Clock.class);
        when(advancing.instant()).thenReturn(clock.instant());
        DecisionEvidenceTool expiring = new DecisionEvidenceTool(advancing);
        String token = expiring.grant("1", 2, List.of(evidence()));
        when(advancing.instant()).thenReturn(clock.instant().plusSeconds(181));
        assertThatThrownBy(() -> expiring.read(token, new ReadEvidenceRequest("1", 2, List.of("explanation-2")))).isInstanceOf(BusinessException.class);
    }

    /** 목적: 모델의 임의 금액·타 조직·가짜 인용을 차단한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: UUID 생성. */
    @Test
    void hypothesisMustRemainUnquantifiedAndCiteItsOwnOrganization() {
        Hypothesis good = hypothesis("1", null, "납품 일정 확인 필요");
        Discovery accepted = service.validated(discovery(good), List.of(evidence()));
        assertThat(accepted.hypotheses().getFirst().id()).startsWith("hyp-");
        assertThat(accepted.hypotheses().getFirst().amountWon()).isNull();
        for (Hypothesis bad : List.of(hypothesis("2", null, "납품 일정 확인 필요"), hypothesis("1", "3500000000", "납품 일정 확인 필요"),
                hypothesis("1", null, "계약 변경 완료"))) {
            assertThatThrownBy(() -> service.validated(discovery(bad), List.of(evidence()))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 목적: 설명 서비스가 실패해도 실제 근거 기반 질문만 저장한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 저장·AI 호출. */
    @Test
    void discoveryFailureKeepsFixedSourcesAndNeverInventsEffects() {
        Analysis analysis = analysis();
        Research captured = new Research("1", 2, OffsetDateTime.now(clock), List.of(evidence()), null);
        when(analyses.get(10L, 1L, true)).thenReturn(analysis);
        when(research.capture(analysis)).thenReturn(captured);
        when(ai.post(eq("/v1/decision-agent/discover"), any(), eq(10L))).thenThrow(new IllegalStateException("offline"));
        when(research.save(eq(captured), eq(2), any())).thenAnswer(invocation -> new Research("1", 2, captured.capturedAt(), captured.evidence(), invocation.getArgument(2)));
        Research output = service.discover(planner, 1L, new DiscoverRequest(2));
        assertThat(output.discovery().mode()).isEqualTo("TEMPLATE");
        assertThat(output.discovery().hypotheses()).hasSize(1).allSatisfy(item -> {
            assertThat(item.amountWon()).isNull(); assertThat(item.recognitionMonth()).isNull();
            assertThat(item.needsConfirmation()).isTrue(); assertThat(item.evidenceRefs()).containsExactly("explanation-2");
        });
        verify(analyses, never()).update(anyLong(), anyInt(), any());
    }

    /** 목적: 오래된 분석과 조직 담당자는 후보 발굴 전에 거부한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 읽기. */
    @Test
    void authorizationAndRevisionPrecedeEvidenceAndAi() {
        assertThatThrownBy(() -> service.discover(new AuthenticatedUser(11L, UserRole.ORG_MANAGER, 1L), 1L, new DiscoverRequest(2)))
                .isInstanceOf(BusinessException.class);
        when(analyses.get(10L, 1L, true)).thenReturn(analysis());
        assertThatThrownBy(() -> service.discover(planner, 1L, new DiscoverRequest(1))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(research, ai);
    }

    /** 목적: 기준 생성 후 원천 수치가 바뀌면 조사에 섞지 않는다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 객체 생성만. */
    @Test
    void recaptureRejectsChangedUnderlyingFinancialAmounts() {
        DecisionResearchStore repository = new DecisionResearchStore(mock(JdbcTemplate.class), mapper, mock(DecisionBaselineLoader.class), clock);
        Snapshot fixed = analysis().snapshot();
        Snapshot changed = new Snapshot("3", "월간", 2026, 11, fixed.asOf(), List.of("4"),
                List.of(new Organization("1", "A", "A", "1100", "80", "1020")), "1020");
        repository.assertSameAmounts(fixed, fixed);
        assertThatThrownBy(() -> repository.assertSameAmounts(fixed, changed)).isInstanceOf(BusinessException.class);
    }

    /** 목적: 실제 근거 모양의 합성 입력을 제공한다. Args: 없음. Returns: 메모리 근거. Raises: 없음. Side Effects: 없음. */
    private Evidence evidence() { return new Evidence("explanation-2", "APPROVED_EXPLANATION", "1", "A 소명", "납품 일정 확인 필요", Map.of()); }

    /** 목적: 가설 경계 테스트 입력을 구성한다. Args: org 조직, amount 미정 또는 오염 금액, quote 인용. Returns: 검토 가설. Raises: 없음. Side Effects: 없음. */
    private Hypothesis hypothesis(String org, String amount, String quote) {
        return new Hypothesis("model-id", org, "일정 검토", "담당자 확인이 필요합니다.", List.of("explanation-2"), amount, null,
                Category.REVENUE, List.of("연내 반영 가능 여부를 확인해 주세요."), true, quote);
    }

    /** 목적: 최소 모델 응답을 구성한다. Args: hypothesis 검토 가설. Returns: 응답. Raises: 없음. Side Effects: 없음. */
    private Discovery discovery(Hypothesis hypothesis) {
        return new Discovery("MODEL", new GoalProposal("1050", 2026, List.of(), true, List.of()), List.of(hypothesis), List.of(), List.of());
    }

    /** 목적: DB를 사용하지 않는 고정 분석을 제공한다. Args: 없음. Returns: revision2 분석. Raises: 없음. Side Effects: 없음. */
    private Analysis analysis() {
        var now = OffsetDateTime.now(clock);
        Snapshot snapshot = new Snapshot("3", "월간", 2026, 11, now, List.of("4"),
                List.of(new Organization("1", "A", "A", "1000", "80", "920")), "920");
        return new Analysis("1", "목표", "목표 검토", 2, "DRAFT", now, now, "1050", List.of(), snapshot, List.of(), null, null);
    }
}
