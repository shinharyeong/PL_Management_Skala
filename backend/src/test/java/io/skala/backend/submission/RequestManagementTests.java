package io.skala.backend.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 요청 관리·분류 목록·조직별 작업 연결을 별도 H2에서 검증한다. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:request_management;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
    "app.local-auth.planner-email=request-planner@example.test",
    "app.local-auth.planner-token=request-planner-token-at-least-32-characters",
    "app.local-auth.organization-email=request-manager@example.test",
    "app.local-auth.organization-token=request-manager-token-at-least-32-characters"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@Transactional
class RequestManagementTests {
    private static final String PLANNER = "Bearer request-planner-token-at-least-32-characters";
    private static final String MANAGER = "Bearer request-manager-token-at-least-32-characters";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    /** 사용자와 조직만 합성 메모리 DB에 준비한다.
     * Args: 없음. Returns: 없음. Raises: DB 오류. Side Effects: 테스트 후 롤백되는 H2 행 생성.
     */
    @BeforeEach
    void prepare() {
        jdbc.sql("INSERT INTO organization(id,code,name,active) VALUES (1,'D100','합성조직',TRUE),(2,'D200','다른조직',TRUE)").update();
        jdbc.sql("""
                INSERT INTO app_user(id,email,name,role,organization_id,active) VALUES
                (10,'request-planner@example.test','기획','PLANNER',NULL,TRUE),
                (11,'request-manager@example.test','담당','ORG_MANAGER',1,TRUE),
                (12,'request-other@example.test','다른 담당','ORG_MANAGER',2,TRUE)
                """).update();
    }

