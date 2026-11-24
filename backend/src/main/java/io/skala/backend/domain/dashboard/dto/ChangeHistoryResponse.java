package io.skala.backend.domain.dashboard.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * ARCH-03 제출본의 변경 이력. EXPLAINED는 제출 때 쓴 1회차 소명(변경 일자 = 제출 시각),
 * RE_EXPLAINED는 경영기획팀 요청에 답한 재소명(답한 날짜별로 묶음, 변경 일자 = 그날 마지막 답변 시각).
 */
public record ChangeHistoryResponse(List<DateGroup> dates) {

    public record DateGroup(OffsetDateTime changedAt, String state, List<Item> items) {
    }

    public record Item(int no, String code, String name, int month, String scenario, String explanation) {
    }
}
