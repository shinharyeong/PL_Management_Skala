package io.skala.backend.submission;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** 요청 연도의 전체 PLAN 또는 마감월까지 ACTUAL·이후 ESTIMATE를 원본 근거와 대조한다. 파일·DB 접근이나 금액 보정은 하지 않는다. */
@Component
public class FinancialValidationRules {
    private final ObjectMapper mapper;

    /** JSON 직렬화기를 연결한다. Args: mapper 직렬화기. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public FinancialValidationRules(ObjectMapper mapper) { this.mapper = mapper; }

    /** 월별 상세·보고분류·합계 근거를 검증하고 적재 가능한 원본 행을 돌려준다.
     * Args: data 파서 submissionData, sourceHash 저장 원본 해시, organizationCode 인증 조직,
     * year 요청 연도, requestType 요청 유형.
     * Returns: reports/lines/diagnostics/reportValues/fingerprint를 가진 검증 자료.
     * Raises: IllegalStateException 내부 직렬화 오류.
     * Side Effects: 메모리에서만 검증하며 원본·파서 JSON·DB를 변경하지 않는다.
     */
    public Map<String, Object> evaluate(JsonNode data, String sourceHash, String organizationCode, int year, String requestType) {
        return evaluate(data, sourceHash, organizationCode, year, requestType, null);
    }

