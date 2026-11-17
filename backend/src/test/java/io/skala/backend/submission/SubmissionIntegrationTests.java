package io.skala.backend.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.parser.ParsingWorker;
import io.skala.backend.parser.PythonParserClient;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** 메모리 H2·실제 Python 프로세스로 접수부터 결과 조회까지 검증한다. */
@SpringBootTest(properties={
    "app.local-auth.planner-email=planner@example.test",
    "app.local-auth.planner-token=planner-test-token-at-least-32-characters",
    "app.local-auth.organization-email=manager@example.test",
    "app.local-auth.organization-token=manager-test-token-at-least-32-characters"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
class SubmissionIntegrationTests {
    private static final String PLANNER = "Bearer planner-test-token-at-least-32-characters";
    private static final String MANAGER = "Bearer manager-test-token-at-least-32-characters";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PythonParserClient parser;
    @Autowired TransactionTemplate transactions;
    @Autowired io.skala.backend.review.ReviewComparisonService comparisons;

    /** 각 테스트의 업무 자료를 메모리 DB에만 준비한다.
     * Args: 없음. Returns: 없음. Raises: DB 오류. Side Effects: H2 행 초기화, 파일 쓰기 없음.
     */
    @BeforeEach
    void prepare() {
        jdbc.sql("UPDATE request_target SET confirmed_plan_submission_id=NULL").update();
        for (String table : new String[]{"review_workflow_event", "review_explanation", "gap_comparison", "review_item", "gap_threshold", "master_source", "master_version", "annual_master_source", "annual_master", "financial_value", "financial_line", "submission_validation", "account_item", "report_item", "idempotency_record", "parsing_job", "submission", "request_target",
                "submission_request", "annual_plan", "app_user", "organization", "stored_object"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("INSERT INTO organization(id,code,name,active) VALUES (1,'D100','합성조직',TRUE),(2,'D200','다른조직',TRUE)").update();
        jdbc.sql("""
                INSERT INTO app_user(id,email,name,role,organization_id,active) VALUES
                (10,'planner@example.test','기획','PLANNER',NULL,TRUE),
                (11,'manager@example.test','담당','ORG_MANAGER',1,TRUE),
                (12,'other@example.test','다른 담당','ORG_MANAGER',2,TRUE)
                """).update();
    }

    /** 실제 파서의 십진 정밀도 보존과 미검증 차단·원본 동일성을 확인한다.
     * Args: 없음. Returns: 없음. Raises: 검증/실행 오류.
     * Side Effects: 메모리 H2 및 stdin Python 프로세스, fixture 파일 없음.
     */
    @Test
    void realParserRoundTripPreservesOriginalAndNeverApproves() throws Exception {
        String requestId = createRequest();
        byte[] original = workbook();
        JsonNode accepted = upload(requestId, "upload-real-0001", original);
        String id = accepted.path("id").asText();
        assertThat(accepted.path("parsing").path("status").asText()).isEqualTo("QUEUED");
        new ParsingWorker(jdbc, transactions, parser, mapper, 90).runNext();
        var response = mvc.perform(get("/api/v1/submissions/" + id).header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.readiness.canSubmit").value(false))
                .andExpect(jsonPath("$.readiness.validationState").value("NOT_RUN"))
                .andExpect(jsonPath("$.parsing.status").value("SUCCEEDED")).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("source_object_key", "result_object_key", "payload");
        byte[] result = jdbc.sql("""
                SELECT o.payload FROM stored_object o JOIN parsing_job j ON j.result_object_key=o.object_key
                WHERE j.submission_id=?
                """).param(Long.parseLong(id)).query(byte[].class).single();
        JsonNode parsed = mapper.readTree(result);
        assertThat(parsed.path("records").findValuesAsText("normalized_value")).contains("123.4500", "0");
        mvc.perform(get("/api/v1/submissions/" + id + "/file").header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(content().bytes(original));
        mvc.perform(get("/api/v1/submissions/" + id + "/diagnostics").header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        JsonNode replay = mapper.readTree(mvc.perform(multipart("/api/v1/submission-requests/" + requestId + "/submissions")
                .file(new MockMultipartFile("file", "D100_2031-PLAN.xlsx", "application/octet-stream", original))
                .header("Authorization", MANAGER).header("Idempotency-Key", "upload-real-0001"))
                .andExpect(status().isAccepted()).andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn().getResponse().getContentAsString());
        assertThat(replay.path("id").asText()).isEqualTo(id);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submission").query(Long.class).single()).isEqualTo(1);
    }

    /** 파일명·대리 업로드·미정의 입력 및 금액 제출 경계를 확인한다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류. Side Effects: 메모리 DB.
     */
    @Test
    void uploadRejectsWrongRoleFilenameAndExtraParts() throws Exception {
        String requestId = createRequest();
        var file = new MockMultipartFile("file", "D100_2031-PLAN.xlsx", "application/octet-stream", workbook());
        mvc.perform(multipart("/api/v1/submission-requests/" + requestId + "/submissions").file(file)
                .header("Authorization", PLANNER).header("Idempotency-Key", "wrong-role-0001")).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/submission-requests/" + requestId + "/submissions")
                .file(new MockMultipartFile("file", "D200_2031-PLAN.xlsx", "application/octet-stream", workbook()))
                .header("Authorization", MANAGER).header("Idempotency-Key", "wrong-name-0001")).andExpect(status().isUnprocessableEntity());
        mvc.perform(multipart("/api/v1/submission-requests/" + requestId + "/submissions").file(file).param("organizationId", "2")
                .header("Authorization", MANAGER).header("Idempotency-Key", "extra-part-0001")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/submissions?organizationId=2").header("Authorization", MANAGER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/submissions?unknown=value").header("Authorization", MANAGER)).andExpect(status().isBadRequest());
    }

    /** 다른 조직의 존재·파일·진단이 개별 조회로 드러나지 않음을 확인한다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류. Side Effects: 메모리 DB.
     */
    @Test
    void anotherOrganizationsResourcesAreHidden() throws Exception {
        String requestId = createRequest();
        String id = upload(requestId, "cross-org-0001", workbook()).path("id").asText();
        jdbc.sql("UPDATE submission SET organization_id=2,uploaded_by=12 WHERE id=?").param(Long.parseLong(id)).update();
        for (String suffix : new String[]{"", "/file", "/diagnostics"}) {
            mvc.perform(get("/api/v1/submissions/" + id + suffix).header("Authorization", MANAGER))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
        mvc.perform(get("/api/v1/submission-requests/" + requestId).header("Authorization", MANAGER))
                .andExpect(jsonPath("$.targetCount").value(1)).andExpect(jsonPath("$.targets[0].organization.id").value("1"));
    }

    /** 처리 중단 복구와 토큰 검사 후 재시도를 검증한다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류. Side Effects: 메모리 DB·Python 실행.
     */
    @Test
    void interruptedJobCanRetryAndOldTokenCannotMutate() throws Exception {
        String id = upload(createRequest(), "retry-upload-01", workbook()).path("id").asText();
        jdbc.sql("UPDATE parsing_job SET status='RUNNING',started_at=TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00' WHERE submission_id=?")
                .param(Long.parseLong(id)).update();
        var worker = new ParsingWorker(jdbc, transactions, parser, mapper, 90);
        worker.runNext();
        JsonNode failed = mapper.readTree(mvc.perform(get("/api/v1/submissions/" + id).header("Authorization", MANAGER))
                .andExpect(jsonPath("$.parsing.errorCode").value("PARSER_INTERRUPTED")).andReturn().getResponse().getContentAsString());
        String body = mapper.writeValueAsString(ApiData.object("expectedStateToken", failed.path("stateToken").asText(), "expectedAttemptNo", 1));
        mvc.perform(post("/api/v1/submissions/" + id + "/parsing-retries").header("Authorization", MANAGER)
                .header("Idempotency-Key", "retry-cmd-0001").contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.parsing.attemptNo").value(2));
        mvc.perform(post("/api/v1/submissions/" + id + "/parsing-retries").header("Authorization", MANAGER)
                .header("Idempotency-Key", "retry-cmd-0002").contentType("application/json").content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_CHANGED"));
        worker.runNext();
        mvc.perform(get("/api/v1/submissions/" + id).header("Authorization", MANAGER))
                .andExpect(jsonPath("$.parsing.status").value("SUCCEEDED")).andExpect(jsonPath("$.parsing.attemptNo").value(2));
    }

    /** 월간 요청과 알 수 없는 쓰기 필드를 차단한다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류. Side Effects: 메모리 DB.
     */
    @Test
    void monthlyRequestRequiresConfirmedAnnualPlanAndUnknownFieldsFail() throws Exception {
        mvc.perform(post("/api/v1/submission-requests").header("Authorization", PLANNER).header("Idempotency-Key", "monthly-0001")
                .contentType("application/json").content("{\"type\":\"MONTHLY_ACTUAL\",\"year\":2031,\"month\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ANNUAL_PLAN_NOT_CONFIRMED"));
        mvc.perform(post("/api/v1/submission-requests").header("Authorization", PLANNER).header("Idempotency-Key", "unknown-0001")
                .contentType("application/json").content("{\"type\":\"ANNUAL_PLAN\",\"year\":2031,\"unknownOrganizationIds\":[1]}"))
                .andExpect(status().isBadRequest());
    }

    /** 저장 바이트 변조를 원본 해시로 차단하고 재파싱을 허용하지 않는다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류. Side Effects: 메모리 DB.
     */
    @Test
    void detectsStoredSourceCorruption() throws Exception {
        String id = upload(createRequest(), "corrupt-source-01", workbook()).path("id").asText();
        jdbc.sql("UPDATE stored_object SET payload=? WHERE object_key=(SELECT source_object_key FROM submission WHERE id=?)")
                .params(new byte[]{1, 2, 3}, Long.parseLong(id)).update();
        new ParsingWorker(jdbc, transactions, parser, mapper, 90).runNext();
        mvc.perform(get("/api/v1/submissions/" + id).header("Authorization", MANAGER))
                .andExpect(jsonPath("$.parsing.status").value("FAILED"))
                .andExpect(jsonPath("$.parsing.errorCode").value("SOURCE_INTEGRITY_MISMATCH"))
                .andExpect(jsonPath("$.parsing.retryAllowed").value(false));
    }

    /** 구시도 작업의 늦은 완료가 새 활성 시도에 반영되지 않음을 확인한다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류. Side Effects: 메모리 DB와 파서 경계 mock.
     */
    @Test
    void lateAttemptCannotOverwriteNewAttempt() throws Exception {
        String id = upload(createRequest(), "late-attempt-01", workbook()).path("id").asText();
        var controlledParser = org.mockito.Mockito.mock(PythonParserClient.class);
        org.mockito.Mockito.when(controlledParser.parse(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    jdbc.sql("UPDATE parsing_job SET attempt_no=2,status='RUNNING' WHERE submission_id=?").param(Long.parseLong(id)).update();
                    return parser.failure("PARSER_TIMEOUT");
                });
        new ParsingWorker(jdbc, transactions, controlledParser, mapper, 90).runNext();
        mvc.perform(get("/api/v1/submissions/" + id).header("Authorization", MANAGER))
                .andExpect(jsonPath("$.parsing.status").value("RUNNING")).andExpect(jsonPath("$.parsing.attemptNo").value(2))
                .andExpect(jsonPath("$.parsing.errorCode").doesNotExist());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM stored_object WHERE object_key LIKE 'result/%'").query(Long.class).single()).isZero();
    }

    /** 실제 HTTP 명령과 H2에서 적재·최종 제출·기획 조회·원본 근거·멱등을 연결한다.
     * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 adapter와 H2만 사용; 실제 파서는 별도 원본 시험으로 대조한다.
     */
    @Test
    void validationPersistsFactsAndFinalSubmissionIsVisibleToPlanner() throws Exception {
        String request = createRequest();
        String id = upload(request, "financial-upload-01", workbook()).path("id").asText();
        seedAdapter(id, false);
        mvc.perform(get("/api/v1/submissions/" + id + "/financial-values").header("Authorization", MANAGER))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VALIDATION_REQUIRED"));
        JsonNode validated = workflow(id, "validation", "validate-financial-01");
        assertThat(validated.path("submission").path("readiness").path("canSubmit").asBoolean()).isTrue();
        mvc.perform(get("/api/v1/submissions/" + id + "/financial-values?year=2031&month=1").header("Authorization", PLANNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.lineCount").value(2))
                .andExpect(jsonPath("$.summary.valueCount").value(24)).andExpect(jsonPath("$.summary.complete").value(true))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].amount").value("0.000000"));
        mvc.perform(get("/api/v1/submissions/" + id + "/report-values?month=2").header("Authorization", PLANNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].valueStatus").value("AVAILABLE"));
        mvc.perform(get("/api/v1/submissions/" + id + "/source-cells?sheet=매출&cell=A1").header("Authorization", PLANNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rawValue").value("0"))
                .andExpect(jsonPath("$.sharedFormulaBase.cell").value("B1"))
                .andExpect(jsonPath("$.sharedFormulaBase.formula").value("=B2-B3"));
        mvc.perform(get("/api/v1/submissions/" + id + "/source-cells?sheet=매출&cell=A2").header("Authorization", PLANNER))
                .andExpect(status().isNotFound());
        String body = mapper.writeValueAsString(ApiData.object("expectedStateToken", validated.path("submission").path("stateToken").asText()));
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = mvc.perform(post("/api/v1/submissions/" + id + "/submit").header("Authorization", MANAGER)
                    .header("Idempotency-Key", "submit-financial-01").contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.seq").value(1));
            if (attempt == 1) result.andExpect(header().string("Idempotency-Replayed", "true"));
        }
        mvc.perform(get("/api/v1/submission-requests/" + request).header("Authorization", PLANNER))
                .andExpect(jsonPath("$.submittedCount").value(1)).andExpect(jsonPath("$.targets[0].latestSubmittedSubmissionId").value(id));
        mvc.perform(get("/api/v1/submissions/" + id + "/file").header("Authorization", PLANNER))
                .andExpect(content().bytes(workbook()));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM financial_value").query(Long.class).single()).isEqualTo(24);
    }

    /** 파싱 확인이 필요한 초안에서도 금액 누락을 함께 안내하되 적재와 소명을 생성하지 않는다.
     * Args: 없음. Returns: 없음. Raises: HTTP·JSON·검증 오류.
     * Side Effects: 합성 H2 파싱 상태만 준비하며 조회 전후 금융값·검토·검증 저장 건수가 0인지 확인한다.
     */
    @Test
    void reviewRequiredParsingExposesCompletenessWithoutPersistingValidation() throws Exception {
        String id = upload(createRequest(), "preview-missing-01", workbook()).path("id").asText();
        seedAdapter(id, true);
        jdbc.sql("UPDATE parsing_job SET status='NEEDS_REVIEW',error_code='PARSER_REVIEW_REQUIRED' WHERE submission_id=?")
                .param(Long.parseLong(id)).update();
        mvc.perform(get("/api/v1/submissions/" + id + "/diagnostics?stage=COMPLETENESS").header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].code").value("PLAN_AMOUNT_UNRESOLVED"));
        mvc.perform(get("/api/v1/submissions/" + id).header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readiness.validationState").value("NOT_RUN"))
                .andExpect(jsonPath("$.readiness.canSubmit").value(false));
        for (String table : new String[]{"financial_line", "financial_value", "submission_validation", "review_item"})
            assertThat(jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single()).isZero();
        mvc.perform(get("/api/v1/submissions/" + id + "/file").header("Authorization", MANAGER))
                .andExpect(content().bytes(workbook()));
    }

    /** 업무 근거에서 제외한 파싱 후보도 보존된 원문으로 조회하고 없는 셀은 구분한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·JSON·검증 오류.
     * Side Effects: 합성 H2 JSON만 준비하며 원본·금액·조회 자료를 변경하지 않는다.
     */
    @Test
    void sourceCellFallsBackToPreservedCandidateWithoutGuessingUnknownCells() throws Exception {
        String id = upload(createRequest(), "candidate-source-01", workbook()).path("id").asText();
        seedAdapter(id, false);
        var parsed = adapter(id);
        parsed.withArray("records").add(mapper.readTree("""
                {"source":{"sheet":"매출","cell":"Z99"},"role":{"kind":"inactive_template"},
                 "raw_value":"-0.250000","data_type":"number","formula":null,
                 "value":{"status":"confirmed","value_kind":"amount","normalized_value":"-0.250000","source_unit":"원","multiplier":"1"}}
                """));
        saveAdapter(id, parsed);
        mvc.perform(get("/api/v1/submissions/" + id + "/source-cells?sheet=매출&cell=Z99").header("Authorization", PLANNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rawValue").value("-0.250000"))
                .andExpect(jsonPath("$.normalizedAmount").value("-0.250000"))
                .andExpect(jsonPath("$.presence").value("PRESENT")).andExpect(jsonPath("$.cacheStatus").value("NOT_FORMULA"));
        mvc.perform(get("/api/v1/submissions/" + id + "/source-cells?sheet=매출&cell=Z98").header("Authorization", PLANNER))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SOURCE_CELL_NOT_FOUND"));
        assertThat(adapter(id)).isEqualTo(parsed);
    }

    /** 누락을 0으로 채우지 않고 불완전 행과 보고 합계를 차단하며 재검증 커서를 무효화한다.
     * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 H2만 변경.
     */
    @Test
    void incompleteFactsExposeDiagnosticsAndCannotBeSubmitted() throws Exception {
        String id = upload(createRequest(), "financial-missing-01", workbook()).path("id").asText();
        seedAdapter(id, true);
        JsonNode validated = workflow(id, "validation", "validate-missing-01");
        assertThat(validated.path("submission").path("readiness").path("canSubmit").asBoolean()).isFalse();
        mvc.perform(get("/api/v1/submissions/" + id + "/financial-values").header("Authorization", PLANNER))
                .andExpect(jsonPath("$.summary.lineCount").value(1)).andExpect(jsonPath("$.summary.complete").value(false));
        mvc.perform(get("/api/v1/submissions/" + id + "/report-values").header("Authorization", PLANNER))
                .andExpect(jsonPath("$.items[0].amount").doesNotExist()).andExpect(jsonPath("$.items[0].valueStatus").value("UNRESOLVED"));
        mvc.perform(get("/api/v1/submissions/" + id + "/diagnostics?stage=COMPLETENESS").header("Authorization", PLANNER))
                .andExpect(jsonPath("$.items[0].code").value("PLAN_AMOUNT_UNRESOLVED"));
        mvc.perform(post("/api/v1/submissions/" + id + "/submit").header("Authorization", MANAGER)
                .header("Idempotency-Key", "submit-missing-01").contentType("application/json")
                .content(mapper.writeValueAsString(ApiData.object("expectedStateToken", validated.path("submission").path("stateToken").asText()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBMISSION_NOT_READY"));
        JsonNode page = mapper.readTree(mvc.perform(get("/api/v1/submissions/" + id + "/financial-values?limit=1").header("Authorization", MANAGER))
                .andReturn().getResponse().getContentAsString());
        workflow(id, "validation", "validate-missing-02");
        mvc.perform(get("/api/v1/submissions/" + id + "/financial-values").param("limit", "1").param("cursor", page.path("nextCursor").asText()).header("Authorization", MANAGER))
                .andExpect(status().isBadRequest());
        jdbc.sql("UPDATE parsing_job SET attempt_no=attempt_no+1 WHERE submission_id=?").param(Long.parseLong(id)).update();
        mvc.perform(get("/api/v1/submissions/" + id + "/financial-values").header("Authorization", MANAGER))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VALIDATION_STALE"));
        assertThat(jdbc.sql("SELECT seq FROM submission WHERE id=?").param(Long.parseLong(id)).query().singleRow().get("seq")).isNull();
    }

    /** 새 조회·명령에서 타 조직 및 기획자 쓰기를 제한한다.
     * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: H2만 변경.
     */
    @Test
    void financialEndpointsEnforceOrganizationAndWriterRole() throws Exception {
        String id = upload(createRequest(), "financial-access-01", workbook()).path("id").asText();
        seedAdapter(id, false);
        mvc.perform(post("/api/v1/submissions/" + id + "/validation").header("Authorization", PLANNER)
                .header("Idempotency-Key", "validate-planner-01").contentType("application/json").content("{\"expectedStateToken\":\"any\"}"))
                .andExpect(status().isForbidden());
        jdbc.sql("UPDATE submission SET organization_id=2 WHERE id=?").param(Long.parseLong(id)).update();
        for (String suffix : new String[]{"/financial-values", "/report-values", "/source-cells?sheet=매출&cell=A1"})
            mvc.perform(get("/api/v1/submissions/" + id + suffix).header("Authorization", MANAGER)).andExpect(status().isNotFound());
    }

    /** 제출 후 요청이 닫혀도 고정 검증 근거로 승인하고 원본·금액·회차를 보존한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·검증 오류.
     * Side Effects: 합성 연간 제출·검토·멱등 기록을 H2에 저장하며 파일·외부 DB 접근 없음.
     */
    @Test
    void annualApprovalIsAuditedIdempotentAndPreservesSubmittedFacts() throws Exception {
        String request = createRequest();
        String id = pendingSubmission(request, "approval");
        byte[] original = mvc.perform(get("/api/v1/submissions/" + id + "/file").header("Authorization", PLANNER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var values = jdbc.sql("SELECT id,line_id,year,month,scenario,amount,source_record_id,source_cell FROM financial_value ORDER BY id").query().listOfRows();
        String validationKey = jdbc.sql("SELECT validation_result_object_key FROM submission_validation WHERE submission_id=?")
                .param(Long.parseLong(id)).query(String.class).single();
        String body = decisionBody(id, "APPROVE", null);
        jdbc.sql("UPDATE submission_request SET status='CLOSED' WHERE id=?").param(Long.parseLong(request)).update();
        String originalResponse = decision(id, "approve-audit-01", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.submission.status").value("APPROVED"))
                .andExpect(jsonPath("$.submission.seq").value(1))
                .andExpect(jsonPath("$.submission.reviewedBy.id").value("10"))
                .andExpect(jsonPath("$.submission.reviewedBy.name").value("기획"))
                .andExpect(jsonPath("$.submission.reviewedAt").isNotEmpty())
                .andExpect(jsonPath("$.submission.rejectReason").doesNotExist())
                .andExpect(jsonPath("$.masterEffects[0].action").value("WAITING_INITIAL"))
                .andExpect(jsonPath("$.masterEffects[0].approvedOrganizationCount").value(1))
                .andExpect(jsonPath("$.masterEffects[0].requiredOrganizationCount").value(2))
                .andReturn().getResponse().getContentAsString();
        decision(id, "approve-audit-01", body).andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true")).andExpect(content().json(originalResponse));
        decision(id, "approve-ended-01", decisionBody(id, "APPROVE", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SUBMISSION_STATE"));
        mvc.perform(get("/api/v1/submissions/" + id).header("Authorization", MANAGER))
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.reviewedBy.id").value("10"));
        mvc.perform(get("/api/v1/submission-requests/" + request).header("Authorization", PLANNER))
                .andExpect(jsonPath("$.approvedCount").value(1)).andExpect(jsonPath("$.targets[0].latestStatus").value("APPROVED"));
        assertThat(jdbc.sql("SELECT id,line_id,year,month,scenario,amount,source_record_id,source_cell FROM financial_value ORDER BY id").query().listOfRows()).isEqualTo(values);
        assertThat(jdbc.sql("SELECT validation_result_object_key FROM submission_validation WHERE submission_id=?")
                .param(Long.parseLong(id)).query(String.class).single()).isEqualTo(validationKey);
        mvc.perform(get("/api/v1/submissions/" + id + "/file").header("Authorization", PLANNER))
                .andExpect(status().isOk()).andExpect(content().bytes(original));
    }

    /** 새 PRD의 연간 재제출은 변경 근거를 보존하되 Gap 판정 없는 금액 변경을 강제로 소명시키지 않는다.
     * Args: 없음. Returns: 없음. Raises: HTTP·JSON·검증 오류.
     * Side Effects: H2에 세 합성 파일과 검증 근거를 저장하며 원본 Data에는 접근하지 않는다.
     */
    @Test
    void rejectionPreservesHistoryAndNewAnnualPolicyDoesNotForceChangeReasons() throws Exception {
        String request = createRequest();
        String first = pendingSubmission(request, "rejected");
        String body = decisionBody(first, "REJECT", "  원본의 거래 근거를 보완해 주세요.  ");
        decision(first, "reject-first-01", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.submission.status").value("REJECTED"))
                .andExpect(jsonPath("$.submission.rejectReason").value("원본의 거래 근거를 보완해 주세요."))
                .andExpect(jsonPath("$.submission.reviewedBy.id").value("10"))
                .andExpect(jsonPath("$.submission.reviewedAt").isNotEmpty()).andExpect(jsonPath("$.masterEffects").isEmpty());
        decision(first, "reject-first-01", body).andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"));
        decision(first, "reject-ended-01", decisionBody(first, "REJECT", "새 사유")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SUBMISSION_STATE"));
        mvc.perform(get("/api/v1/submissions/" + first).header("Authorization", MANAGER))
                .andExpect(jsonPath("$.rejectReason").value("원본의 거래 근거를 보완해 주세요."));
        String second = pendingSubmission(request, "resubmitted");
        JsonNode secondView = submissionView(second);
        assertThat(secondView.path("seq").asInt()).isEqualTo(2);
        assertThat(secondView.path("comparisonContext").path("previousSubmissionId").asText()).isEqualTo(first);
        decision(second, "reject-second-01", decisionBody(second, "REJECT", "추가 근거가 필요합니다.")).andExpect(status().isOk());
        String changed = upload(request, "changed-upload-01", workbook()).path("id").asText();
        seedAdapter(changed, false);
        String key = "test-adapter/" + changed;
        JsonNode parsed = mapper.readTree(jdbc.sql("SELECT payload FROM stored_object WHERE object_key=?").param(key).query(byte[].class).single());
        ((com.fasterxml.jackson.databind.node.ObjectNode) parsed.path("submissionData").path("lines").get(0).path("values").get(1))
                .put("amount", "100.123457");
        jdbc.sql("UPDATE stored_object SET payload=? WHERE object_key=?").params(mapper.writeValueAsBytes(parsed), key).update();
        JsonNode validation = workflow(changed, "validation", "changed-validate-01");
        assertThat(validation.path("submission").path("readiness").path("canSubmit").asBoolean()).isTrue();
        assertThat(validation.path("submission").path("readiness").path("requiredCount").asInt()).isZero();
        assertThat(validation.path("answerRequiredReviewItemIds").isEmpty()).isTrue();
        String reviewId=validation.path("changedReviewItemIds").get(0).asText();
        mvc.perform(get("/api/v1/review-items/"+reviewId+"/evidence").header("Authorization",PLANNER).param("reason","CHANGE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.baselineSubmissionId").value(second))
                .andExpect(jsonPath("$.items.length()").value(2));
        assertThat(reviewView(reviewId).path("required").asBoolean()).isFalse();
        mvc.perform(get("/api/v1/submissions/"+changed+"/comparison").header("Authorization",MANAGER)
                .param("baselineSubmissionId",second).param("scenario","PLAN").param("baselineScenario","PLAN").param("month","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].baselineEvidenceUrl").isNotEmpty());
        assertThat(submissionView(changed).path("comparisonContext").path("previousSubmissionId").asText()).isEqualTo(second);
        workflow(changed,"submit","changed-submit-without-forced-reason");
        assertThat(submissionView(changed).path("seq").asInt()).isEqualTo(3);
        assertThat(submissionView(first).path("status").asText()).isEqualTo("REJECTED");
        assertThat(submissionView(first).path("seq").asInt()).isEqualTo(1);
        assertThat(submissionView(changed).path("readiness").path("canApprove").asBoolean()).isTrue();
        var requested=ApiData.object("action","REQUEST_EXPLANATION","items",java.util.List.of(ApiData.object("reviewItemId",reviewId,"expectedStateToken",reviewView(reviewId).path("stateToken").asText())),
                "note","기준 미달이지만 거래 근거를 확인해 주세요.","dueAt","2031-12-01T18:00:00+09:00");
        mvc.perform(post("/api/v1/review-items/actions").header("Authorization",PLANNER).header("Idempotency-Key","request-reference-reason")
                .contentType("application/json").content(mapper.writeValueAsBytes(requested))).andExpect(status().isOk());
        assertThat(submissionView(changed).path("readiness").path("requiredCount").asInt()).isEqualTo(1);
        assertThat(submissionView(changed).path("readiness").path("canApprove").asBoolean()).isFalse();
        decision(changed,"approve-reference-unanswered",decisionBody(changed,"APPROVE",null)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXPLANATIONS_NOT_ACCEPTED"));
        answerReview(reviewId,"담당자가 요청한 거래 근거를 확인했습니다.","reference-request-answer");
        reviewDecision(reviewId,"ACCEPT",null,"reference-request-accept").andExpect(status().isOk());
        decision(changed,"approve-no-gap",decisionBody(changed,"APPROVE",null)).andExpect(status().isOk());
    }

    /** 기획자 권한·판단 입력·최신 토큰·대기 상태를 HTTP 경계에서 강제한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·검증 오류.
     * Side Effects: 합성 초안과 대기본만 H2에 저장하고 모든 잘못된 판단은 보존 확인한다.
     */
    @Test
    void decisionsEnforceRoleInputStateAndToken() throws Exception {
        String request = createRequest();
        String draft = upload(request, "decision-draft-01", workbook()).path("id").asText();
        decision(draft, "decide-draft-01", decisionBody(draft, "REJECT", "초안 판단 금지"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_SUBMISSION_STATE"));
        String id = pendingSubmission(request, "decision-input");
        String body = decisionBody(id, "APPROVE", null);
        mvc.perform(post("/api/v1/submissions/" + id + "/decisions").header("Authorization", MANAGER)
                .header("Idempotency-Key", "manager-decide-01").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/submissions/" + id + "/decisions").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        String token = submissionView(id).path("stateToken").asText();
        for (String invalid : new String[]{
                "{}", "{\"expectedStateToken\":\"x\",\"action\":\"OTHER\"}",
                "{\"expectedStateToken\":3,\"action\":\"APPROVE\"}",
                mapper.writeValueAsString(ApiData.object("expectedStateToken", token, "action", "REJECT")),
                mapper.writeValueAsString(ApiData.object("expectedStateToken", token, "action", "REJECT", "note", " \n\t ")),
                mapper.writeValueAsString(ApiData.object("expectedStateToken", token, "action", "REJECT", "note", "가".repeat(2001))),
                mapper.writeValueAsString(ApiData.object("expectedStateToken", token, "action", "APPROVE", "note", null)),
                mapper.writeValueAsString(ApiData.object("expectedStateToken", token, "action", "APPROVE", "note", "메모 금지")),
                mapper.writeValueAsString(ApiData.object("expectedStateToken", token, "action", "APPROVE", "unexpected", true))}) {
            decision(id, "invalid-decision-01", invalid).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        decision(id, "stale-decision-01", mapper.writeValueAsString(ApiData.object("expectedStateToken", "stale", "action", "APPROVE")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_CHANGED"));
        assertThat(submissionView(id).path("status").asText()).isEqualTo("PENDING");
        assertThat(submissionView(id).path("reviewedAt").isNull()).isTrue();
    }

    /** 승인 근거가 사라진 대기본은 승인 불가이지만 명시적인 사유로 반려할 수 있다.
     * Args: 없음. Returns: 없음. Raises: HTTP·검증 오류.
     * Side Effects: 메모리 검증 참조만 제거해 손상을 모사하고 반려 기록을 저장한다.
     */
    @Test
    void missingFrozenValidationBlocksApprovalButAllowsRejection() throws Exception {
        String id = pendingSubmission(createRequest(), "missing-validation");
        jdbc.sql("DELETE FROM submission_validation WHERE submission_id=?").param(Long.parseLong(id)).update();
        decision(id, "approve-no-proof-01", decisionBody(id, "APPROVE", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VALIDATION_REQUIRED"));
        assertThat(submissionView(id).path("status").asText()).isEqualTo("PENDING");
        decision(id, "reject-no-proof-01", decisionBody(id, "REJECT", "검증 근거를 확인할 수 없습니다."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.submission.status").value("REJECTED"));
    }

    /** 저장된 검증과 실제 적재 개수가 달라지면 승인 상태로 넘기지 않는다.
     * Args: 없음. Returns: 없음. Raises: HTTP·검증 오류.
     * Side Effects: H2의 합성 금액 한 건을 제거하며 원본 파일이나 운영 DB에는 접근하지 않는다.
     */
    @Test
    void changedFinancialRowsCannotPassFrozenApprovalCheck() throws Exception {
        String id = pendingSubmission(createRequest(), "missing-fact");
        jdbc.sql("DELETE FROM financial_value WHERE id=(SELECT MIN(id) FROM financial_value)").update();
        decision(id, "approve-missing-fact-01", decisionBody(id, "APPROVE", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBMISSION_NOT_READY"));
        assertThat(submissionView(id).path("status").asText()).isEqualTo("PENDING");
        assertThat(submissionView(id).path("reviewedBy").isNull()).isTrue();
    }

    /** 월간 승인은 확정 계획을 요구하며 잘못된 유형의 제출본 반려는 허용한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·검증 오류.
     * Side Effects: H2 합성 요청 종류를 바꿔 확정 계획이 없는 월간 판단 경계를 검사한다.
     */
    @Test
    void monthlyApprovalRequiresConfirmedPlanAndMatchingMonthlyValidation() throws Exception {
        String request = createRequest();
        String id = pendingSubmission(request, "monthly-decision");
        jdbc.sql("UPDATE submission_request SET type='MONTHLY_ACTUAL',month=1,period_key='2031-01' WHERE id=?")
                .param(Long.parseLong(request)).update();
        decision(id, "approve-monthly-01", decisionBody(id, "APPROVE", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANNUAL_PLAN_NOT_CONFIRMED"));
        decision(id, "reject-monthly-01", decisionBody(id, "REJECT", "원본 범위가 잘못되었습니다."))
                .andExpect(status().isOk()).andExpect(jsonPath("$.submission.status").value("REJECTED"));
    }

    /** Gap의 비율·금액 토글을 이력에 저장하고 둘 다 꺼진 기준은 차단한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·검증 오류. Side Effects: 합성 H2 기준 이력만 생성한다.
     */
    @Test
    void gapThresholdConditionTogglesPersistAndRequireEnabledCondition() throws Exception {
        var current=mapper.readTree(mvc.perform(get("/api/v1/gap-thresholds/current").header("Authorization",PLANNER)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var body=ApiData.object("expectedStateToken",current.path("stateToken").asText(),"scenario","ACTUAL","percent","5","amount","10000000","percentEnabled",false,"amountEnabled",true);
        mvc.perform(post("/api/v1/gap-thresholds").header("Authorization",PLANNER).header("Idempotency-Key","threshold-amount-only")
                .contentType("application/json").content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.percentEnabled").value(false)).andExpect(jsonPath("$.amountEnabled").value(true));
        current=mapper.readTree(mvc.perform(get("/api/v1/gap-thresholds/current").header("Authorization",PLANNER))
                .andExpect(jsonPath("$.actual.percentEnabled").value(false)).andReturn().getResponse().getContentAsString());
        body.put("expectedStateToken",current.path("stateToken").asText());body.put("amountEnabled",false);
        mvc.perform(post("/api/v1/gap-thresholds").header("Authorization",PLANNER).header("Idempotency-Key","threshold-both-disabled")
                .contentType("application/json").content(mapper.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/gap-thresholds/preview").header("Authorization",PLANNER).contentType("application/json")
                .content("{\"requestId\":\"1\",\"scenario\":\"ACTUAL\",\"percent\":\"5\",\"amount\":\"10000000\",\"percentEnabled\":false,\"amountEnabled\":false}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM gap_threshold").query(Integer.class).single()).isEqualTo(1);
    }

    /** 확정 계획과 새 기준으로 월간 실적 Gap을 검토한 뒤 승인까지 연결한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·DB·검증 오류. Side Effects: 합성 H2만 변경하며 파일은 생성하지 않는다.
     */
    @Test
    void monthlyActualGapUsesFixedPlanAndRequiresAcceptedExplanation() throws Exception {
        String annual = createRequest(); String plan = pendingSubmission(annual, "annual-fixed");
        decision(plan, "annual-fixed-approve", decisionBody(plan, "APPROVE", null)).andExpect(status().isOk());
        jdbc.sql("UPDATE request_target SET confirmed_plan_submission_id=? WHERE request_id=? AND organization_id=1").params(Long.parseLong(plan),Long.parseLong(annual)).update();
        jdbc.sql("UPDATE annual_plan SET status='CONFIRMED'").update();
        jdbc.sql("UPDATE request_target SET excluded=TRUE WHERE organization_id=2").update();
        jdbc.sql("UPDATE organization SET active=FALSE WHERE id=2").update();
        applyThreshold("ACTUAL", "5", "10000000", "actual-threshold");
        applyThreshold("ESTIMATE", "10", "50000000", "estimate-threshold");
        JsonNode request=mapper.readTree(mvc.perform(post("/api/v1/submission-requests").header("Authorization",PLANNER).header("Idempotency-Key","monthly-request-01").contentType("application/json").content("{\"type\":\"MONTHLY_ACTUAL\",\"year\":2031,\"month\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String monthly=mapper.readTree(mvc.perform(multipart("/api/v1/submission-requests/"+request.path("id").asText()+"/submissions")
                .file(new MockMultipartFile("file","D100_2031-01.xlsx","application/octet-stream",workbook())).header("Authorization",MANAGER).header("Idempotency-Key","monthly-upload-01"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asText();
        seedAdapter(monthly,false);String objectKey="test-adapter/"+monthly;
        var parsed=(com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(jdbc.sql("SELECT payload FROM stored_object WHERE object_key=?").param(objectKey).query(byte[].class).single());
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)parsed.path("submissionData");
        for(JsonNode sheet:data.path("sheets"))for(JsonNode period:sheet.path("periodColumns"))if(period.path("year").asInt()==2031)((com.fasterxml.jackson.databind.node.ObjectNode)period).put("scenario",period.path("month").asInt()==1?"ACTUAL":"ESTIMATE");
        for(JsonNode line:data.path("lines"))for(JsonNode value:line.path("values"))if(value.path("year").asInt()==2031)((com.fasterxml.jackson.databind.node.ObjectNode)value).put("scenario",value.path("month").asInt()==1?"ACTUAL":"ESTIMATE");
        for(JsonNode control:data.path("controlValues"))if(control.path("year").asInt()==2031)((com.fasterxml.jackson.databind.node.ObjectNode)control).put("scenario",control.path("month").asInt()==1?"ACTUAL":"ESTIMATE");
        ((com.fasterxml.jackson.databind.node.ObjectNode)data.path("lines").get(0).path("values").get(0)).put("amount","50000000");
        for(JsonNode control:data.path("controlValues"))if(control.path("month").asInt()==1 && !"expense:salary".equals(control.path("reportItemKey").asText()) && !"COST_TOTAL".equals(control.path("calculationCode").asText()))((com.fasterxml.jackson.databind.node.ObjectNode)control).put("amount","50000000");
        jdbc.sql("UPDATE stored_object SET payload=? WHERE object_key=?").params(mapper.writeValueAsBytes(parsed),objectKey).update();
        JsonNode validated=workflow(monthly,"validation","monthly-validate-01");
        assertThat(validated.path("submission").path("readiness").path("unansweredCount").asInt()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM financial_value v JOIN financial_line l ON l.id=v.line_id WHERE l.submission_id=? AND v.scenario='ACTUAL'").param(Long.parseLong(monthly)).query(Integer.class).single()).isEqualTo(2);
        String reviewId=validated.path("answerRequiredReviewItemIds").get(0).asText();
        String secondReviewId=validated.path("answerRequiredReviewItemIds").get(1).asText();
        applyThreshold("ACTUAL","6","10000000","actual-draft-new");
        assertThat(submissionView(monthly).path("readiness").path("validationState").asText()).isEqualTo("STALE");
        workflow(monthly,"validation","monthly-validate-new-context");
        JsonNode item=reviewView(reviewId);
        assertThat(item.path("comparisons").get(0).path("basis").asText()).isEqualTo("PLAN");
        assertThat(item.path("comparisons").get(0).path("baselineAmount").decimalValue()).isZero();
        assertThat(item.path("comparisons").get(0).path("gapRate").isNull()).isTrue();
        String oldThreshold=item.path("comparisons").get(0).path("threshold").path("id").asText();
        answerReview(reviewId,"합성 검증용: 최초 월 실적 5천만 원을 원본과 대조했습니다.","monthly-answer-01");
        answerReview(secondReviewId,"합성 검증용: 영업이익 변동을 대조했습니다.","monthly-answer-op");
        workflow(monthly,"submit","monthly-submit-01");
        applyThreshold("ACTUAL","99","999999999","actual-new-threshold");
        assertThat(reviewView(reviewId).path("comparisons").get(0).path("threshold").path("id").asText()).isEqualTo(oldThreshold);
        reviewDecision(reviewId,"ACCEPT",null,"monthly-review-accept").andExpect(status().isOk());
        reviewDecision(secondReviewId,"ACCEPT",null,"monthly-review-accept-op").andExpect(status().isOk());
        decision(monthly,"monthly-approve-01",decisionBody(monthly,"APPROVE",null)).andExpect(status().isOk()).andExpect(jsonPath("$.masterEffects[0].action").value("CREATED"));
        mvc.perform(post("/api/v1/gap-thresholds/preview").header("Authorization",PLANNER).contentType("application/json").content(mapper.writeValueAsString(ApiData.object("requestId",request.path("id").asText(),"scenario","ACTUAL","percent","5","amount","10000000"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evaluatedItemCount").value(3)).andExpect(jsonPath("$.exceededItemCount").value(2)).andExpect(jsonPath("$.excludedItemCount").value(0));
    }

    /** 같은 합계의 원천 추가·상쇄는 근거만 보존하고 Gap 없는 소명을 강제하지 않으며 통째 누락은 차단한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·검증 실패. Side Effects: 합성 H2와 메모리 JSON만 변경한다.
     */
    @Test
    void offsettingSourceChangesRemainTraceableWithoutForcedReasonsAndRemovedReportBlocks() throws Exception {
        String request=createRequest(), baseline=pendingSubmission(request,"offset-baseline");
        decision(baseline,"offset-baseline-reject",decisionBody(baseline,"REJECT","합성 재제출 검증")).andExpect(status().isOk());
        String changed=upload(request,"offset-upload",workbook()).path("id").asText();seedAdapter(changed,false);
        var parsed=adapter(changed);var data=(com.fasterxml.jackson.databind.node.ObjectNode)parsed.path("submissionData");
        var original=(com.fasterxml.jackson.databind.node.ObjectNode)data.path("lines").get(0);
        var added=original.deepCopy();added.put("detailId","offset-extra");((com.fasterxml.jackson.databind.node.ObjectNode)added.path("source")).put("row",20);
        for(JsonNode value:added.path("values"))((com.fasterxml.jackson.databind.node.ObjectNode)value).put("amount",value.path("month").asInt()==2?"-1":"0").put("sourceCell","B"+(20+value.path("month").asInt())).put("sourceRecordId","offset-"+value.path("month").asInt());
        ((com.fasterxml.jackson.databind.node.ObjectNode)original.path("values").get(1)).put("amount","101.123456");
        ((com.fasterxml.jackson.databind.node.ArrayNode)data.path("lines")).add(added);saveAdapter(changed,parsed);
        JsonNode result=workflow(changed,"validation","offset-validate");
        assertThat(result.path("submission").path("readiness").path("requiredCount").asInt()).isZero();
        JsonNode items=mapper.readTree(mvc.perform(get("/api/v1/review-items").header("Authorization",PLANNER).param("submissionId",changed).param("month","2")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String item=items.path("items").get(0).path("id").asText();
        assertThat(items.path("items").get(0).path("reportItem").path("kind").asText()).isEqualTo("REVENUE");
        mvc.perform(get("/api/v1/review-items/"+item+"/evidence").header("Authorization",PLANNER).param("reason","CHANGE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.netDelta").value("0.000000")).andExpect(jsonPath("$.items.length()").value(3));
        String removed=upload(request,"removed-upload",workbook()).path("id").asText();seedAdapter(removed,false);var missing=adapter(removed);var missingData=(com.fasterxml.jackson.databind.node.ObjectNode)missing.path("submissionData");
        ((com.fasterxml.jackson.databind.node.ArrayNode)missingData.path("lines")).remove(0);
        for(JsonNode control:missingData.path("controlValues")){var c=(com.fasterxml.jackson.databind.node.ObjectNode)control;if("revenue:service".equals(c.path("reportItemKey").asText())||"REVENUE_TOTAL".equals(c.path("calculationCode").asText()))c.put("amount","0");if("OP".equals(c.path("calculationCode").asText()))c.put("amount",c.path("month").asInt()==1?"0":"-40.123456");}
        saveAdapter(removed,missing);JsonNode removal=workflow(removed,"validation","removed-validate");
        assertThat(removal.path("submission").path("readiness").path("blockers").findValuesAsText("code")).contains("REQUIRED_REPORT_ITEM_REMOVED");
    }

    /** 연간 계획의 신규 0원 항목은 근거로 남기고 기준 원본의 상쇄 변조를 거부한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·DB·검증 실패. Side Effects: 합성 H2만 변경한다.
     */
    @Test
    void addedAnnualZeroReportKeepsEvidenceAndBaselineIntegrityDetectsOffsettingMutation() throws Exception {
        String request=createRequest(),baseline=pendingSubmission(request,"new-report-baseline");
        decision(baseline,"new-report-reject",decisionBody(baseline,"REJECT","합성 신규 항목 검증")).andExpect(status().isOk());
        assertThat(comparisons.complete(Long.parseLong(baseline))).isTrue();
        var values=jdbc.sql("SELECT v.id,v.amount FROM financial_value v JOIN financial_line l ON l.id=v.line_id WHERE l.submission_id=? AND v.month=2 ORDER BY v.id").param(Long.parseLong(baseline)).query().listOfRows();
        for(var value:values)jdbc.sql("UPDATE financial_value SET amount=amount+1 WHERE id=?").param(value.get("id")).update();
        assertThat(comparisons.complete(Long.parseLong(baseline))).isFalse();
        for(var value:values)jdbc.sql("UPDATE financial_value SET amount=? WHERE id=?").params(value.get("amount"),value.get("id")).update();
        assertThat(comparisons.complete(Long.parseLong(baseline))).isTrue();
        jdbc.sql("UPDATE parsing_job SET attempt_no=attempt_no+1 WHERE submission_id=?").param(Long.parseLong(baseline)).update();
        assertThat(comparisons.complete(Long.parseLong(baseline))).isFalse();
        jdbc.sql("UPDATE parsing_job SET attempt_no=attempt_no-1 WHERE submission_id=?").param(Long.parseLong(baseline)).update();
        String changed=upload(request,"new-report-upload",workbook()).path("id").asText();seedAdapter(changed,false);var parsed=adapter(changed);var data=(com.fasterxml.jackson.databind.node.ObjectNode)parsed.path("submissionData");
        var report=((com.fasterxml.jackson.databind.node.ObjectNode)data.path("reportItems").get(0)).deepCopy().put("key","revenue:new").put("name","신규 보고 항목");((com.fasterxml.jackson.databind.node.ArrayNode)data.path("reportItems")).add(report);
        var added=((com.fasterxml.jackson.databind.node.ObjectNode)data.path("lines").get(0)).deepCopy().put("reportItemKey","revenue:new").put("detailId","new-report-row");((com.fasterxml.jackson.databind.node.ObjectNode)added.path("source")).put("row",20);
        for(JsonNode value:added.path("values"))((com.fasterxml.jackson.databind.node.ObjectNode)value).put("amount","0").put("sourceCell","C"+(20+value.path("month").asInt())).put("sourceRecordId","new-report-"+value.path("month").asInt());
        ((com.fasterxml.jackson.databind.node.ArrayNode)data.path("lines")).add(added);var controls=(com.fasterxml.jackson.databind.node.ArrayNode)data.path("controlValues");int count=controls.size();for(int index=0;index<count;index++)if("revenue:service".equals(controls.get(index).path("reportItemKey").asText()))controls.add(((com.fasterxml.jackson.databind.node.ObjectNode)controls.get(index)).deepCopy().put("reportItemKey","revenue:new").put("amount","0"));
        saveAdapter(changed,parsed);JsonNode result=workflow(changed,"validation","new-report-validate");
        assertThat(result.path("submission").path("readiness").path("requiredCount").asInt()).isZero();
        assertThat(result.path("submission").path("readiness").path("blockers").findValuesAsText("code")).isEmpty();
    }

    /** 보존된 합성 adapter JSON을 읽는다. Args: submission 제출. Returns: JSON. Raises: DB·JSON 오류. Side Effects: 읽기. */
    private com.fasterxml.jackson.databind.node.ObjectNode adapter(String submission) throws Exception {return (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(jdbc.sql("SELECT payload FROM stored_object WHERE object_key=?").param("test-adapter/"+submission).query(byte[].class).single());}
    /** 테스트 전용 adapter의 합성 근거를 저장한다. Args: submission 제출, parsed JSON. Returns: 없음. Raises: DB·JSON 오류. Side Effects: 합성 H2 payload만 변경한다. */
    private void saveAdapter(String submission,JsonNode parsed) throws Exception {jdbc.sql("UPDATE stored_object SET payload=? WHERE object_key=?").params(mapper.writeValueAsBytes(parsed),"test-adapter/"+submission).update();}

    /** 현재 검토 상세를 읽는다. Args: reviewId 항목. Returns: JSON. Raises: HTTP 오류. Side Effects: H2 읽기. */
    private JsonNode reviewView(String reviewId) throws Exception {return mapper.readTree(mvc.perform(get("/api/v1/review-items/"+reviewId).header("Authorization",PLANNER)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    /** 최신 회차에 답변한다. Args: reviewId 항목, answer 내용, key 멱등키. Returns: 없음. Raises: HTTP 오류. Side Effects: H2 답변 저장. */
    private void answerReview(String reviewId,String answer,String key) throws Exception {var row=reviewView(reviewId);mvc.perform(put("/api/v1/review-items/"+reviewId+"/answer").header("Authorization",MANAGER).header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(ApiData.object("expectedStateToken",row.path("stateToken").asText(),"round",row.path("currentRound").asInt(),"answer",answer)))).andExpect(status().isOk());}
    /** 최신 회차의 수용·보완을 요청한다. Args: reviewId 항목, action 판단, note 사유, key 멱등키. Returns: 검증응답. Raises: HTTP 오류. Side Effects: H2 검토 저장. */
    private org.springframework.test.web.servlet.ResultActions reviewDecision(String reviewId,String action,String note,String key) throws Exception {var row=reviewView(reviewId);return mvc.perform(post("/api/v1/review-items/"+reviewId+"/decisions").header("Authorization",PLANNER).header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(ApiData.object("expectedStateToken",row.path("stateToken").asText(),"round",row.path("currentRound").asInt(),"action",action,"note",note))));}
    /** 기대 토큰으로 새 기준을 적용한다. Args: scenario 종류, percent 비율, amount 금액, key 멱등키. Returns: 없음. Raises: HTTP 오류. Side Effects: H2 기준 저장. */
    private void applyThreshold(String scenario,String percent,String amount,String key) throws Exception {var current=mapper.readTree(mvc.perform(get("/api/v1/gap-thresholds/current").header("Authorization",PLANNER)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());mvc.perform(post("/api/v1/gap-thresholds").header("Authorization",PLANNER).header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(ApiData.object("expectedStateToken",current.path("stateToken").asText(),"scenario",scenario,"percent",percent,"amount",amount)))).andExpect(status().isCreated());}

    /** 합성 전체 파일을 접수·검증·최종 제출해 실제 HTTP 검토 대기본을 준비한다.
     * Args: request 요청 ID, keyPrefix 서로 다른 멱등키 접두사. Returns: PENDING 제출 ID.
     * Raises: HTTP·검증 오류. Side Effects: H2의 원본·검증·금융값·제출 기록 저장.
     */
    private String pendingSubmission(String request, String keyPrefix) throws Exception {
        String id = upload(request, keyPrefix + "-upload", workbook()).path("id").asText();
        seedAdapter(id, false);
        workflow(id, "validation", keyPrefix + "-validate");
        assertThat(workflow(id, "submit", keyPrefix + "-submit").path("status").asText()).isEqualTo("PENDING");
        return id;
    }

    /** 기획자에게 보이는 현재 제출 상세를 읽는다.
     * Args: id 제출 ID. Returns: 상세 JSON. Raises: HTTP·JSON 오류. Side Effects: H2 읽기.
     */
    private JsonNode submissionView(String id) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/submissions/" + id).header("Authorization", PLANNER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** 최신 토큰과 승인·반려별 허용 필드로 HTTP 판단 본문을 만든다.
     * Args: id 제출 ID, action 판단, note 반려 사유 또는 null. Returns: JSON 문자열.
     * Raises: HTTP·JSON 오류. Side Effects: 현재 제출 읽기만 수행한다.
     */
    private String decisionBody(String id, String action, String note) throws Exception {
        var body = mapper.createObjectNode().put("expectedStateToken", submissionView(id).path("stateToken").asText()).put("action", action);
        if (note != null) body.put("note", note);
        return mapper.writeValueAsString(body);
    }

    /** 동일 인증 경계로 승인·반려 POST를 실행하고 검증 가능한 결과를 반환한다.
     * Args: id 제출 ID, key 멱등키, body 검증할 원문 JSON. Returns: MockMvc 결과 검증기.
     * Raises: HTTP 실행 오류. Side Effects: 성공 시 H2 검토·멱등 기록만 변경한다.
     */
    private org.springframework.test.web.servlet.ResultActions decision(String id, String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/submissions/" + id + "/decisions").header("Authorization", PLANNER)
                .header("Idempotency-Key", key).contentType("application/json").content(body));
    }

    /** 파서 경계의 독립 합성 근거를 메모리 DB에 주입한다.
     * Args: id 제출, missing 첫 상세 1월 누락 여부. Returns: 없음. Raises: JSON·DB 오류.
     * Side Effects: H2 파싱 결과만 변경하며 원본 객체는 보존한다.
     */
    private void seedAdapter(String id, boolean missing) throws Exception {
        var adapter = FinancialValidationRulesTest.fixture();
        adapter.put("sourceSha256", jdbc.sql("SELECT source_sha256 FROM submission WHERE id=?").param(Long.parseLong(id)).query(String.class).single());
        if (missing) ((com.fasterxml.jackson.databind.node.ObjectNode) adapter.path("lines").get(0).path("values").get(0)).putNull("amount");
        adapter.putArray("sourceCells").add(mapper.valueToTree(ApiData.object("sheet", "매출", "cell", "A1", "presence", "PRESENT", "rawValue", "0", "normalizedAmount", "0", "cacheStatus", "NOT_FORMULA")));
        String key = "test-adapter/" + id;
        var shared = ApiData.object("source", ApiData.object("sheet", "매출", "cell", "A1"), "formula", ApiData.object("text", null,
                "attributes", ApiData.object("t", "shared", "si", "1"), "shared_base", ApiData.object("address", "B1", "raw_text", "B2-B3", "attributes", ApiData.object("si", "1", "ref", "A1:B1"))));
        jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES (?,?,?)").params(key, "application/json", mapper.writeValueAsBytes(ApiData.object("submissionData", adapter, "diagnostics", java.util.List.of(), "records", java.util.List.of(shared)))).update();
        jdbc.sql("UPDATE parsing_job SET status='SUCCEEDED',result_object_key=? WHERE submission_id=?").params(key, Long.parseLong(id)).update();
    }

    /** 최신 상태 토큰으로 실제 HTTP 명령을 실행한다.
     * Args: id 제출, name 명령, key 멱등키. Returns: 성공 JSON. Raises: 검증 오류. Side Effects: H2 명령 실행.
     */
    private JsonNode workflow(String id, String name, String key) throws Exception {
        JsonNode current = mapper.readTree(mvc.perform(get("/api/v1/submissions/" + id).header("Authorization", MANAGER)).andReturn().getResponse().getContentAsString());
        return mapper.readTree(mvc.perform(post("/api/v1/submissions/" + id + "/" + name).header("Authorization", MANAGER).header("Idempotency-Key", key)
                .contentType("application/json").content(mapper.writeValueAsString(ApiData.object("expectedStateToken", current.path("stateToken").asText()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** 사전 정의한 기획 계정으로 연간 요청을 생성한다.
     * Args: 없음. Returns: 요청 ID. Raises: 검증 오류. Side Effects: 메모리 DB.
     */
    private String createRequest() throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/submission-requests")
                .header("Authorization", PLANNER).header("Idempotency-Key", "request-create-01")
                .contentType("application/json").content("{\"type\":\"ANNUAL_PLAN\",\"year\":2031}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.targetCount").value(2))
                .andReturn().getResponse().getContentAsString()).path("id").asText();
    }

    /** 합성 원본을 HTTP multipart로 접수한다.
     * Args: requestId 요청, key 멱등키, bytes 원본. Returns: 접수 JSON. Raises: 검증 오류. Side Effects: 메모리 DB.
     */
    private JsonNode upload(String requestId, String key, byte[] bytes) throws Exception {
        return mapper.readTree(mvc.perform(multipart("/api/v1/submission-requests/" + requestId + "/submissions")
                .file(new MockMultipartFile("file", "D100_2031-PLAN.xlsx", "application/octet-stream", bytes))
                .header("Authorization", MANAGER).header("Idempotency-Key", key))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
    }

    /** 독립 상수의 작은 XLSX를 메모리 ZIP으로 작성한다.
     * Args: 없음. Returns: 원본 바이트. Raises: ZIP 생성 오류. Side Effects: 메모리만 사용.
     */
    private byte[] workbook() throws Exception {
        String ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        String rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        var entries = new LinkedHashMap<String, String>();
        entries.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/></Types>");
        entries.put("_rels/.rels", "<Relationships><Relationship Id=\"book\" Type=\"" + rel + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        entries.put("xl/workbook.xml", "<workbook xmlns=\"" + ns + "\" xmlns:r=\"" + rel + "\"><sheets><sheet name=\"매출\" sheetId=\"1\" r:id=\"r1\"/><sheet name=\"조직\" sheetId=\"2\" r:id=\"r2\"/></sheets></workbook>");
        entries.put("xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"r1\" Type=\"" + rel + "/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"r2\" Type=\"" + rel + "/worksheet\" Target=\"worksheets/sheet2.xml\"/></Relationships>");
        entries.put("xl/worksheets/sheet1.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>단위: 원</t></is></c></row>
                <row r="3"><c r="A3" t="inlineStr"><is><t>조직명</t></is></c>
                <c r="B3" t="inlineStr"><is><t>관리분류</t></is></c>
                <c r="C3" t="inlineStr"><is><t>중분류</t></is></c>
                <c r="D3" t="inlineStr"><is><t>소분류</t></is></c>
                <c r="E3" t="inlineStr"><is><t>거래내역 및 산출근거</t></is></c>
                <c r="F3" t="inlineStr"><is><t>2031년 실적</t></is></c>
                <c r="G3" t="inlineStr"><is><t>2031년 실적</t></is></c></row>
                <row r="4"><c r="F4" t="inlineStr"><is><t>1월</t></is></c>
                <c r="G4" t="inlineStr"><is><t>2월</t></is></c></row>
                <row r="5"><c r="A5" t="inlineStr"><is><t>합성조직</t></is></c>
                <c r="B5" t="inlineStr"><is><t>매출</t></is></c>
                <c r="C5" t="inlineStr"><is><t>3100 서비스</t></is></c>
                <c r="D5" t="inlineStr"><is><t>월정액</t></is></c>
                <c r="E5" t="inlineStr"><is><t>합성 근거 [ID:memory-row; 수정:1]</t></is></c>
                <c r="F5"><v>123.4500</v></c><c r="G5"><v>0</v></c></row>
                </sheetData></worksheet>
                """);
        entries.put("xl/worksheets/sheet2.xml", """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>사업부코드</t></is></c>
                <c r="B1" t="inlineStr"><is><t>사업부명</t></is></c></row>
                <row r="2"><c r="A2" t="inlineStr"><is><t>D100</t></is></c>
                <c r="B2" t="inlineStr"><is><t>합성조직</t></is></c></row>
                </sheetData></worksheet>
                """);
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
