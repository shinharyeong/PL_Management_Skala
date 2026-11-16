package io.skala.backend.submission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 실제 검증기로 원본 후보와 적재 가능한 전체 PLAN의 경계를 메모리에서 확인한다. */
class FinancialValidationRulesTest {
    private static final String SOURCE_HASH = "a".repeat(64);
    private static final String REVENUE_KEY = "revenue:service";
    private static final String COST_KEY = "expense:salary";
    private final ObjectMapper mapper = new ObjectMapper();
    private final FinancialValidationRules rules = new FinancialValidationRules(mapper);

    /** 0원과 소수 여섯 자리를 보존하고 원본 JSON을 변경하지 않는다.
     * Args: 없음. Returns: 없음. Raises: AssertionError 검증 결과 불일치.
     * Side Effects: 합성 메모리 JSON 생성·검증만 수행; DB·파일 접근 없음.
     */
    @Test
    void completePlanKeepsExplicitZeroPrecisionAndInput() {
        ObjectNode data = fixture();
        JsonNode before = data.deepCopy();
        JsonNode result = evaluate(data);
        assertThat(result.path("diagnostics").isEmpty()).isTrue();
        assertThat(result.path("lines").size()).isEqualTo(2);
        assertThat(result.path("lines").get(0).path("values").size()).isEqualTo(12);
        assertThat(result.path("lines").get(0).path("values").get(0).path("amount").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.path("lines").get(0).path("values").get(1).path("amount").decimalValue()).isEqualByComparingTo("100.123456");
        assertThat(result.path("reportValues").size()).isEqualTo(60);
        assertThat(result.path("reportValues").findValuesAsText("valueStatus")).containsOnly("AVAILABLE");
        assertThat(data).isEqualTo(before);
    }

    /** 금액 누락과 월 슬롯 삭제는 실제 0원과 구분하여 전체 보고값을 차단한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError 누락을 숫자로 처리한 경우.
     * Side Effects: 각 합성 JSON 복사본만 변경한다.
     */
    @Test
    void nullAmountAndMissingMonthAreNeverFilledWithZero() {
        for (boolean removeSlot : List.of(false, true)) {
            ObjectNode data = fixture();
            ArrayNode values = (ArrayNode) data.path("lines").get(0).path("values");
            if (removeSlot) values.remove(11);
            else ((ObjectNode) values.get(11)).putNull("amount");
            JsonNode result = evaluate(data);
            assertThat(result.path("diagnostics").findValuesAsText("code")).contains("PLAN_AMOUNT_UNRESOLVED");
            assertThat(result.path("lines").size()).isEqualTo(1);
            assertUnavailable(result);
        }
    }

    /** 파일 소유 조직과 각 상세 조직을 별도로 검사한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError 조직 불일치 허용.
     * Side Effects: 합성 JSON에서 조직 코드만 변경한다.
     */
    @Test
    void workbookOwnerAndLineOrganizationMustBothMatch() {
        ObjectNode wrongOwner = fixture();
        ((ObjectNode) wrongOwner.path("owner")).put("code", "D200");
        JsonNode ownerResult = evaluate(wrongOwner);
        assertThat(ownerResult.path("diagnostics").findValuesAsText("code")).contains("SUBMISSION_ORGANIZATION_MISMATCH");
        assertUnavailable(ownerResult);
        ObjectNode wrongLine = fixture();
        ((ObjectNode) wrongLine.path("lines").get(1).path("organization")).put("code", "D200");
        JsonNode lineResult = evaluate(wrongLine);
        assertThat(lineResult.path("diagnostics").findValuesAsText("code")).contains("LINE_ORGANIZATION_MISMATCH");
        assertThat(lineResult.path("lines").size()).isEqualTo(1);
        assertUnavailable(lineResult);
    }

