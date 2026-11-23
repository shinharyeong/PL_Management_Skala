package io.skala.backend.domain.dashboard.dto;

import io.skala.backend.domain.master.dto.MasterResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * HOME-01 데이터 상태와 마감 진행 막대.
 * dataStatus: CONFIRMED(master 있음) · PROVISIONAL(승인본만 있음) · NONE. dDay는 마감까지 남은 날(KST, 지나면 음수).
 */
public record HomeStatusResponse(String dataStatus, MasterResponse master, List<MasterResponse> masters,
                                 OffsetDateTime dueAt, Long dDay, List<Step> steps) {

    /** key: REQUESTED·SUBMITTED·GAP_REVIEW·EXPLAINED·MASTER */
    public record Step(String key, String label, long done, long total, LocalDate date) {
    }
}
