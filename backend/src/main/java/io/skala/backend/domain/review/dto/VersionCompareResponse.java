package io.skala.backend.domain.review.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** GAP-10 같은 셀을 버전별로 한 줄에 비교 */
public record VersionCompareResponse(List<Column> columns, List<Row> rows) {

    /**
     * @param state               CONFIRMED(이전 승인) · CURRENT(현재 기준 승인본) · NEW(아직 승인 전인 이 제출본)
     * @param nextLabelOnApproval NEW일 때 승인되면 붙을 버전 이름
     */
    public record Column(String submissionId, String label, String state, OffsetDateTime approvedAt,
                         String nextLabelOnApproval) {
    }

    /**
     * @param values            열 순서대로의 값(그 버전에 셀이 없으면 null)
     * @param change            마지막 열 − 바로 앞 열
     * @param basis             마지막 열 행의 산출 근거(거래내역)
     * @param uploadExplanation 이 항목에 업로드 때 쓴 소명
     */
    public record Row(String cell, String name, List<String> values, String change, String basis,
                      String uploadExplanation, boolean changed) {
    }
}