    /** 답안지 값 불일치와 말단·전체 합계 근거 누락을 각각 차단한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError 대조 근거 오류 미검출.
     * Side Effects: 합성 합계 JSON만 변경하며 원본 수식을 계산하지 않는다.
     */
    @Test
    void mismatchedAndMissingLeafOrTotalControlsBlockReports() {
        ObjectNode mismatch = fixture();
        ObjectNode control = (ObjectNode) mismatch.path("controlValues").get(9);
        control.put("amount", "61");
        JsonNode result = evaluate(mismatch);
        assertThat(result.path("diagnostics").findValuesAsText("code")).contains("CONTROL_TOTAL_MISMATCH");
        JsonNode diagnostic = result.path("diagnostics").get(0);
        assertThat(diagnostic.path("source").path("sheet").asText()).isEqualTo("답안지");
        assertThat(diagnostic.path("source").path("cell").asText()).isEqualTo(control.path("source").path("cell").asText());
        assertThat(diagnostic.path("month").asInt()).isEqualTo(2);
        assertUnavailable(result);
        ObjectNode missingLeaf = fixture();
        ((ArrayNode) missingLeaf.path("controlValues")).remove(0);
        assertThat(evaluate(missingLeaf).path("diagnostics").findValuesAsText("code")).contains("REPORT_CONTROL_MISSING");
        ObjectNode missingTotal = fixture();
        ((ArrayNode) missingTotal.path("controlValues")).remove(4);
        assertThat(evaluate(missingTotal).path("diagnostics").findValuesAsText("code")).contains("CONTROL_TOTAL_MISSING");
    }

    /** 합계 캐시의 미세 차이와 경계값은 경고로 보존하고 적재값·지문을 바꾸지 않는다.
     * Args: 없음. Returns: 없음. Raises: AssertionError 경계·근거 보존 불일치.
     * Side Effects: 합성 OP 캐시만 변경하며 금액을 보정하지 않는다.
     */
    @Test
    void cachePrecisionWarningPreservesEvidenceAndExactBoundary() {
        JsonNode baseline = evaluate(fixture());
        for (String delta : List.of("0.000001", "-0.000001", "0.00001", "-0.00001")) {
            ObjectNode data = fixture();
            String cached = new BigDecimal("60").add(new BigDecimal(delta)).toPlainString();
            ((ObjectNode) data.path("controlValues").get(9)).put("amount", cached);
            JsonNode original = data.deepCopy();
            JsonNode result = evaluate(data);
            assertThat(result.path("diagnostics").size()).isEqualTo(1);
            JsonNode warning = result.path("diagnostics").get(0);
            assertThat(warning.path("code").asText()).isEqualTo("CONTROL_CACHE_PRECISION");
            assertThat(warning.path("severity").asText()).isEqualTo("WARNING");
            assertThat(warning.path("cachedAmount").asText()).isEqualTo(cached);
            assertThat(new BigDecimal(warning.path("calculatedAmount").asText())).isEqualByComparingTo("60");
            assertThat(new BigDecimal(warning.path("difference").asText())).isEqualByComparingTo(delta);
            assertThat(warning.path("source").path("cell").asText()).isEqualTo("A10");
            assertThat(result.path("reportValues").findValuesAsText("valueStatus")).containsOnly("AVAILABLE");
            assertThat(result.path("reportValues")).isEqualTo(baseline.path("reportValues"));
            assertThat(result.path("lines")).isEqualTo(baseline.path("lines"));
            assertThat(result.path("fingerprint")).isEqualTo(baseline.path("fingerprint"));
            assertThat(data).isEqualTo(original);
        }
    }

    /** 허용 차이 초과와 실제 1원 차이는 양수·음수 모두 업무 오류로 차단한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError 초과 차이 허용.
     * Side Effects: 합성 OP 캐시만 변경한다.
     */
    @Test
    void cacheDifferenceAboveBoundaryOrOneWonBlocksReports() {
        for (String delta : List.of("0.0000101", "-0.0000101", "1", "-1")) {
            ObjectNode data = fixture();
            ((ObjectNode) data.path("controlValues").get(9)).put("amount", new BigDecimal("60").add(new BigDecimal(delta)).toPlainString());
            JsonNode result = evaluate(data);
            assertThat(result.path("diagnostics").findValuesAsText("code")).contains("CONTROL_TOTAL_MISMATCH");
            assertThat(result.path("diagnostics").findValuesAsText("severity")).contains("ERROR");
            assertUnavailable(result);
        }
    }

    /** 캐시 허용치를 상세 금액 보정·변경 감지 생략·DB 정밀도 완화에 적용하지 않는다.
     * Args: 없음. Returns: 없음. Raises: AssertionError 상세 금액 규칙 우회.
     * Side Effects: 합성 상세 금액만 변경하고 원래 합계 캐시는 그대로 둔다.
     */
    @Test
    void cacheToleranceNeverRoundsFinancialValuesOrHidesTheirChanges() {
        JsonNode baseline = evaluate(fixture());
        ObjectNode changed = fixture();
        ((ObjectNode) changed.path("lines").get(0).path("values").get(1)).put("amount", "100.123457");
        JsonNode result = evaluate(changed);
        assertThat(result.path("diagnostics").findValuesAsText("code")).containsOnly("CONTROL_CACHE_PRECISION");
        assertThat(result.path("lines").get(0).path("values").get(1).path("amount").decimalValue()).isEqualByComparingTo("100.123457");
        assertThat(result.path("fingerprint")).isNotEqualTo(baseline.path("fingerprint"));
        ObjectNode overPrecision = fixture();
        ((ObjectNode) overPrecision.path("lines").get(0).path("values").get(1)).put("amount", "100.1234561");
        JsonNode blocked = evaluate(overPrecision);
        assertThat(blocked.path("diagnostics").findValuesAsText("code")).contains("PLAN_AMOUNT_UNRESOLVED");
        assertUnavailable(blocked);
    }

