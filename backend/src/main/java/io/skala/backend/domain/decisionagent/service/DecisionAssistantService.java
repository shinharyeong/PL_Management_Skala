package io.skala.backend.domain.decisionagent.service;

import io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.Analysis;
import io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.Snapshot;
import static io.skala.backend.domain.decisionagent.dto.DecisionAssistantDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.skala.backend.domain.report.service.ReportAiClient;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** DB 트랜잭션을 보유하지 않은 채 내부 Agent와 읽기 capability의 생명주기를 조율한다. */
@Service
@RequiredArgsConstructor
public class DecisionAssistantService {
    private final DecisionAnalysisStore analyses;
    private final DecisionBaselineLoader baselines;
    private final DecisionResearchStore research;
    private final DecisionAssistantGateway gateway;
    private final DecisionAssistantTools tools;
    private final DecisionAssistantResponseValidator validator;
    private final ReportAiClient ai;

    /** 목적: 고정 기준의 대화를 읽기 전용으로 실행한다. Args: actor 기획자, request 기준·선택 분석·질문. Returns: 실제 관찰로 검증한 답변. Raises: 권한/기준/버전 오류. Side Effects: DB 읽기, 내부 AI HTTP, 일시 메모리만. */
    public Response ask(AuthenticatedUser actor, Request request) {
        actor.requireRole(UserRole.PLANNER);
        if ((request.analysisId() == null) != (request.expectedRevision() == null))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "분석을 선택하면 예상 revision도 함께 전달해야 합니다.");
        Snapshot snapshot = baselines.load(id(request.masterId()));
        Analysis analysis = request.analysisId() == null ? null : analyses.get(actor.id(), id(request.analysisId()), false);
        if (analysis != null) {
            if (analysis.revision() != request.expectedRevision() || !analysis.snapshot().masterId().equals(request.masterId())) throw new BusinessException(ErrorCode.RESOURCE_CHANGED);
            research.assertSameAmounts(analysis.snapshot(), snapshot); snapshot = analysis.snapshot();
        }
        String token = gateway.grant(actor, snapshot, analysis, request.message());
        try {
            var run = gateway.require(token);
            tools.assertCurrent(run);
            JsonNode proposed = null;
            try { proposed = ai.post("/v1/decision-agent/assistant", new ComposeRequest(request.message(), request.history(), run.context, token), actor.id()); }
            catch (RuntimeException ignored) { }
            tools.assertCurrent(run);
            synchronized (run) {
                try { return validator.validate(proposed, run); }
                catch (IllegalArgumentException exception) { return validator.unavailable(run); }
            }
        } finally { gateway.revoke(token); }
    }

    /** 목적: 외부 식별자의 long 범위를 검사한다. Args: value 양수 문자열. Returns: long 식별자. Raises: 입력 오류. Side Effects: 없음. */
    private long id(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) { throw new BusinessException(ErrorCode.INVALID_REQUEST, "식별자 형식이 올바르지 않습니다."); }
    }
}
