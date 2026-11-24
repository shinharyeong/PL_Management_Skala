package io.skala.backend.domain.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** RPT-05 새 버전. expectedVersion이 지금 최신 버전과 다르면 REPORT_REVISION_CHANGED */
public record TemplateVersionRequest(@NotNull JsonNode definition, @NotNull @Min(1) Integer expectedVersion) {
}