    /** DB 정밀도를 넘거나 확정되지 않은 값을 반올림·숫자 추정 없이 거부한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError 범위·상태 검증 누락.
     * Side Effects: 합성 금액 JSON만 변경한다.
     */
    @Test
    void overPrecisionOverflowNonDecimalAndUnconfirmedAmountsAreRejected() {
        for (String amount : List.of("0.0000001", "1000000000000000000000000", "NaN", "1,000")) {
            ObjectNode data = fixture();
            ((ObjectNode) data.path("lines").get(0).path("values").get(1)).put("amount", amount);
            JsonNode result = evaluate(data);
            assertThat(result.path("diagnostics").findValuesAsText("code")).contains("PLAN_AMOUNT_UNRESOLVED");
            assertUnavailable(result);
        }
        ObjectNode numericJson = fixture();
        ((ObjectNode) numericJson.path("lines").get(0).path("values").get(1)).put("amount", 100);
        assertThat(evaluate(numericJson).path("diagnostics").findValuesAsText("code")).contains("PLAN_AMOUNT_UNRESOLVED");
        ObjectNode uncertain = fixture();
        ((ObjectNode) uncertain.path("lines").get(0).path("values").get(1)).put("valueStatus", "UNRESOLVED");
        assertThat(evaluate(uncertain).path("diagnostics").findValuesAsText("code")).contains("PLAN_AMOUNT_UNRESOLVED");
    }

    /** 소수 여섯 자리·정수 스물네 자리의 경계값을 손실 없이 받는다.
     * Args: 없음. Returns: 없음. Raises: AssertionError 유효 경계값 거부.
     * Side Effects: 합성 월 금액과 대응 합계만 변경한다.
     */
    @Test
    void maximumExactDecimalCanBeValidatedWithoutRounding() {
        ObjectNode data = fixture();
        String maximum = "999999999999999999999999.999999";
        ((ObjectNode) data.path("lines").get(0).path("values").get(0)).put("amount", maximum);
        for (int index : List.of(0, 2, 4)) ((ObjectNode) data.path("controlValues").get(index)).put("amount", maximum);
        JsonNode result = evaluate(data);
        assertThat(result.path("diagnostics").isEmpty()).isTrue();
        assertThat(result.path("lines").get(0).path("values").get(0).path("amount").decimalValue()).isEqualByComparingTo(maximum);
    }

    /** 12개월 헤더와 매출·비용 두 영역의 존재를 값 검사와 별도로 요구한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError 시트·헤더 범위 미검출.
     * Side Effects: 합성 헤더 JSON만 변경한다.
     */
    @Test
    void missingMonthHeaderAndMissingExpenseSheetBlockCompleteness() {
        ObjectNode missingMonth = fixture();
        ((ArrayNode) missingMonth.path("sheets").get(0).path("periodColumns")).remove(11);
        assertThat(evaluate(missingMonth).path("diagnostics").findValuesAsText("code")).contains("PLAN_MONTH_HEADERS_INCOMPLETE");
        ObjectNode unresolvedMonth = fixture();
        ((ObjectNode) unresolvedMonth.path("sheets").get(0).path("periodColumns").get(0)).put("status", "UNRESOLVED");
        assertThat(evaluate(unresolvedMonth).path("diagnostics").findValuesAsText("code")).contains("PLAN_MONTH_HEADERS_INCOMPLETE");
        ObjectNode missingSheet = fixture();
        ((ArrayNode) missingSheet.path("sheets")).remove(1);
        assertThat(evaluate(missingSheet).path("diagnostics").findValuesAsText("code")).contains("FINANCIAL_SHEETS_INCOMPLETE");
    }

