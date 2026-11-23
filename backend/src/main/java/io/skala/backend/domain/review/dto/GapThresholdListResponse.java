package io.skala.backend.domain.review.dto;

import java.util.List;

/** GAP-03 현재 Gap 기준(비교 3종) */
public record GapThresholdListResponse(String stateToken, List<GapThresholdResponse> items) {
}
