package io.skala.backend.domain.decisionagent.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

/** 저장 없는 대화와 단기 조회 capability의 공개·내부 계약. */
public final class DecisionAssistantDtos {
    /** 목적: 계약 이름 공간 생성을 막는다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    private DecisionAssistantDtos() { }
    public record History(@NotBlank @Pattern(regexp="user|assistant") String role,
                           @NotBlank @Size(max=2000) String content) { }
    public record Request(@NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String masterId,
                           @Pattern(regexp="[1-9][0-9]{0,18}") String analysisId,
                           @Min(1) Integer expectedRevision,
                           @NotBlank @Size(max=2000) String message,
                           @NotNull @Size(max=8) List<@NotNull @Valid History> history) { }
    public record Context(String masterId, String masterLabel, int year, int baseMonth,
                           String analysisId, Integer revision, OffsetDateTime asOf) { }
    public record ComposeRequest(String message, List<History> history, Context context, String toolAccessToken) { }
    public record ToolRequest(@NotBlank @Size(max=100) String tool, @NotNull JsonNode arguments) { }
    public record Basis(String masterId, int year, int baseMonth, String analysisId, Integer revision,
                         OffsetDateTime capturedAt, String comparisonBasis) { }
    public record Observation(String id, String tool, String label, String status, Basis basis,
                              JsonNode data, List<String> warnings) { }
    public record ToolCall(String id, String tool, String label, String status, String observationId) { }
    public record Citation(String observationId, String path, String quote) { }
    public record Finding(String id, String kind, String text, List<Citation> citations, String uncertainty) { }
    public record Requirement(String id, String requestQuote, String question, String status,
                              List<String> findingIds, String missingInformation, String followUpQuestion) { }
    public record Brief(String summary, List<Requirement> requirements, List<Finding> findings) { }
    public record Response(String mode, String status, String answer, Context context,
                            List<ToolCall> toolCalls, List<Observation> observations,
                            List<Citation> citations, List<String> nextActions, List<String> limitations, Brief brief) {
        /** 목적: 기존 호출부의 근거 목록 응답을 호환한다. Args: 기존 응답 필드. Returns: brief 없는 응답. Raises: 없음. Side Effects: 없음. */
        public Response(String mode, String status, String answer, Context context, List<ToolCall> toolCalls,
                        List<Observation> observations, List<Citation> citations, List<String> nextActions, List<String> limitations) {
            this(mode, status, answer, context, toolCalls, observations, citations, nextActions, limitations, null);
        }
    }
}
