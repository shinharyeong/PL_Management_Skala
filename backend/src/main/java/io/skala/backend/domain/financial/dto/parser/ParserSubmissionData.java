package io.skala.backend.domain.financial.dto.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 파서 브리지 응답의 {@code submissionData}(adapterVersion financial-submission/1.0).
 * 기준 문서: parser/docs/development/output-contract.md "서비스 제출 검증 어댑터".
 * 금액은 십진 문자열 그대로 받고, 빈 값은 null로 둔다(0으로 바꾸지 않음).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParserSubmissionData(
        String adapterVersion,
        String sourceSha256,
        Owner owner,
        List<ReportItem> reportItems,
        List<Line> lines,
        List<Diagnostic> diagnostics
) {

    public ParserSubmissionData {
        reportItems = reportItems == null ? List.of() : List.copyOf(reportItems);
        lines = lines == null ? List.of() : List.copyOf(lines);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(String code, String name, String status) {
    }

    /** SUMIFS로 확인한 보고 말단. key는 ["REVENUE",["매출","제품매출"]] 형태의 JSON 문자열. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReportItem(String key, String kind, String name, List<String> path, String status) {

        public ReportItem {
            path = path == null ? List.of() : List.copyOf(path);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(
            String key,
            String domain,
            Source source,
            Account account,
            String detailId,
            String detailRevision,
            String reportItemKey,
            String reportMappingStatus,
            String managementCategory,
            String actualBasis,
            String costType,
            String teamCode,
            String taskCode,
            String description,
            String personInCharge,
            String teamName,
            String taskName,
            String headquartersName,
            List<Value> values
    ) {

        public Line {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String sheet, Integer row) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(String code, String name) {
    }

    /** valueStatus: AVAILABLE / MISSING / UNRESOLVED. AVAILABLE일 때만 amount가 있다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Value(
            Integer year,
            Integer month,
            String scenario,
            String amount,
            String valueStatus,
            String inputPresence,
            String sourceCell,
            String sourceRecordId
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Diagnostic(String code, String severity, String stage, String message, CellRef source,
                             String lineKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CellRef(String sheet, String cell) {
    }
}
