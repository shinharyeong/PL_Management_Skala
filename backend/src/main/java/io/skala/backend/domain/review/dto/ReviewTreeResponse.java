package io.skala.backend.domain.review.dto;

import java.util.List;
import java.util.Map;

/** GAP-06 Gap 계층형 표(view=PLANNER) */
public record ReviewTreeResponse(SubmissionRef submission, Map<String, Integer> tabCounts, List<Node> tree) {

    /** 표가 보여 주는 제출본(요청·조직의 최신 제출본). 제출본이 없으면 null */
    public record SubmissionRef(String id, String label, String status) {
    }

    public record ComparisonValue(String label, String basis, String baselineSubmissionId,
                                  String baselineScenario, String baselineAmount, String currentAmount,
                                  String diff, String rate) {}

    /**
     * 대/중/소(보고 항목) 행과 상세(월·구분) 행. 상세 행에만 reviewItemId·stage·tab·stateToken이 있다.
     * plan은 비교 기준 값(추정 vs 직전 추정이면 직전 추정), actual은 이번 제출 값.
     * 상세 행의 scenario로 실적/추정을 구분한다. previousEstimate는 전월 승인본의 동일 월 추정이며 없으면 null.
     */
    public record Node(String reportItemId, String name, int level, String plan, String actual, String diff,
                       String rate, Integer stage, String tab, boolean gapExceeded, String reviewItemId,
                       String stateToken, List<Node> children, String previousEstimate, String scenario, Integer month, List<ComparisonValue> comparisons) {
        public Node(String reportItemId, String name, int level, String plan, String actual, String diff,
                    String rate, Integer stage, String tab, boolean gapExceeded, String reviewItemId,
                    String stateToken, List<Node> children) {
            this(reportItemId, name, level, plan, actual, diff, rate, stage, tab, gapExceeded,
                    reviewItemId, stateToken, children, null, null, null, List.of());
        }
    }
}
