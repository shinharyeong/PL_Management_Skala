package io.skala.backend.domain.report.dto;

import java.util.List;
import java.util.Map;

/** RPT-13 Gap 설명 블록용 승인 소명 카드 + 제안 분류. 금액은 원 단위 문자열 */
public record ReportExplanationResponse(String gapTotal, List<Card> items, List<Reviewing> reviewing,
                                        Map<String, String> categoryTotals, String classifiedTotal,
                                        boolean matchesGapTotal) {

    public record OrganizationRef(String id, String code, String name) {
    }

    /**
     * @param categoryId         이미 분류했으면 값
     * @param suggestedCategoryId 지난달 같은 조직·항목 분류(LAST_MONTH) 또는 답변 단어 규칙(RULE)으로 고른 후보
     */
    public record Card(String explanationId, OrganizationRef organization, String item, String gapAmount,
                       String answer, String categoryId, String suggestedCategoryId, String suggestionSource) {
    }

    /** 아직 수락 전이라 분류할 수 없는 소명(사유 확인 중) */
    public record Reviewing(OrganizationRef organization, String item, String gapAmount) {
    }
}
