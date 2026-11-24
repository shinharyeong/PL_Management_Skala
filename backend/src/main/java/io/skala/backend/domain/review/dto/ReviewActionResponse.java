package io.skala.backend.domain.review.dto;

import java.util.List;
import java.util.Map;

/** GAP-07 응답: 처리한 항목의 새 탭·토큰과, 처리한 제출본들의 탭 건수(제출본이 하나면 그 건수) */
public record ReviewActionResponse(List<Item> items, Map<String, Integer> tabCounts) {

    public record Item(String reviewItemId, String tab, String stateToken) {
    }
}
