package io.skala.backend.domain.review.dto;

import io.skala.backend.domain.financial.value.Scenario;
import io.skala.backend.domain.submission.dto.BaselineResponse;
import java.util.List;

/** PROOF-02 조직 소명 대상 목록(view=ORG). Gap 기준은 내려주지 않는다. */
public record OrgReviewListResponse(BaselineResponse baseline, Progress progress, List<Group> groups) {

    public record Progress(int answered, int required) {
    }

    /** group: REVENUE / COST */
    public record Group(String group, int cellCount, List<MonthGroup> months) {
    }

    public record MonthGroup(int month, Scenario scenario, List<Item> items) {
    }

    /**
     * @param previous    재제출일 때 직전(반려된) 제출값. 재제출이 아니거나 값이 없으면 null
     * @param answerState NOT_REQUIRED(비교 기준 없는 최초 월간 제출의 조회 전용 항목) / EMPTY / WRITTEN / INHERITED(값이 그대로라 지난 승인 소명을 이어받음)
     */
    public record Item(String reviewItemId, String name, String sourceCell, String baseline, String previous,
                       String current, String rate, String answerState, String stateToken) {
    }
}