    /** 연간 또는 월간 전체 스냅샷을 요청 기간·시나리오의 원본 근거와 대조한다.
     * Args: data 파서 근거, sourceHash 원본 해시, organizationCode 조직, year 연도, requestType 유형, closingMonth 월간 마감월.
     * Returns: 적재 가능한 상세·보고값·진단·구성 지문. Raises: IllegalStateException 직렬화 오류.
     * Side Effects: 메모리 계산만 수행하며 빈칸이나 불명확한 기간을 보정하지 않는다.
     */
    public Map<String, Object> evaluate(JsonNode data, String sourceHash, String organizationCode, int year, String requestType, Integer closingMonth) {
        var diagnostics = new ArrayList<Map<String, Object>>();
        var reports = new LinkedHashMap<String, Map<String, Object>>();
        var lines = new ArrayList<Map<String, Object>>();
        var totals = new HashMap<String, BigDecimal>();
        var fingerprints = new ArrayList<String>();
        if (!"ANNUAL_PLAN".equals(requestType) && (!"MONTHLY_ACTUAL".equals(requestType) || closingMonth == null || closingMonth < 1 || closingMonth > 12)) issue(diagnostics, "MONTHLY_PERIOD_REQUIRED", "월간 요청의 마감월을 확인할 수 없습니다.", null, null, null, null);
        if (!data.isObject() || !"financial-submission/1.0".equals(text(data, "adapterVersion"))) {
            issue(diagnostics, "SUBMISSION_ADAPTER_REQUIRED", "업무 적재 근거가 없는 이전 파서 결과입니다. 새 파일로 다시 접수해 주세요.", null, null, null, null);
            return object("reports", List.of(), "lines", List.of(), "diagnostics", diagnostics, "reportValues", List.of(), "fingerprint", "");
        }
        if (!sourceHash.equals(text(data, "sourceSha256"))) issue(diagnostics, "SOURCE_HASH_MISMATCH", "업무 적재 자료와 저장 원본의 해시가 다릅니다.", null, null, null, null);
        JsonNode owner = data.path("owner");
        if (!resolved(owner.path("status").asText()) || !organizationCode.equals(text(owner, "code"))) issue(diagnostics, "SUBMISSION_ORGANIZATION_MISMATCH", "원본에서 확인한 제출 조직이 현재 조직과 일치하지 않습니다.", null, null, null, null);
        for (JsonNode item : data.path("reportItems")) {
            String key = text(item, "key"), name = text(item, "name"), kind = upper(item, "kind");
            if (key == null || name == null || name.length() > 500 || !Set.of("REVENUE", "COST").contains(kind) || !resolved(item.path("status").asText()) || reports.containsKey(key)) {
                issue(diagnostics, "REPORT_MAPPING_UNRESOLVED", "보고 항목의 원본 분류·집계 수식 근거를 유일하게 확인하지 못했습니다.", null, null, null, null);
                continue;
            }
            reports.put(key, object("key", key, "code", "SOURCE:" + hash(key.getBytes(StandardCharsets.UTF_8)), "name", name, "kind", kind));
        }
        for (JsonNode diagnostic : data.path("diagnostics")) {
            if (diagnostic.hasNonNull("year") && diagnostic.path("year").asInt() != year) continue;
            if (diagnostic.hasNonNull("scenario") && !scenario(requestType, closingMonth, diagnostic.path("month").asInt()).equals(upper(diagnostic, "scenario"))) continue;
            if (!"ERROR".equals(upper(diagnostic, "severity"))) continue;
            issue(diagnostics, Optional.ofNullable(text(diagnostic, "code")).orElse("ADAPTER_EVIDENCE_ERROR"),
                    Optional.ofNullable(text(diagnostic, "message")).orElse("업무 적재 근거를 확인해 주세요."),
                    text(diagnostic.path("source"), "sheet"), text(diagnostic.path("source"), "cell"), null,
                    diagnostic.hasNonNull("month") ? diagnostic.path("month").asInt() : null);
        }
        var domains = new HashSet<String>();
        for (JsonNode sheet : data.path("sheets")) {
            String domain = domain(upper(sheet, "domain"));
            if (!Set.of("REVENUE", "COST").contains(domain)) continue;
            domains.add(domain);
            var months = new HashSet<Integer>();
            for (JsonNode period : sheet.path("periodColumns")) {
                if (resolved(period.path("status").asText()) && period.path("year").asInt() == year && scenario(requestType, closingMonth, period.path("month").asInt()).equals(upper(period, "scenario")) && period.path("month").asInt() >= 1 && period.path("month").asInt() <= 12) months.add(period.path("month").asInt());
            }
            if (months.size() != 12) issue(diagnostics, "PLAN_MONTH_HEADERS_INCOMPLETE", "요청 연도·마감월에 맞는 1~12월 시나리오 헤더가 모두 확인되어야 합니다.", text(sheet, "name"), null, null, null);
        }
        if (!domains.containsAll(Set.of("REVENUE", "COST"))) issue(diagnostics, "FINANCIAL_SHEETS_INCOMPLETE", "매출·비용 상세 시트의 업무 근거가 모두 필요합니다.", null, null, null, null);
        var sourceRows = new HashSet<String>();
        var detailIds = new HashSet<String>();
        for (JsonNode line : data.path("lines")) {
            String sheet = text(line.path("source"), "sheet"), reportKey = text(line, "reportItemKey");
            int row = line.path("source").path("row").asInt();
            String kind = domain(upper(line, "domain"));
            Map<String, Object> report = reports.get(reportKey);
            int before = diagnostics.size();
            if (sheet == null || sheet.length() > 255 || row < 1 || !sourceRows.add(sheet + ":" + row)) issue(diagnostics, "SOURCE_ROW_INVALID", "원본 행 위치가 없거나 중복됩니다.", sheet, null, reportKey, null);
            if (report == null || !kind.equals(report.get("kind")) || !resolved(line.path("reportMappingStatus").asText())) lineIssue(diagnostics, "LINE_REPORT_MAPPING_UNRESOLVED", "관리분류·실적집계기준의 보고 항목 연결을 확인한 뒤 원본 전체 파일을 다시 업로드해 주세요.", line, fieldEvidence(line, "REVENUE".equals(kind) ? "managementCategory" : "actualBasis"), reportKey);
            if (!resolved(line.path("organization").path("status").asText()) || !organizationCode.equals(text(line.path("organization"), "code"))) lineIssue(diagnostics, "LINE_ORGANIZATION_MISMATCH", "상세 행의 조직이 제출 조직과 일치하지 않습니다. 원본의 조직명·팀코드 연결을 확인한 뒤 전체 파일을 다시 업로드해 주세요.", line, fieldEvidence(line, "organization", "teamCode"), reportKey);
            String business = upper(line.path("businessValidation"), "status");
            if (!"VALID".equals(business)) businessIssues(diagnostics, line, reportKey);
            String detailId = text(line, "detailId");
            if (detailId != null && !detailIds.add(kind + ":" + detailId)) lineIssue(diagnostics, "DUPLICATE_DETAIL_ID", "같은 상세 식별자가 반복됩니다. 원본의 중복·수정본을 확인한 뒤 전체 파일을 다시 업로드해 주세요.", line, fieldEvidence(line, "detailId"), reportKey);
            if (!bounded(line)) issue(diagnostics, "FINANCIAL_FIELD_TOO_LONG", "조직·계정·분류·식별자 길이가 적재 규격을 초과합니다.", sheet, firstSource(line), reportKey, null);
            var byMonth = new TreeMap<Integer, JsonNode>();
            for (JsonNode value : line.path("values")) {
                if (value.path("year").asInt() != year || !scenario(requestType, closingMonth, value.path("month").asInt()).equals(upper(value, "scenario"))) continue;
                int month = value.path("month").asInt();
                if (month < 1 || month > 12 || byMonth.putIfAbsent(month, value) != null) issue(diagnostics, "DUPLICATE_FINANCIAL_MONTH", "같은 상세 행의 요청 월·시나리오가 중복되거나 범위를 벗어났습니다.", sheet, text(value, "sourceCell"), reportKey, month);
            }
            var values = new ArrayList<Map<String, Object>>();
            for (int month = 1; month <= 12; month++) {
                JsonNode value = byMonth.get(month);
                String cell = value == null ? null : text(value, "sourceCell");
                BigDecimal amount = value == null ? null : amount(value);
                if (amount == null || cell == null || !cell.matches("[A-Z]{1,3}[1-9][0-9]{0,6}") || text(value, "sourceRecordId") == null || text(value, "sourceRecordId").length() > 500) {
                    issue(diagnostics, "PLAN_AMOUNT_UNRESOLVED", "요청 시나리오의 월별 금액·단위·수식 결과 또는 원본 셀 근거를 확인할 수 없습니다. 원본을 확인·수정한 뒤 전체 파일을 다시 업로드해 주세요.", sheet, cell != null && cell.matches("[A-Z]{1,3}[1-9][0-9]{0,6}") ? cell : null, reportKey, month);
                    continue;
                }
                values.add(object("year", year, "month", month, "scenario", scenario(requestType, closingMonth, month), "amount", amount,
                        "sourceCell", cell, "sourceRecordId", text(value, "sourceRecordId")));
            }
            if (diagnostics.size() != before) continue;
            Map<String, Object> accepted = mapper.convertValue(line, Map.class);
            accepted.put("values", values);
            accepted.put("domain", kind);
            lines.add(accepted);
            for (var value : values) {
                int month = (Integer) value.get("month");
                BigDecimal amount = (BigDecimal) value.get("amount");
                totals.merge(reportKey + ":" + month, amount, BigDecimal::add);
                totals.merge(kind + "_TOTAL:" + month, amount, BigDecimal::add);
                fingerprints.add(("ANNUAL_PLAN".equals(requestType) ? "" : scenario(requestType, closingMonth, month) + ":") + fingerprint(line, reportKey, month, amount));
            }
        }
        if (lines.isEmpty()) issue(diagnostics, "FINANCIAL_LINES_EMPTY", "적재 가능한 월별 재무 상세가 없습니다.", null, null, null, null);
        for (int month = 1; month <= 12; month++) totals.put("OP:" + month, totals.getOrDefault("REVENUE_TOTAL:" + month, BigDecimal.ZERO).subtract(totals.getOrDefault("COST_TOTAL:" + month, BigDecimal.ZERO)));
        var controls = new HashSet<String>();
        for (JsonNode control : data.path("controlValues")) {
            if (control.path("year").asInt() != year || !scenario(requestType, closingMonth, control.path("month").asInt()).equals(upper(control, "scenario"))) continue;
            int month = control.path("month").asInt();
            String key = text(control, "reportItemKey");
            if (key == null) key = text(control, "calculationCode");
            if (key == null || month < 1 || month > 12) continue;
            BigDecimal actual = controlAmount(control);
            BigDecimal expected = totals.getOrDefault(key + ":" + month, BigDecimal.ZERO);
            boolean unique = controls.add(key + ":" + month);
            if (actual == null || !unique || actual.subtract(expected).abs().compareTo(new BigDecimal("0.00001")) > 0) issue(diagnostics, "CONTROL_TOTAL_MISMATCH", "상세 합계와 답안지의 저장 금액이 일치하지 않거나 합계 근거가 미확정입니다.", sourceSheet(control), sourceCell(control), key, month);
            else if (actual.compareTo(expected) != 0) {
                issue(diagnostics, "CONTROL_CACHE_PRECISION", "답안지 저장 캐시에 0.00001원 이하의 미세 차이가 있습니다. 적재·보고 금액은 상세의 정확한 십진 합계를 사용합니다.", sourceSheet(control), sourceCell(control), key, month);
                var warning = diagnostics.getLast(); warning.put("severity", "WARNING"); warning.put("cachedAmount", actual.toPlainString()); warning.put("calculatedAmount", expected.toPlainString()); warning.put("difference", actual.subtract(expected).toPlainString());
            }
        }
        for (String key : List.of("REVENUE_TOTAL", "COST_TOTAL", "OP")) {
            for (int month = 1; month <= 12; month++) if (!controls.contains(key + ":" + month)) issue(diagnostics, "CONTROL_TOTAL_MISSING", "요청 시나리오의 월별 매출·비용·손익 합계 확인 근거가 필요합니다.", null, null, key, month);
            reports.put(key, object("key", key, "code", key, "name", switch (key) { case "REVENUE_TOTAL" -> "매출 합계"; case "COST_TOTAL" -> "영업비용 합계"; default -> "영업이익"; }, "kind", "DERIVED"));
        }
        for (var report : reports.values()) if (!"DERIVED".equals(report.get("kind"))) {
            for (int month = 1; month <= 12; month++) if (!controls.contains(report.get("key") + ":" + month)) issue(diagnostics, "REPORT_CONTROL_MISSING", "보고 항목의 월별 원본 합계 근거가 필요합니다.", null, null, report.get("key").toString(), month);
        }
        var reportValues = new ArrayList<Map<String, Object>>();
        boolean complete = diagnostics.stream().noneMatch(item -> "ERROR".equals(item.get("severity")));
        for (var report : reports.values()) for (int month = 1; month <= 12; month++) reportValues.add(object("reportKey", report.get("key"), "reportItemName", report.get("name"), "kind", report.get("kind"), "year", year, "month", month, "scenario", scenario(requestType, closingMonth, month), "valueStatus", complete ? "AVAILABLE" : "UNRESOLVED", "amount", complete ? totals.getOrDefault(report.get("key") + ":" + month, BigDecimal.ZERO).toPlainString() : null));
        Collections.sort(fingerprints);
        return object("reports", new ArrayList<>(reports.values()), "lines", lines, "diagnostics", diagnostics,
                "reportValues", reportValues, "fingerprint", hash(json(fingerprints).getBytes(StandardCharsets.UTF_8)));
    }

