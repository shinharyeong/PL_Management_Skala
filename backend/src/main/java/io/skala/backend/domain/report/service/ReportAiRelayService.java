package io.skala.backend.domain.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.skala.backend.domain.report.dto.ReportAiRequests;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.security.AuthenticatedUser;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * RPT-10·11·15를 report-ai로 넘긴다(명세 12절). 백엔드는 권한 확인과 감사 헤더만 붙이고, 응답은 AI 응답 그대로다.
 * AI가 숫자를 만들지 않고 보고서용 뷰에서 코드가 계산한다는 원칙은 report-ai 쪽에서 지킨다.
 */
@Service
@RequiredArgsConstructor
public class ReportAiRelayService {

    private final ReportAiClient reportAi;
    private final TemplateDefinitionValidator validator;
    private final ReportMetricService metrics;

    public JsonNode data(AuthenticatedUser user, ReportAiRequests.Data request) {
        user.requireRole(UserRole.PLANNER);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("base", Map.of("year", request.base().year(), "month", request.base().month()));
        body.put("blocks", metrics.resolveMetrics(request.blocks()));      // metricId → 지표 사전의 SQL
        return reportAi.post("/v1/data", body, user.id());
    }

    public JsonNode nlQuery(AuthenticatedUser user, ReportAiRequests.NlQuery request) {
        user.requireRole(UserRole.PLANNER);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", request.question());
        body.put("baseYear", request.baseYear());
        body.put("baseMonth", request.baseMonth());
        body.put("conversationId", "u" + user.id());     // 사용자별 대화 기억(report-ai 서버 메모리, 짧은 후속 질문용)
        return reportAi.post("/v1/nl-query", body, user.id());
    }

    public JsonNode edit(AuthenticatedUser user, ReportAiRequests.Edit request) {
        user.requireRole(UserRole.PLANNER);
        validator.validate(request.definition());             // 저장 봉투에 맞는 틀만 편집한다
        return reportAi.post("/v1/edit", Map.of("definition", request.definition(), "instruction", request.instruction()),
                user.id());
    }
}