    /** 선택 조직만 요청함에 노출하면서 전사 마스터 필수 집합은 유지하고 후속 조직을 추가 요청한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·JSON·검증 오류. Side Effects: 합성 H2 요청 대상의 선택 상태만 변경한다.
     */
    @Test
    void selectedOrganizationsRemainSeparateFromRequiredMasterSources() throws Exception {
        var response=mvc.perform(post("/api/v1/submission-requests").header("Authorization",PLANNER).header("Idempotency-Key","selected-request-01")
                .contentType("application/json").content("{\"type\":\"ANNUAL_PLAN\",\"year\":2031,\"organizationIds\":[\"2\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.requiredOrganizationCount").value(2))
                .andExpect(jsonPath("$.selectedTargetCount").value(1)).andExpect(jsonPath("$.targets[0].selected").value(false))
                .andExpect(jsonPath("$.targets[1].recipients[0].email").value("request-other@example.test"))
                .andReturn().getResponse().getContentAsByteArray();
        var request=mapper.readTree(response);String id=request.path("id").asText();
        mvc.perform(get("/api/v1/submission-requests").header("Authorization",MANAGER)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/submission-requests/"+id).header("Authorization",MANAGER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/submission-requests/"+id+"/annual-master").header("Authorization",PLANNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requiredOrganizationCount").value(2));
        var patchBody=Map.of("expectedStateToken",request.path("stateToken").asText(),"organizationIds",java.util.List.of("1"));
        mvc.perform(patch("/api/v1/submission-requests/"+id).header("Authorization",PLANNER).header("Idempotency-Key","select-additional-01")
                .contentType("application/json").content(mapper.writeValueAsBytes(patchBody)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selectedTargetCount").value(2));
        mvc.perform(get("/api/v1/submission-requests/"+id).header("Authorization",MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.targets[0].selected").value(true));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_target WHERE request_id=? AND excluded=FALSE").param(Long.parseLong(id)).query(Integer.class).single()).isEqualTo(2);
    }

    /** PATCH의 생략 유지·null 삭제·상태 토큰·멱등 재전달을 확인한다.
     * Args: 없음. Returns: 없음. Raises: HTTP/JSON/검증 오류.
     * Side Effects: H2 요청만 변경하며 원본 파일·실제 DB는 사용하지 않는다.
     */
    @Test
    void requestPatchPreservesOmittedFieldsClearsNullAndChecksConcurrency() throws Exception {
        JsonNode original = create(2031);
        String requestId = original.path("id").asText();
        String update = mapper.writeValueAsString(Map.of("expectedStateToken", original.path("stateToken").asText(), "message", "변경 안내"));
        JsonNode changed = mapper.readTree(mvc.perform(patch("/api/v1/submission-requests/" + requestId)
                .header("Authorization", PLANNER).header("Idempotency-Key", "request-patch-001")
                .contentType("application/json").content(update)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("변경 안내"))
                .andExpect(jsonPath("$.dueAt").value("2031-12-01T00:00:00Z"))
                .andExpect(jsonPath("$.status").value("OPEN")).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(patch("/api/v1/submission-requests/" + requestId)
                .header("Authorization", PLANNER).header("Idempotency-Key", "request-patch-001")
                .contentType("application/json").content(update)).andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));
        mvc.perform(patch("/api/v1/submission-requests/" + requestId)
                .header("Authorization", PLANNER).header("Idempotency-Key", "request-stale-001")
                .contentType("application/json").content(update)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CHANGED"));
        var cleared = mapper.createObjectNode().put("expectedStateToken", changed.path("stateToken").asText());
        cleared.putNull("message").putNull("dueAt");
        mvc.perform(patch("/api/v1/submission-requests/" + requestId)
                .header("Authorization", PLANNER).header("Idempotency-Key", "request-clear-001")
                .contentType("application/json").content(mapper.writeValueAsBytes(cleared)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").isEmpty())
                .andExpect(jsonPath("$.dueAt").isEmpty()).andExpect(jsonPath("$.targets.length()").value(2));
    }

    /** 요청 개폐가 새 접수에 적용되고 확정 연간 계획은 다시 열리지 않는지 확인한다.
     * Args: 없음. Returns: 없음. Raises: HTTP/JSON/DB 검증 오류.
     * Side Effects: H2 요청·확정 상태만 변경하고 메모리 바이트로 업로드 경계를 검사한다.
     */
    @Test
    void closedRequestBlocksUploadAndConfirmedAnnualRequestCannotReopen() throws Exception {
        JsonNode original = create(2031);
        String requestId = original.path("id").asText();
        JsonNode closed = changeStatus(requestId, original.path("stateToken").asText(), "CLOSED", "request-close-001");
        mvc.perform(multipart("/api/v1/submission-requests/" + requestId + "/submissions")
                .file(new MockMultipartFile("file", "D100_2031-PLAN.xlsx", "application/octet-stream", new byte[]{'P','K',3,4}))
                .header("Authorization", MANAGER).header("Idempotency-Key", "closed-upload-001"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_CLOSED"));
        mvc.perform(get("/api/v1/submission-requests/" + requestId).header("Authorization", MANAGER))
                .andExpect(jsonPath("$.myWorkflow.canUpload").value(false))
                .andExpect(jsonPath("$.myWorkflow.blockingCode").value("REQUEST_CLOSED"));
        JsonNode reopened = changeStatus(requestId, closed.path("stateToken").asText(), "OPEN", "request-open-001");
        JsonNode reclosed = changeStatus(requestId, reopened.path("stateToken").asText(), "CLOSED", "request-close-002");
        jdbc.sql("UPDATE annual_plan SET status='CONFIRMED' WHERE id=?").param(original.path("annualPlanId").asLong()).update();
        mvc.perform(patch("/api/v1/submission-requests/" + requestId)
                .header("Authorization", PLANNER).header("Idempotency-Key", "confirmed-open-001")
                .contentType("application/json").content(mapper.writeValueAsBytes(Map.of(
                        "expectedStateToken", reclosed.path("stateToken").asText(), "status", "OPEN"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ANNUAL_PLAN_LOCKED"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submission").query(Long.class).single()).isZero();
    }

    /** 권한·수정 가능 필드·자료형·시간대 검사를 우회할 수 없는지 확인한다.
     * Args: 없음. Returns: 없음. Raises: HTTP/JSON/검증 오류. Side Effects: H2 요청 생성 후 잘못된 명령만 전송한다.
     */
    @Test
    void requestPatchRejectsUnauthorizedUnknownAndMalformedFields() throws Exception {
        JsonNode request = create(2031);
        String route = "/api/v1/submission-requests/" + request.path("id").asText();
        String token = request.path("stateToken").asText();
        mvc.perform(patch(route).header("Authorization", MANAGER).header("Idempotency-Key", "request-role-001")
                .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("expectedStateToken", token, "status", "CLOSED"))))
                .andExpect(status().isForbidden());
        for (JsonNode invalid : new JsonNode[]{
                mapper.createObjectNode().put("expectedStateToken", token),
                mapper.createObjectNode().put("expectedStateToken", token).put("year", 2032),
                mapper.createObjectNode().put("expectedStateToken", token).putNull("status"),
                mapper.createObjectNode().put("expectedStateToken", token).put("message", 12),
                mapper.createObjectNode().put("expectedStateToken", token).put("dueAt", "2031-12-01T00:00:00"),
                mapper.createObjectNode().put("expectedStateToken", token).put("message", "a".repeat(5001))}) {
            mvc.perform(patch(route).header("Authorization", PLANNER).header("Idempotency-Key", "request-invalid-001")
                    .contentType("application/json").content(mapper.writeValueAsBytes(invalid)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(jdbc.sql("SELECT status FROM submission_request WHERE id=?").param(request.path("id").asLong()).query(String.class).single()).isEqualTo("OPEN");
    }

    /** 보고 항목의 계층·필터·페이지와 사용자/필터별 커서 격리를 확인한다.
     * Args: 없음. Returns: 없음. Raises: HTTP/JSON/DB 검증 오류. Side Effects: H2 합성 보고 항목만 생성한다.
     */
    @Test
    void reportItemsExposeRealHierarchyAndScopePagination() throws Exception {
        jdbc.sql("""
                INSERT INTO report_item(id,code,name,kind,is_leaf,display_order,active) VALUES
                (101,'COST','비용','COST',FALSE,1,TRUE),(102,'REVENUE','매출','REVENUE',TRUE,3,TRUE),
                (103,'OLD','이전 항목','COST',TRUE,4,FALSE)
                """).update();
        jdbc.sql("INSERT INTO report_item(id,code,name,parent_id,kind,is_leaf,display_order,active) VALUES (104,'COST_A','세부 비용',101,'COST',TRUE,2,TRUE)").update();
        JsonNode page = mapper.readTree(mvc.perform(get("/api/v1/report-items?active=true&kind=COST&limit=1")
                .header("Authorization", MANAGER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("101")).andExpect(jsonPath("$.items[0].isLeaf").value(false))
                .andReturn().getResponse().getContentAsByteArray());
        mvc.perform(get("/api/v1/report-items").param("active", "true").param("kind", "COST").param("limit", "1")
                .param("cursor", page.path("nextCursor").asText()).header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value("104"))
                .andExpect(jsonPath("$.items[0].parentId").value("101")).andExpect(jsonPath("$.nextCursor").isEmpty());
        mvc.perform(get("/api/v1/report-items").param("active", "true").param("kind", "COST").param("limit", "1")
                .param("cursor", page.path("nextCursor").asText()).header("Authorization", PLANNER)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/report-items?active=false").header("Authorization", PLANNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/report-items?kind=UNKNOWN").header("Authorization", PLANNER)).andExpect(status().isBadRequest());
    }

    /** 기존 완료본과 새 오류 초안을 분리하고 다른 조직 작업을 요청함에 섞지 않는지 확인한다.
     * Args: 없음. Returns: 없음. Raises: HTTP/JSON/DB 검증 오류.
     * Side Effects: 합성 H2 제출·작업만 구성하고 사용자별 목록을 읽는다.
     */
    @Test
    void workflowShowsFreshDraftSeparatelyAndBlocksAnotherPendingRequest() throws Exception {
        JsonNode request = create(2031);
        long requestId = request.path("id").asLong();
        seedSubmission(requestId, 201, 1, "APPROVED", "2031-01-01T00:00:00Z", "2031-01-03T00:00:00Z", "SUCCEEDED");
        seedSubmission(requestId, 202, 1, "DRAFT", "2031-01-02T00:00:00Z", null, "NEEDS_REVIEW");
        mvc.perform(get("/api/v1/submission-requests/" + requestId).header("Authorization", PLANNER))
                .andExpect(jsonPath("$.targets[0].latestSubmittedSubmissionId").value("201"))
                .andExpect(jsonPath("$.targets[0].latestDraftSubmissionId").isEmpty());
        seedSubmission(requestId, 203, 1, "DRAFT", "2031-01-04T00:00:00Z", null, "NEEDS_REVIEW");
        seedSubmission(requestId, 204, 2, "DRAFT", "2031-01-05T00:00:00Z", null, "FAILED");
        mvc.perform(get("/api/v1/submission-requests/" + requestId).header("Authorization", PLANNER))
                .andExpect(jsonPath("$.targets[0].latestSubmittedSubmissionId").value("201"))
                .andExpect(jsonPath("$.targets[0].latestDraftSubmissionId").value("203"))
                .andExpect(jsonPath("$.targets[0].latestDraftParsingStatus").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.targets[0].draftCount").value(2));
        mvc.perform(get("/api/v1/submission-requests?year=2031").header("Authorization", MANAGER))
                .andExpect(jsonPath("$.items[0].targetCount").value(1))
                .andExpect(jsonPath("$.items[0].myWorkflow.latestDraftSubmissionId").value("203"))
                .andExpect(jsonPath("$.items[0].myWorkflow.unansweredCount").isEmpty())
                .andExpect(jsonPath("$.items[0].myWorkflow.canUpload").value(true));
        JsonNode other = create(2032);
        seedSubmission(other.path("id").asLong(), 205, 1, "PENDING", "2032-01-01T00:00:00Z", "2032-01-02T00:00:00Z", "SUCCEEDED");
        mvc.perform(get("/api/v1/submission-requests/" + requestId).header("Authorization", MANAGER))
                .andExpect(jsonPath("$.myWorkflow.canUpload").value(true))
                .andExpect(jsonPath("$.myWorkflow.blockingCode").isEmpty())
                .andExpect(jsonPath("$.myWorkflow.blockingSubmissionId").isEmpty());
        mvc.perform(get("/api/v1/submission-requests/"+other.path("id").asText()).header("Authorization",MANAGER))
                .andExpect(jsonPath("$.myWorkflow.canUpload").value(false))
                .andExpect(jsonPath("$.myWorkflow.blockingCode").value("PENDING_ALREADY_EXISTS"));
    }

    /** 조직의 파싱 대기 한도에 도달하면 요청함과 실제 접수의 차단 조건을 일치시킨다.
     * Args: 없음. Returns: 없음. Raises: HTTP·JSON·DB 검증 오류.
     * Side Effects: 합성 H2 작업을 만들고 한 작업만 완료 상태로 바꾸며 원본·실제 DB는 사용하지 않는다.
     */
    @Test
    void workflowBlocksUploadAtOrganizationParsingLimitAndReopensAfterCompletion() throws Exception {
        JsonNode request = create(2031);
        long requestId = request.path("id").asLong();
        for (long id = 301; id <= 310; id++) seedSubmission(requestId, id, 1, "DRAFT", "2031-01-01T00:00:00Z", null,
                id == 310 ? "RUNNING" : "QUEUED");
        seedSubmission(requestId, 311, 2, "DRAFT", "2031-01-01T00:00:00Z", null, "QUEUED");
        mvc.perform(get("/api/v1/submission-requests/" + requestId).header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.myWorkflow.canUpload").value(false))
                .andExpect(jsonPath("$.myWorkflow.blockingCode").value("PARSER_BUSY"))
                .andExpect(jsonPath("$.myWorkflow.blockingSubmissionId").isEmpty());
        mvc.perform(multipart("/api/v1/submission-requests/" + requestId + "/submissions")
                .file(new MockMultipartFile("file", "D100_2031-PLAN.xlsx", "application/octet-stream", new byte[]{'P','K',3,4}))
                .header("Authorization", MANAGER).header("Idempotency-Key", "busy-workflow-upload-001"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PARSER_BUSY"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submission WHERE organization_id=1").query(Long.class).single()).isEqualTo(10);
        jdbc.sql("UPDATE parsing_job SET status='SUCCEEDED' WHERE submission_id=310").update();
        mvc.perform(get("/api/v1/submission-requests/" + requestId).header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.myWorkflow.canUpload").value(true))
                .andExpect(jsonPath("$.myWorkflow.blockingCode").isEmpty());
    }

    /** 서로 다른 가상 연도의 요청을 정상 API로 생성한다.
     * Args: year 가상 연도. Returns: 요청 JSON. Raises: HTTP/JSON 검증 오류.
     * Side Effects: H2 계획·요청·대상·멱등 행을 생성한다.
     */
    private JsonNode create(int year) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/submission-requests")
                .header("Authorization", PLANNER).header("Idempotency-Key", "request-create-" + year)
                .contentType("application/json").content(mapper.writeValueAsBytes(Map.of(
                        "type", "ANNUAL_PLAN", "year", year, "message", "최초 안내", "dueAt", "2031-12-01T00:00:00Z"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
    }

    /** 현재 토큰으로 요청 상태를 변경한다.
     * Args: requestId 요청, token 기대 토큰, value 새 상태, key 멱등 키.
     * Returns: 변경된 요청 JSON. Raises: HTTP/JSON 검증 오류. Side Effects: H2 요청만 변경한다.
     */
    private JsonNode changeStatus(String requestId, String token, String value, String key) throws Exception {
        return mapper.readTree(mvc.perform(patch("/api/v1/submission-requests/" + requestId)
                .header("Authorization", PLANNER).header("Idempotency-Key", key).contentType("application/json")
                .content(mapper.writeValueAsBytes(Map.of("expectedStateToken", token, "status", value))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(value))
                .andReturn().getResponse().getContentAsByteArray());
    }

    /** 요청함에 필요한 합성 제출·파싱 상태를 원본 파일 없이 구성한다.
     * Args: requestId 요청, submissionId 제출, organizationId 조직, status 제출 상태,
     * uploadedAt 업로드 시각, submittedAt 완료 시각/null, parsingStatus 파싱 상태.
     * Returns: 없음. Raises: DB 오류. Side Effects: 롤백되는 H2 원본 바이트·제출·작업만 생성한다.
     */
    private void seedSubmission(long requestId, long submissionId, long organizationId, String status,
            String uploadedAt, String submittedAt, String parsingStatus) {
        String objectKey = "request-fixture-" + submissionId;
        jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES (?,'application/octet-stream',?)").params(objectKey, new byte[]{1}).update();
        jdbc.sql("""
                INSERT INTO submission(id,request_id,organization_id,seq,status,original_filename,source_object_key,source_sha256,uploaded_by,uploaded_at,submitted_at)
                VALUES (?,?,?,?,?,'synthetic.xlsx',?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
                """).params(submissionId, requestId, organizationId, "DRAFT".equals(status) ? null : 1, status,
                        objectKey, "a".repeat(64), organizationId == 1 ? 11 : 12, uploadedAt, submittedAt).update();
        jdbc.sql("INSERT INTO parsing_job(submission_id,status,engine,attempt_no) VALUES (?,?,'excel_parser',1)").params(submissionId, parsingStatus).update();
    }
}