    /** 요청의 각 월에 반드시 존재해야 하는 시나리오를 정한다.
     * Args: type 요청 유형, closingMonth 월간 마감월, month 값의 월. Returns: PLAN/ACTUAL/ESTIMATE.
     * Raises: 없음. Side Effects: 없음; 월간 마감월 누락은 별도 진단으로 차단한다.
     */
    private String scenario(String type, Integer closingMonth, int month) {
        return "ANNUAL_PLAN".equals(type) ? "PLAN" : closingMonth != null && month <= closingMonth ? "ACTUAL" : "ESTIMATE";
    }

    /** 조직·보고 확인 상태를 읽는다. Args: status 상태. Returns: 확인 여부. Raises: 없음. Side Effects: 없음. */
    private boolean resolved(String status) { return "RESOLVED".equalsIgnoreCase(status); }

    /** 원본 도메인 명칭을 DB 분류로 맞춘다. Args: value 대문자 도메인. Returns: REVENUE/COST 또는 원문. Raises: 없음. Side Effects: 없음. */
    private String domain(String value) { return switch (value) { case "REVENUE_DETAIL" -> "REVENUE"; case "EXPENSE", "EXPENSE_DETAIL" -> "COST"; default -> value; }; }

    /** nullable scalar를 읽는다. Args: node JSON, field 필드. Returns: 문자열/null. Raises: 없음. Side Effects: 없음. */
    private String text(JsonNode node, String field) { JsonNode value = node.path(field); return value.isMissingNode() || value.isNull() || !value.isValueNode() ? null : value.asText(); }

