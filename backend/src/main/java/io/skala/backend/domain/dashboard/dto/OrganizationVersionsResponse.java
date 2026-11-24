package io.skala.backend.domain.dashboard.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** ARCH-02 조직의 월별 최종(승인) 버전과, 월을 고르면 그 달 버전 이력 */
public record OrganizationVersionsResponse(OrganizationRef organization, List<MonthFinal> months, List<History> history) {

    public record OrganizationRef(String id, String code, String name) {
    }

    /** finalLabel: 가장 최근 승인 버전(ver{월}.{NN}). 승인본이 없으면 null */
    public record MonthFinal(int month, String finalLabel) {
    }

    public record History(String submissionId, String label, String status, OffsetDateTime lastModifiedAt, FileRef file) {
    }

    public record FileRef(String name, String url) {
    }
}
