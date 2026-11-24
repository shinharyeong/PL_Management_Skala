package io.skala.backend.domain.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** RPT-04 새 틀(버전 1). definition.name은 name으로 맞춘다 */
public record TemplateCreateRequest(@NotBlank @Size(max = 120) String name, @NotNull JsonNode definition,
                                    String sourcePdfJobId) {
}