    /** 상태를 대문자로 읽는다. Args: node JSON, field 필드. Returns: 대문자 또는 빈 문자열. Raises: 없음. Side Effects: 없음. */
    private String upper(JsonNode node, String field) { return Optional.ofNullable(text(node, field)).orElse("").toUpperCase(Locale.ROOT); }

    /** 원 단위 값을 손실 없이 DB 정밀도로 검증한다.
     * Args: value amount/valueStatus JSON. Returns: 유효 Decimal 또는 null.
     * Raises: 없음. Side Effects: 반올림하거나 빈칸을 0으로 바꾸지 않는다.
     */
    private BigDecimal amount(JsonNode value) {
        if (!Set.of("AVAILABLE", "CONFIRMED").contains(upper(value, "valueStatus")) || !value.path("amount").isTextual()) return null;
        try {
            BigDecimal number = new BigDecimal(value.path("amount").textValue());
            BigDecimal normalized = number.stripTrailingZeros();
            if ((long) normalized.precision() - normalized.scale() > 24 || normalized.scale() > 6) return null;
            BigDecimal fixed = number.setScale(6, RoundingMode.UNNECESSARY);
            return fixed.precision() <= 30 ? number : null;
        } catch (NumberFormatException | ArithmeticException exception) { return null; }
    }

