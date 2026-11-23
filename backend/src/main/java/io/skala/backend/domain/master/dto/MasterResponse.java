package io.skala.backend.domain.master.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * ARCH-04 월별 master. state: CONFIRMED(최신 master 있음) · WAITING(생성 대기, id·seq·createdAt null).
 * 생성 대기의 label은 처음 만들어질 이름(master_ver{월}.01)이다.
 */
public record MasterResponse(String id, int year, int month, Integer seq, String label, String state,
                             OffsetDateTime createdAt) {

    public record ListResponse(List<MasterResponse> items) {
    }
}
