package io.skala.backend.domain.report.dto;

import java.util.List;

/** RPT-12 소명 사유 유형 분류표 */
public record ExplanationCategoryResponse(int tableVersion, List<Item> items) {

    /**
     * @param parentCode 상위 유형 코드(하위 유형이면 값). 상위 유형의 합계는 하위 유형 합
     * @param catchAll   분류하지 않은 소명이 자동으로 가는 '기타'
     * @param assignable 카드를 직접 넣을 수 있는지(상위 유형은 false)
     */
    public record Item(String id, int code, String name, Integer parentCode, boolean catchAll, boolean assignable) {
    }
}