    /** DB에 적재하지 않는 원본 합계 캐시의 미세 소수를 보존한다.
     * Args: value 원 단위로 확인된 캐시 대조값. Returns: 범위 내 Decimal/null.
     * Raises: 없음. Side Effects: 없음. 상세 정밀도와 별개로 16소수까지 비교하며 반올림하지 않는다.
     */
    private BigDecimal controlAmount(JsonNode value) {
        if (!Set.of("AVAILABLE", "CONFIRMED").contains(upper(value, "valueStatus")) || !value.path("amount").isTextual()) return null;
        try { BigDecimal number = new BigDecimal(value.path("amount").textValue()); BigDecimal normalized = number.stripTrailingZeros(); return (long) normalized.precision() - normalized.scale() <= 24 && normalized.scale() <= 16 ? number : null; }
        catch (NumberFormatException exception) { return null; }
    }

    /** 적재 문자 필드의 길이를 확인한다. Args: line 행 JSON. Returns: 허용 여부. Raises: 없음. Side Effects: 없음. */
    private boolean bounded(JsonNode line) {
        for (String field : List.of("detailId", "managementCategory", "actualBasis", "costType")) if (Optional.ofNullable(text(line, field)).orElse("").length() > 500) return false;
        for (String field : List.of("teamCode", "taskCode", "headquartersCode")) if (Optional.ofNullable(text(line, field)).orElse("").length() > 160) return false;
        return Optional.ofNullable(text(line, "detailRevision")).orElse("").length() <= 80 && Optional.ofNullable(text(line.path("account"), "code")).orElse("").length() <= 160 && Optional.ofNullable(text(line.path("account"), "name")).orElse("").length() <= 500;
    }

