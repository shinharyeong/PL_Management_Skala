package io.skala.backend.domain.submission.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.skala.backend.domain.financial.dto.IngestionDiagnostic;
import io.skala.backend.domain.submission.dto.DiagnosticsResponse;
import java.util.List;
import java.util.Map;

/**
 * 검증 결과 파일(results/validation/*.json) 형식. submission_validation.validation_result_object_key가 가리킨다.
 * code는 규칙 코드, rule은 화면에 보여 줄 검증 항목 이름이다(UP-05).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidationReport(String policyVersion, int attemptNo, int errorCount, List<Entry> items) {

    public static final String POLICY_VERSION = "submission-validation/1.0";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String code, String sourceCell, String item, String rule, String guide) {
    }

    private static final Map<String, String> RULES = Map.ofEntries(
            Map.entry("UNSUPPORTED_PARSER_RESULT", "파일 해석"),
            Map.entry("SOURCE_HASH_MISMATCH", "파일 일치"),
            Map.entry("ORGANIZATION_MISMATCH", "조직 일치"),
            Map.entry("WORKBOOK_OWNER_UNRESOLVED", "조직 일치"),
            Map.entry("ORGANIZATION_LOOKUP_ERROR", "조직 확인"),
            Map.entry("ORGANIZATION_VALUE_ERROR", "조직 확인"),
            Map.entry("ORGANIZATION_NAME_CONFLICT", "조직 확인"),
            Map.entry("ORGANIZATION_CODE_NAME_CONFLICT", "조직 확인"),
            Map.entry("LINE_SOURCE_INVALID", "필수 시트·헤더"),
            Map.entry("REPORT_ITEMS_UNRESOLVED", "필수 시트·헤더"),
            Map.entry("REPORT_MAPPING_UNRESOLVED", "계정 코드 연결"),
            Map.entry("ACTIVE_FINANCIAL_LINES_NOT_FOUND", "상세 데이터 없음"),
            Map.entry("FINANCIAL_DETAIL_NOT_FOUND", "상세 데이터 없음"),
            Map.entry("DUPLICATE_DETAIL_ID", "중복 데이터"),
            Map.entry("DUPLICATE_PERIOD", "중복 데이터"),
            Map.entry("PERIOD_UNRESOLVED", "기간 확인"),
            Map.entry("PERIOD_OUT_OF_RANGE", "기간 일치"),
            Map.entry("VALUE_UNRESOLVED", "금액 형식"));

    public static ValidationReport of(int attemptNo, List<IngestionDiagnostic> diagnostics) {
        List<Entry> entries = diagnostics.stream()
                .map(d -> new Entry(d.rule(), d.sourceCell(), d.item(), ruleLabel(d.rule()),
                        d.guide() == null || d.guide().isBlank() ? "양식을 확인해 수정한 뒤 파일을 다시 올려 주세요." : d.guide()))
                .toList();
        return new ValidationReport(POLICY_VERSION, attemptNo, entries.size(), entries);
    }

    static String ruleLabel(String code) {
        return code == null ? "양식 확인" : RULES.getOrDefault(code, "양식 확인");
    }

    public List<DiagnosticsResponse.Item> toItems() {
        return items.stream().map(e -> new DiagnosticsResponse.Item(e.sourceCell(), e.item(), e.rule(), e.guide()))
                .toList();
    }
}