    /** 중복 상세 ID·원본행·월 값을 조용히 마지막 값으로 선택하지 않는다.
     * Args: 없음. Returns: 없음. Raises: AssertionError 중복 미검출.
     * Side Effects: 합성 행·월을 메모리에서 복제한다.
     */
    @Test
    void duplicateDetailIdSourceRowAndMonthAreRejected() {
        ObjectNode duplicateId = fixture();
        ObjectNode copied = duplicateId.path("lines").get(0).deepCopy();
        ((ObjectNode) copied.path("source")).put("row", 8);
        ((ArrayNode) duplicateId.path("lines")).add(copied);
        assertThat(evaluate(duplicateId).path("diagnostics").findValuesAsText("code")).contains("DUPLICATE_DETAIL_ID");
        ObjectNode duplicateRow = fixture();
        ObjectNode sameRow = duplicateRow.path("lines").get(0).deepCopy();
        sameRow.put("detailId", "another-id");
        ((ArrayNode) duplicateRow.path("lines")).add(sameRow);
        assertThat(evaluate(duplicateRow).path("diagnostics").findValuesAsText("code")).contains("SOURCE_ROW_INVALID");
        ObjectNode duplicateMonth = fixture();
        ArrayNode values = (ArrayNode) duplicateMonth.path("lines").get(0).path("values");
        values.add(values.get(0).deepCopy());
        assertThat(evaluate(duplicateMonth).path("diagnostics").findValuesAsText("code")).contains("DUPLICATE_FINANCIAL_MONTH");
    }

    /** 구 어댑터·다른 원본 해시·미확정 보고 연결을 현재 적재 근거로 채택하지 않는다.
     * Args: 없음. Returns: 없음. Raises: AssertionError 근거 검증 누락.
     * Side Effects: 합성 계약 메타데이터만 변경한다.
     */
    @Test
    void legacyAdapterHashMismatchAndUnresolvedReportMappingAreBlocked() {
        ObjectNode legacy = fixture();
        legacy.remove("adapterVersion");
        JsonNode old = evaluate(legacy);
        assertThat(old.path("diagnostics").findValuesAsText("code")).containsExactly("SUBMISSION_ADAPTER_REQUIRED");
        assertThat(old.path("lines").isEmpty()).isTrue();
        assertThat(old.path("reportValues").isEmpty()).isTrue();
        ObjectNode wrongHash = fixture();
        wrongHash.put("sourceSha256", "b".repeat(64));
        assertThat(evaluate(wrongHash).path("diagnostics").findValuesAsText("code")).contains("SOURCE_HASH_MISMATCH");
        ObjectNode unresolved = fixture();
        ((ObjectNode) unresolved.path("lines").get(0)).put("reportMappingStatus", "UNRESOLVED");
        assertThat(evaluate(unresolved).path("diagnostics").findValuesAsText("code")).contains("LINE_REPORT_MAPPING_UNRESOLVED");
    }

    /** 행 이동·수정차수만 바뀌면 지문을 유지하고 실제 금액 변화는 구별한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError 의미 비교 불일치.
     * Side Effects: 합성 행 위치·설명·금액만 메모리에서 변경한다.
     */
    @Test
    void fingerprintIgnoresLocationsAndRevisionButKeepsAmounts() {
        ObjectNode original = fixture();
        String fingerprint = evaluate(original).path("fingerprint").asText();
        ObjectNode moved = fixture();
        ObjectNode line = (ObjectNode) moved.path("lines").get(0);
        line.put("detailRevision", "2").put("description", "합성 거래 [ID:revenue-row; 수정:2]");
        ((ObjectNode) line.path("source")).put("row", 20);
        for (JsonNode value : line.path("values")) ((ObjectNode) value)
                .put("sourceCell", String.valueOf((char) ('F' + value.path("month").asInt() - 1)) + "20")
                .put("sourceRecordId", "moved-" + value.path("month").asInt());
        JsonNode movedResult = evaluate(moved);
        assertThat(movedResult.path("diagnostics").isEmpty()).isTrue();
        assertThat(movedResult.path("fingerprint").asText()).isEqualTo(fingerprint);
        ((ObjectNode) line.path("values").get(0)).put("amount", "1");
        for (int index : List.of(0, 2, 4)) ((ObjectNode) moved.path("controlValues").get(index)).put("amount", "1");
        assertThat(evaluate(moved).path("fingerprint").asText()).isNotEqualTo(fingerprint);
    }

