package io.skala.backend.domain.decisionagent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/** 의사결정 분석의 고정 기준·후보·계산 결과 계약. 금액과 식별자는 JSON 문자열이다. */
public final class DecisionAgentDtos {
    /** 목적: DTO 이름 공간 생성을 막는다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    private DecisionAgentDtos() { }

    public enum Category { OPERATIONS, PROCUREMENT, REVENUE, HEADCOUNT, RND, UNKNOWN }
    public enum EffectType { NET_PROFIT, REVENUE }
    public enum EvidenceStatus { ASSUMPTION, REVIEWED }

    public record Items<T>(List<T> items) { }
    public record Baseline(String masterId, String label, int year, int month,
                           OffsetDateTime createdAt, int sourceCount) { }
    public record AnalysisSummary(String id, String title, int revision, OffsetDateTime createdAt) { }
    public record Organization(String id, String code, String name, String revenueWon,
                               String costWon, String profitWon) { }
    public record Snapshot(String masterId, String masterLabel, int year, int baseMonth,
                           OffsetDateTime asOf, List<String> sourceSubmissionIds,
                           List<Organization> organizations, String baselineProfitWon) { }
    public record CreateRequest(@NotBlank @Size(max = 200) String title,
                                @NotBlank @Size(max = 4000) String prompt,
                                @NotBlank String masterId, @NotBlank String targetProfitWon,
                                @NotNull @Size(max = 2) List<@NotNull Category> protectedCategories) { }
    public record Candidate(@NotBlank @Size(max = 100) String id, @NotBlank String organizationId,
                            @NotBlank @Size(max = 200) String title, @NotNull EffectType type,
                            @NotBlank String amountWon, String contributionMarginPercent,
                            @NotBlank String implementationCostWon, @NotBlank String embeddedEffectWon,
                            @Min(1) @Max(24) int recognitionMonth, @NotNull Category category,
                            @NotNull @Size(max = 100) String duplicateGroup,
                            @NotNull EvidenceStatus evidenceStatus,
                            @NotNull @Size(max = 4000) String evidenceNote,
                            @NotNull @Size(max = 2000) String evidenceRef) { }
    public record CandidatesRequest(@Min(1) int expectedRevision,
                                    @NotNull @Size(max = 100) List<@NotNull @Valid Candidate> candidates) { }
    public record RunRequest(@Min(1) int expectedRevision) { }
    public record Effect(String candidateId, String netEffectWon, boolean included, boolean reviewed,
                         List<String> exclusionReasons) { }
    public record Result(String baselineProfitWon, String targetProfitWon, String initialGapWon,
                         String reviewedEffectWon, String conditionalEffectWon, String reviewedOutlookWon,
                         String conditionalOutlookWon, String reviewedGapWon, String conditionalGapWon,
                         String targetCoveragePercent, List<Effect> effects, List<String> warnings) { }
    public record Step(String key, String label, String status) { }
    public record Narrative(String mode, String summary, List<String> decisionRequests,
                            List<String> limitations, List<Step> steps) { }
    public record Analysis(String id, String title, String prompt, int revision, String status,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt, String targetProfitWon,
                           List<Category> protectedCategories, Snapshot snapshot, List<Candidate> candidates,
                           Result result, Narrative narrative) { }
    public record ComposeRequest(String analysisId, String title, String prompt, String targetProfitWon,
                                 List<Category> protectedCategories, Snapshot snapshot,
                                 List<Candidate> candidates, Result result) { }
}
