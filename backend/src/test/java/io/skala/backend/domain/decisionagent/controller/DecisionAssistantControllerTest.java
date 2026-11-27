package io.skala.backend.domain.decisionagent.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.decisionagent.dto.DecisionAssistantDtos.ToolRequest;
import io.skala.backend.domain.decisionagent.service.DecisionAssistantGateway;
import io.skala.backend.domain.decisionagent.service.DecisionAssistantService;
import io.skala.backend.domain.report.service.ReportAiClient;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.security.CurrentUser;
import org.junit.jupiter.api.Test;

/** 내부 HTTP의 두 독립 인증 경계를 실제 DB 없이 검증한다. */
class DecisionAssistantControllerTest {
    /** 목적: 내부 공유 토큰 실패 시 capability와 업무 조회에 도달하지 않게 한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock만. */
    @Test
    void rejectsInvalidInternalTokenBeforeCapabilityLookup() {
        var gateway = mock(DecisionAssistantGateway.class); var ai = mock(ReportAiClient.class);
        var controller = new DecisionAssistantController(mock(DecisionAssistantService.class), gateway, ai, mock(CurrentUser.class));
        assertThatThrownBy(() -> controller.call("wrong", "capability", new ToolRequest("analysis_context", new ObjectMapper().createObjectNode())))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(gateway);
    }

    /** 목적: 내부 토큰이 맞아도 별도 실행 capability 검사에 위임한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock만. */
    @Test
    void passesIndependentCapabilityOnlyAfterInternalAuthentication() {
        var gateway = mock(DecisionAssistantGateway.class); var ai = mock(ReportAiClient.class);
        when(ai.acceptsToken("internal")).thenReturn(true);
        var controller = new DecisionAssistantController(mock(DecisionAssistantService.class), gateway, ai, mock(CurrentUser.class));
        var request = new ToolRequest("analysis_context", new ObjectMapper().createObjectNode());
        controller.call("internal", "run-only-secret", request);
        verify(gateway).call("run-only-secret", request);
    }
}