    /** 다른 통합 테스트도 쓸 수 있는 두 행·12개월 PLAN 근거를 만든다.
     * Args: 없음. Returns: 2031년 D100, 원본 해시 a 64자의 독립 ObjectNode.
     * Raises: 없음. Side Effects: 메모리 JSON만 생성; 파일·DB·원본 접근 없음.
     */
    static ObjectNode fixture() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode data = mapper.createObjectNode().put("adapterVersion", "financial-submission/1.0").put("sourceSha256", SOURCE_HASH);
        data.putObject("owner").put("code", "D100").put("status", "RESOLVED");
        data.putArray("diagnostics");
        ArrayNode reports = data.putArray("reportItems"), sheets = data.putArray("sheets"), lines = data.putArray("lines");
        for (int index = 0; index < 2; index++) {
            String domain = index == 0 ? "REVENUE" : "EXPENSE";
            String key = index == 0 ? REVENUE_KEY : COST_KEY;
            String name = index == 0 ? "서비스 매출" : "급여";
            String sheetName = index == 0 ? "매출 상세" : "비용 상세";
            int row = index + 6;
            reports.addObject().put("key", key).put("name", name).put("kind", index == 0 ? "REVENUE" : "COST").put("status", "RESOLVED");
            ObjectNode sheet = sheets.addObject().put("name", sheetName).put("domain", domain);
            ArrayNode columns = sheet.putArray("periodColumns");
            ObjectNode line = lines.addObject().put("domain", domain).put("reportItemKey", key)
                    .put("reportMappingStatus", "RESOLVED").put("detailId", index == 0 ? "revenue-row" : "cost-row")
                    .put("detailRevision", "1").put("description", index == 0 ? "합성 거래 [ID:revenue-row; 수정:1]" : "합성 비용")
                    .put("teamCode", "TEAM1").put("taskCode", "TASK1");
            line.putObject("source").put("sheet", sheetName).put("row", row);
            line.putObject("organization").put("code", "D100").put("status", "RESOLVED");
            line.putObject("businessValidation").put("status", "VALID");
            line.putObject("account").put("code", index == 0 ? "3100" : "60100").put("name", name);
            if (index == 0) line.put("managementCategory", name);
            else line.put("actualBasis", name).put("costType", "인건비성");
            ArrayNode values = line.putArray("values");
            for (int month = 1; month <= 12; month++) {
                columns.addObject().put("year", 2031).put("month", month).put("scenario", "PLAN").put("status", "RESOLVED");
                values.addObject().put("year", 2031).put("month", month).put("scenario", "PLAN")
                        .put("amount", month == 1 ? "0" : index == 0 ? "100.123456" : "40.123456")
                        .put("valueStatus", "AVAILABLE").put("sourceCell", String.valueOf((char) ('F' + month - 1)) + row)
                        .put("sourceRecordId", "fixture:" + domain + ":" + month);
            }
        }
        ArrayNode controls = data.putArray("controlValues");
        for (int month = 1; month <= 12; month++) {
            for (String key : List.of(REVENUE_KEY, COST_KEY, "REVENUE_TOTAL", "COST_TOTAL", "OP")) {
                String amount = month == 1 ? "0" : key.equals("OP") ? "60" : key.equals(REVENUE_KEY) || key.equals("REVENUE_TOTAL") ? "100.123456" : "40.123456";
                ObjectNode control = controls.addObject().put("year", 2031).put("month", month).put("scenario", "PLAN")
                        .put("amount", amount).put("valueStatus", "AVAILABLE");
                control.put(key.equals(REVENUE_KEY) || key.equals(COST_KEY) ? "reportItemKey" : "calculationCode", key);
                control.putObject("source").put("sheet", "답안지").put("cell", "A" + controls.size());
            }
        }
        return data;
    }

    /** 공통 합성 제출 문맥에서 실제 검증 결과를 JSON으로 읽는다.
     * Args: data 어댑터 JSON. Returns: 순수 검증 결과 JSON.
     * Raises: 내부 JSON 변환 오류. Side Effects: 메모리 계산만 수행.
     */
    private JsonNode evaluate(ObjectNode data) {
        return mapper.valueToTree(rules.evaluate(data, SOURCE_HASH, "D100", 2031, "ANNUAL_PLAN"));
    }

    /** 검증 오류가 있으면 부분 합계를 AVAILABLE 숫자로 제공하지 않는지 확인한다.
     * Args: result 검증 결과 JSON. Returns: 없음. Raises: AssertionError 부분 집계 노출.
     * Side Effects: 없음.
     */
    private void assertUnavailable(JsonNode result) {
        assertThat(result.path("reportValues").findValuesAsText("valueStatus")).containsOnly("UNRESOLVED");
        for (JsonNode value : result.path("reportValues")) assertThat(value.path("amount").isNull()).isTrue();
    }
}