    /** 위치·수정차수와 무관한 금액 구성 지문을 만든다.
     * Args: line 원본 행, report 보고키, month 대상 월, amount 확정 원 금액.
     * Returns: 정규화 의미와 금액의 JSON. Raises: IllegalStateException 직렬화 오류.
     * Side Effects: 없음. 중복 개수는 호출자의 정렬 목록에서 보존한다.
     */
    private String fingerprint(JsonNode line, String report, int month, BigDecimal amount) {
        String description = io.skala.backend.review.ReviewComparisonService.normalizedDescription(text(line, "description"));
        return json(object("report", report, "month", month, "amount", amount.stripTrailingZeros().toPlainString(),
                "detailId", text(line, "detailId"), "account", line.path("account"), "team", text(line, "teamCode"), "task", text(line, "taskCode"), "description", description,
                "managementCategory", text(line, "managementCategory"), "actualBasis", text(line, "actualBasis"), "costType", text(line, "costType")));
    }

    /** 원본 업무키 이슈별 사유와 관측 필드 위치를 차단 진단으로 보존한다.
     * Args: items 진단 목록, line 원본 행, reportKey 보고키.
     * Returns: 없음. Raises: 없음.
     * Side Effects: 메모리 진단만 추가하며 코드·식별자·금액을 수정하거나 행을 선택하지 않는다.
     */
    private void businessIssues(List<Map<String, Object>> items, JsonNode line, String reportKey) {
        int before = items.size();
        for (JsonNode problem : line.path("businessValidation").path("issues")) {
            String field = Optional.ofNullable(text(problem, "field")).orElse("");
            String sourceField = switch (field) {
                case "detail_id" -> "detailId";
                case "account_code" -> "account";
                case "management_category" -> "managementCategory";
                default -> "";
            };
            String label = switch (field) {
                case "detail_id" -> "상세 식별자";
                case "account_code" -> "예산코드";
                case "management_category" -> "관리분류";
                default -> "업무 식별 정보";
            };
            JsonNode source = businessEvidence(line, sourceField, problem.path("provenance"));
            String reason = upper(problem, "code").endsWith("_MISSING")
                    ? "가 누락되었거나 확인되지 않습니다. "
                    : "가 원본의 식별자·코드표·집계 기준을 만족하지 않습니다. ";
            lineIssue(items, "LINE_BUSINESS_KEY_INVALID", label + reason + "원본을 확인·수정한 뒤 전체 파일을 다시 업로드해 주세요.", line, source, reportKey);
        }
        if (items.size() == before) lineIssue(items, "LINE_BUSINESS_KEY_INVALID", "상세 행의 계정·업무 식별 근거가 확정되지 않았습니다. 원본을 확인한 뒤 전체 파일을 다시 업로드해 주세요.", line, null, reportKey);
    }

    /** 업무 필드 근거 안에서만 이슈 출처를 선택하여 같은 행의 무관한 셀을 제외한다.
     * Args: line 원본 행, field 알려진 fieldSources 키, references 업무 이슈의 provenance.
     * Returns: 관련 필드와 일치하는 관측 셀, 또는 이슈 출처가 없을 때 유일한 필드 셀. 모호하면 null.
     * Raises: 없음. Side Effects: 메모리 조회만 수행하며 원본·진단 코드·금액을 변경하지 않는다.
     */
    private JsonNode businessEvidence(JsonNode line, String field, JsonNode references) {
        JsonNode first = fieldEvidence(line, field);
        if (first == null) return null;
        String sheet = text(line.path("source"), "sheet");
        int row = line.path("source").path("row").asInt();
        var cells = new HashSet<String>();
        for (JsonNode reference : line.path("fieldSources").path(field)) {
            String cell = text(reference, "cell");
            if (sheet.equals(text(reference, "sheet")) && cell != null && cell.matches("[A-Z]{1,3}" + row)) cells.add(cell);
        }
        for (JsonNode reference : references) {
            if (sheet.equals(text(reference, "sheet")) && cells.contains(text(reference, "cell"))) return reference;
        }
        return cells.size() == 1 ? first : null;
    }

