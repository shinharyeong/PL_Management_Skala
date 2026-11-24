package io.skala.backend.domain.review.dto;

/**
 * GAP-08 경영기획팀 보기의 선택 항목 요약. 계획(확정 연간 계획) 대비 값과 적용된 Gap 기준.
 * threshold는 제출 때 고정한 기준(없으면 현재 기준, 그것도 없으면 null).
 */
public record GapSummary(String plan, String actual, String diff, String rate, GapThresholdResponse threshold) {
}
