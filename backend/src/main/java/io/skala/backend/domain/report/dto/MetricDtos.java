package io.skala.backend.domain.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** RPT-08·09 지표 사전. 지표는 보고서용 뷰만 읽는 검증된 SQL 모음(FR-RPT-005) */
public final class MetricDtos {

    private MetricDtos() {
    }

    /** 목록에는 SQL을 내보내지 않고, 블록에서는 metricId로 가리킨다(RPT-10에서 백엔드가 SQL로 바꿔 넘김) */
    public record Metric(String id, String code, String name, String description, OffsetDateTime createdAt) {
    }

    public record CreateRequest(@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,79}$") String code,
                                @NotBlank @Size(max = 200) String name, @Size(max = 2000) String description,
                                @NotBlank @Size(max = 4000) String sqlText) {
    }
}
