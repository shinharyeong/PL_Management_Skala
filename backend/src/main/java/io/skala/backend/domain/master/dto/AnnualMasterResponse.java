package io.skala.backend.domain.master.dto;
import java.time.OffsetDateTime;
import java.util.List;
public record AnnualMasterResponse(String id, int year, int seq, String label,
        OffsetDateTime createdAt, String createdByName, int organizationCount) {
    public record ListResponse(int year, String state, List<AnnualMasterResponse> items) {}
}