    /** 코드표 참조를 제외하고 해당 원본 행에서 관측한 셀 근거만 찾는다.
     * Args: line 원본 시트·행, references provenance 또는 fieldSources의 근거 배열.
     * Returns: 같은 시트·행의 유효한 첫 셀 참조 또는 null. 알려지지 않은 좌표는 만들지 않는다.
     * Raises: 없음. Side Effects: 없음.
     */
    private JsonNode lineEvidence(JsonNode line, JsonNode references) {
        String sheet = text(line.path("source"), "sheet");
        int row = line.path("source").path("row").asInt();
        if (sheet == null || row < 1) return null;
        for (JsonNode reference : references) {
            String cell = text(reference, "cell");
            if (sheet.equals(text(reference, "sheet")) && cell != null && cell.matches("[A-Z]{1,3}" + row)) return reference;
        }
        return null;
    }

    /** 관련 업무 필드의 확인된 원본 행 셀을 우선순위대로 찾는다.
     * Args: line 원본 행, fields 조회할 fieldSources 키 목록.
     * Returns: 같은 원본 행의 첫 근거 또는 null. 금액 셀로 대체하지 않는다.
     * Raises: 없음. Side Effects: 없음.
     */
    private JsonNode fieldEvidence(JsonNode line, String... fields) {
        for (String field : fields) {
            JsonNode source = lineEvidence(line, line.path("fieldSources").path(field));
            if (source != null) return source;
        }
        return null;
    }

    /** 관측 필드 위치가 없으면 셀을 비운 채 원본 시트의 차단 사유를 남긴다.
     * Args: items 진단 목록, code/message 차단 사유, line 원본 행, source 확인된 같은 행 참조/null, reportKey 보고키.
     * Returns: 없음. Raises: 없음. Side Effects: 메모리 진단 목록만 갱신한다.
     */
    private void lineIssue(List<Map<String, Object>> items, String code, String message, JsonNode line, JsonNode source, String reportKey) {
        issue(items, code, message, text(line.path("source"), "sheet"), source == null ? null : text(source, "cell"), reportKey, null);
    }

    /** 첫 원본 금액 셀을 찾는다. Args: line 행. Returns: 셀/null. Raises: 없음. Side Effects: 없음. */
    private String firstSource(JsonNode line) { return line.path("values").isEmpty() ? null : text(line.path("values").get(0), "sourceCell"); }

    /** 합계의 원본 시트를 읽는다. Args: node 합계. Returns: 시트/null. Raises: 없음. Side Effects: 없음. */
    private String sourceSheet(JsonNode node) { return node.hasNonNull("sourceSheet") ? text(node, "sourceSheet") : text(node.path("source"), "sheet"); }

    /** 합계의 원본 셀을 읽는다. Args: node 합계. Returns: 셀/null. Raises: 없음. Side Effects: 없음. */
    private String sourceCell(JsonNode node) { return node.hasNonNull("sourceCell") ? text(node, "sourceCell") : text(node.path("source"), "cell"); }

    /** 원본 위치를 가진 차단 진단을 추가한다.
     * Args: items 대상, code 코드, message 안내, sheet/cell 위치, reportKey 보고키, month 월.
     * Returns: 없음. Raises: 없음. Side Effects: 전달된 메모리 진단 목록에 추가.
     */
    private void issue(List<Map<String, Object>> items, String code, String message, String sheet, String cell, String reportKey, Integer month) { items.add(object("code", code, "severity", "ERROR", "stage", "COMPLETENESS", "message", message, "source", sheet == null ? null : object("sheet", sheet, "cell", cell), "reportKey", reportKey, "month", month)); }

    /** 내부 구조를 JSON으로 직렬화한다. Args: value 내부값. Returns: JSON. Raises: IllegalStateException 직렬화 오류. Side Effects: 없음. */
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("검증 자료 직렬화 실패", exception); } }
}
