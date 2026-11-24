package io.skala.backend.domain.review.dto;

import java.util.List;
import java.util.Map;

/** GAP-06 Gap 계층형 표(view=PLANNER) */
public record ReviewTreeResponse(SubmissionRef submission, Map<String, Integer> tabCounts, List<Node> tree) {

    /** 표가 보여 주는 제출본(요청·조직의 최신 제출본). 제출본이 없으면 null */
    public record SubmissionRef(String id, String label, String status) {
    }

    /**
     * 대/중/소(보고 항목) 행과 상세(월·구분) 행. 상세 행에만 reviewItemId·stage·tab·stateToken이 있다.
     * plan은 비교 기준 값(추정 vs 직전 추정이면 직전 추정), actual은 이번 제출 값.
     */
    public record Node(String reportItemId, String name, int level, String plan, String actual, String diff,
                       String rate, Integer stage, String tab, boolean gapExceeded, String reviewItemId,
                       String stateToken, List<Node> children) {
    }
}
