package io.skala.backend.domain.decisionworkflow.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.Candidate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;

/** 조직 확인·검토·외부 의사결정 기록·후속 비교 계약. 조직 응답에 전사 분석을 포함하지 않는다. */
public final class DecisionWorkflowDtos {
    /** 목적: DTO 이름 공간 생성을 막는다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    private DecisionWorkflowDtos() { }

    public enum State { DRAFT, REQUESTED, REPLIED, CHANGES_REQUESTED, ACCEPTED, REJECTED, APPROVED, DECLINED, IN_PROGRESS, COMPLETED, CANCELLED }
    public enum Feasibility { FEASIBLE, NOT_FEASIBLE }
    public enum ReviewVerdict { ACCEPT, REQUEST_CHANGES, REJECT }
    public enum DecisionVerdict { APPROVE, DECLINE }
    public enum ProgressStatus { IN_PROGRESS, COMPLETED }

    public record CreateRequest(@Min(1) int expectedAnalysisRevision,
                                @Size(max = 100) String candidateId, @Size(max = 100) String hypothesisId,
                                @NotBlank @Size(max = 4000) String message, @NotNull OffsetDateTime dueAt) { }
    public record RevisionRequest(@Min(1) int expectedRevision) { }
    public record ReplyRequest(@Min(1) int expectedRevision, @NotNull Feasibility feasibility,
                               @Valid Candidate candidate, @NotBlank @Size(max = 4000) String note,
                               @NotBlank @Size(max = 2000) String evidenceRef) { }
    public record ReviewRequest(@Min(1) int expectedRevision, @Min(1) int expectedAnalysisRevision,
                                @NotNull ReviewVerdict verdict, @NotBlank @Size(max = 4000) String note) { }
    public record DecisionRequest(@Min(1) int expectedRevision, @Min(1) int expectedAnalysisRevision,
                                  @NotNull DecisionVerdict verdict, @NotBlank @Size(max = 200) String decisionMaker,
                                  @NotBlank @Size(max = 2000) String decisionEvidenceRef,
                                  @NotBlank @Size(max = 4000) String note, @NotNull OffsetDateTime dueAt) { }
    public record ProgressRequest(@Min(1) int expectedRevision, @NotNull ProgressStatus status,
                                  @NotBlank @Size(max = 4000) String note, @NotBlank @Size(max = 2000) String evidenceRef,
                                  @Size(max = 30) String followupMasterId) { }
    public record CancelRequest(@Min(1) int expectedRevision, @Min(1) int expectedAnalysisRevision,
                                @NotBlank @Size(max = 4000) String note) { }
    public record Reply(Feasibility feasibility, Candidate candidate, String note, String evidenceRef, String actorId, OffsetDateTime at) { }
    public record Review(ReviewVerdict verdict, String note, String actorId, OffsetDateTime at) { }
    public record Decision(DecisionVerdict verdict, String decisionMaker, String decisionEvidenceRef, String note,
                           OffsetDateTime dueAt, int analysisRevision, String candidateEffectWon, String actorId, OffsetDateTime at) { }
    public record Progress(ProgressStatus status, String note, String evidenceRef, String followupMasterId,
                           String followupMasterLabel, String baselineProfitWon, String followupProfitWon,
                           String profitChangeWon, String actorId, OffsetDateTime at) { }
    public record Event(int sequence, String action, String actorId, OffsetDateTime at, String note, JsonNode details) { }

    @Builder(toBuilder = true)
    public record WorkItem(String id, String analysisId, String candidateId, String hypothesisId,
                           String organizationId, String organizationName, String ownerId, String title,
                           int year, int baseMonth, String masterLabel, String baselineOrganizationProfitWon,
                           int sourceAnalysisRevision, int revision, State state, int round, String message,
                           OffsetDateTime dueAt, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                           Candidate sourceCandidate, JsonNode sourceHypothesis, Reply reply, Review review,
                           Decision decision, Progress progress, List<Event> events) { }
}
