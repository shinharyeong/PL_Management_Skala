package io.skala.backend.domain.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** report-ai로 넘기는 요청(RPT-10·11·15). 블록·틀 형식은 AI가 정하므로 그대로 넘긴다 */
public final class ReportAiRequests {

    private ReportAiRequests() {
    }

    public record Base(@NotNull @Min(2000) @Max(9999) Integer year, @NotNull @Min(1) @Max(12) Integer month) {
    }

    /** RPT-10 블록 데이터 채우기 */
    public record Data(@NotNull @Valid Base base, @NotNull @Size(max = 500) List<JsonNode> blocks) {
    }

    /** RPT-11 자연어 질의 */
    public record NlQuery(@NotBlank @Size(max = 1000) String question, @NotNull @Min(2000) @Max(9999) Integer baseYear,
                          @NotNull @Min(1) @Max(12) Integer baseMonth) {
    }

    /** RPT-15 대화 편집 */
    public record Edit(@NotNull JsonNode definition, @NotBlank @Size(max = 1000) String instruction) {
    }
}
