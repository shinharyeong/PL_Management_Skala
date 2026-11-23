package io.skala.backend.domain.review.dto;

import io.skala.backend.domain.financial.value.Scenario;
import java.util.List;

/** GAP-08 · PROOF-03 항목 상세. summary(계획 대비·Gap 기준)는 경영기획팀에게만 내려준다. */
public record ReviewItemDetailResponse(
        String id,
        String path,
        Scenario scenario,
        int month,
        GapSummary summary,
        List<Row> rows,
        ExplanationResponse previousExplanation,
        List<ExplanationResponse> explanations,
        String stateToken
) {

    /** 원본 셀별 변경 내역 */
    public record Row(String sourceCell, String baseline, String previous, String current, String diff, String rate) {
    }
}
