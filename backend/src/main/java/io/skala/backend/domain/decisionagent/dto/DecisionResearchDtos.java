package io.skala.backend.domain.decisionagent.dto;

import io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.Category;
import io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.Snapshot;
import io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.Step;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 고정 원천 조회·목표 해석 제안·금액 미정 가설의 공개 및 내부 계약. */
public final class DecisionResearchDtos {
    /** 목적: 계약 이름 공간 생성을 막는다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    private DecisionResearchDtos() { }

    public record GoalRequest(@NotBlank @Size(max=4000) String prompt, @Min(2000) @Max(9999) int year) { }
    public record GoalProposal(String targetProfitWon, Integer targetYear, List<Category> protectedCategories,
                               boolean needsConfirmation, List<String> uncertainties) { }
    public record GoalResponse(String mode, GoalProposal goalProposal) { }
    public record Evidence(String refId, String kind, String organizationId, String label, String text,
                           Map<String, Object> facts) { }
    public record CatalogEntry(String refId, String kind, String organizationId, String label) { }
    public record Hypothesis(String id, String organizationId, String title, String rationale,
                             List<String> evidenceRefs, String amountWon, Integer recognitionMonth,
                             Category category, List<String> questions, boolean needsConfirmation, String sourceQuote) { }
    public record Discovery(String mode, GoalProposal goalProposal, List<Hypothesis> hypotheses,
                            List<String> limitations, List<Step> steps) { }
    public record Research(String analysisId, int revision, OffsetDateTime capturedAt,
                           List<Evidence> evidence, Discovery discovery) { }
    public record DiscoverRequest(@Min(1) int expectedRevision) { }
    public record ComposeDiscovery(String analysisId, int revision, String prompt, Snapshot snapshot,
                                   List<Category> protectedCategories, List<CatalogEntry> evidenceCatalog,
                                   String toolAccessToken) { }
    public record ReadEvidenceRequest(@NotBlank String analysisId, @Min(1) int revision,
                                      @NotNull @Size(min=1,max=20) List<@NotBlank String> refIds) { }
    public record ReadEvidenceResponse(String analysisId, int revision, List<Evidence> items) { }
}
