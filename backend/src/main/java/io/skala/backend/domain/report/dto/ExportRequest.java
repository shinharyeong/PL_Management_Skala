package io.skala.backend.domain.report.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * RPT-16 내보내기. 서버는 XLSX만 만든다(숫자는 원 단위 값 그대로). PNG·PDF는 화면(브라우저)에서 만든다.
 * version을 비우면 최신 버전, blockId를 주면 그 블록만.
 */
public record ExportRequest(@NotBlank String templateId, @Min(1) Integer version,
                            @NotNull @Valid ReportAiRequests.Base period, @Size(max = 64) String blockId,
                            @NotNull @Pattern(regexp = "XLSX", message = "서버 내보내기는 XLSX만 지원합니다. PNG·PDF는 화면에서 내보내세요.")
                            String format) {
}
