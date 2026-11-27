package io.skala.backend.domain.decisionagent.controller;

import static io.skala.backend.domain.decisionagent.dto.DecisionResearchDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.decisionagent.service.DecisionAnalysisStore;
import io.skala.backend.domain.decisionagent.service.DecisionDiscoveryService;
import io.skala.backend.domain.decisionagent.service.DecisionEvidenceTool;
import io.skala.backend.domain.decisionagent.service.DecisionResearchStore;
import io.skala.backend.domain.decisionagent.service.DecisionScenarioCalculator;
import io.skala.backend.domain.report.service.ReportAiClient;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 목표 해석의 실제 MVC 입력 검증과 서비스 연결을 DB·네트워크 없이 검사한다. */
class DecisionResearchControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DecisionAnalysisStore analyses = mock(DecisionAnalysisStore.class);
    private final DecisionResearchStore research = mock(DecisionResearchStore.class);
    private final DecisionEvidenceTool evidence = mock(DecisionEvidenceTool.class);
    private final ReportAiClient ai = mock(ReportAiClient.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final IdempotentCommandExecutor idempotency = mock(IdempotentCommandExecutor.class);
    private final AuthenticatedUser planner = new AuthenticatedUser(7L, UserRole.PLANNER, null);
    private final GoalRequest request = new GoalRequest("합성 목표 천억 원을 검토해 주세요.", 2026);
    private DecisionResearchController controller;
    private MockMvc mvc;

    /** 목적: 실제 목표 해석 서비스와 HTTP 입력 검증을 메모리 대역으로 준비한다. Args: 없음. Returns: 없음. Raises: MVC 구성 오류. Side Effects: mock·메모리 초기화만. */
    @BeforeEach
    void prepare() {
        var service = new DecisionDiscoveryService(analyses, research, evidence, new DecisionScenarioCalculator(), ai, mapper);
        controller = new DecisionResearchController(service, evidence, ai, currentUser, idempotency);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(currentUser.get()).thenReturn(planner);
        when(ai.post("/v1/decision-agent/interpret-goal", request, planner.id())).thenReturn(mapper.valueToTree(
                new GoalResponse("MODEL", new GoalProposal("100000000000", 2026, List.of(), true, List.of()))));
    }

    /** 목적: 멱등 헤더 없이 확인용 제안을 반환하고 모든 저장소·멱등 실행을 사용하지 않는다. Args: 없음. Returns: 없음. Raises: 검증/모의 HTTP 오류. Side Effects: 메모리 MVC와 mock AI 호출만. */
    @Test
    void proposesWithoutIdempotencyHeaderOrAnyPersistence() throws Exception {
        mvc.perform(post("/api/v1/decision-agent/goal-proposals").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("MODEL"))
                .andExpect(jsonPath("$.goalProposal.targetProfitWon").value("100000000000"))
                .andExpect(jsonPath("$.goalProposal.needsConfirmation").value(true));
        verify(ai).post("/v1/decision-agent/interpret-goal", request, planner.id());
        verifyNoInteractions(idempotency, analyses, research, evidence);
    }

    /** 목적: 기존 클라이언트의 멱등 헤더를 무시하며 같은 키도 DB 재생 기록을 만들지 않는다. Args: 없음. Returns: 없음. Raises: 검증/모의 HTTP 오류. Side Effects: 메모리 MVC와 mock AI 호출만. */
    @Test
    void ignoresLegacyIdempotencyHeaderWithoutReplayingOrSaving() throws Exception {
        for (int index = 0; index < 2; index++) {
            mvc.perform(post("/api/v1/decision-agent/goal-proposals").header("Idempotency-Key", "legacy-goal-key")
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                    .andExpect(status().isOk()).andExpect(header().doesNotExist("Idempotency-Replayed"));
        }
        verify(ai, times(2)).post("/v1/decision-agent/interpret-goal", request, planner.id());
        verifyNoInteractions(idempotency, analyses, research, evidence);
    }

    /** 목적: 잘못된 원문·연도는 AI나 멱등 저장 호출 전에 HTTP 입력 검증으로 거부한다. Args: 없음. Returns: 없음. Raises: 검증/모의 HTTP 오류. Side Effects: 메모리 MVC만. */
    @Test
    void validatesPromptAndYearBeforeCallingAiOrPersistence() throws Exception {
        for (Map<String, Object> body : List.of(Map.<String, Object>of("prompt", " ", "year", 2026),
                Map.<String, Object>of("prompt", "합성 요청", "year", 1999), Map.<String, Object>of("prompt", "합성 요청", "year", 10000))) {
            mvc.perform(post("/api/v1/decision-agent/goal-proposals").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(ai, idempotency, analyses, research, evidence);
    }

    /** 목적: 조직 담당자는 읽기 전용 해석이어도 기획자 기능을 사용할 수 없다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 인증 조회만. */
    @Test
    void rejectsNonPlannerBeforeAiOrPersistence() {
        when(currentUser.get()).thenReturn(new AuthenticatedUser(8L, UserRole.ORG_MANAGER, 1L));
        assertThatThrownBy(() -> controller.goal(request)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(ai, idempotency, analyses, research, evidence);
    }

    /** 목적: 인증되지 않은 호출은 목표 해석이나 저장 경로에 도달하지 못한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 인증 조회만. */
    @Test
    void requiresCurrentAuthenticatedUserBeforeAiOrPersistence() {
        when(currentUser.get()).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));
        assertThatThrownBy(() -> controller.goal(request)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(ai, idempotency, analyses, research, evidence);
    }

    /** 목적: AI 실패 시 미확인 대안을 반환하고 오류 처리에서도 DB 기록을 만들지 않는다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock AI 호출만. */
    @Test
    void returnsUnconfirmedFallbackWithoutPersistenceWhenAiFails() {
        when(ai.post("/v1/decision-agent/interpret-goal", request, planner.id())).thenThrow(new IllegalStateException("합성 연결 실패"));
        GoalResponse response = controller.goal(request);
        assertThat(response.mode()).isEqualTo("TEMPLATE");
        assertThat(response.goalProposal().targetProfitWon()).isNull();
        assertThat(response.goalProposal().needsConfirmation()).isTrue();
        verifyNoInteractions(idempotency, analyses, research, evidence);
    }
}
