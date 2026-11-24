package io.skala.backend.domain.submission.dto;

import java.util.List;

/** UP-05 검증 결과 · 수정 필요 표 */
public record DiagnosticsResponse(SubmissionResponse.FileRef file, int errorCount, List<Item> items) {

    /**
     * @param sourceCell 원본 위치(예: 손익제출!N40). 모르면 null
     * @param item       항목 이름. 모르면 null
     * @param rule       검증 항목(화면 문구, 예: 금액 누락)
     * @param guide      수정 안내
     */
    public record Item(String sourceCell, String item, String rule, String guide) {
    }
}
