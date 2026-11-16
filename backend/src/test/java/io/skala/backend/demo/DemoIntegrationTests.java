package io.skala.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.skala.backend.parser.ParsingWorker;
import io.skala.backend.parser.PythonParserClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 별도 메모리 DB에서 체험 자료·세션 보안·실제 파서 미리보기를 연결한다. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:demo_bridge;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
    "app.local-auth.planner-email=demo-planner@example.test",
    "app.local-auth.planner-token=planner-demo-token-at-least-32-characters",
    "app.local-auth.organization-email=demo-manager@example.test",
    "app.local-auth.organization-token=manager-demo-token-at-least-32-characters",
    "app.demo.frontend-origin=http://localhost:5173,http://127.0.0.1:5173"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local", "demo"})
@Transactional
class DemoIntegrationTests {
    private static final String PLANNER = "Bearer planner-demo-token-at-least-32-characters";
    private static final String MANAGER = "Bearer manager-demo-token-at-least-32-characters";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired DemoBootstrap bootstrap;
    @Autowired PythonParserClient parser;
    @Autowired TransactionTemplate transactions;

    /** 재기동이 합성 조직·사용자·요청을 중복 생성하지 않음을 확인한다.
     * Args: 없음. Returns: 없음. Raises: 검증/DB 오류.
     * Side Effects: 메모리 DB 조회 및 멱등 부트스트랩 실행; 테스트 후 롤백.
     */
    @Test
    void bootstrapIsIdempotentAndDoesNotCreateApprovals() {
        bootstrap.run(new DefaultApplicationArguments());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM organization").query(Long.class).single()).isEqualTo(17);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM app_user").query(Long.class).single()).isEqualTo(18);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submission_request").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_target").query(Long.class).single()).isEqualTo(17);
        assertThat(jdbc.sql("SELECT status FROM annual_plan WHERE year=2027").query(String.class).single()).isEqualTo("COLLECTING");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submission").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM annual_plan WHERE year=2026").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM organization WHERE code IN ('D100','D200')")
                .query(Long.class).single()).isZero();
        for (int index = 0; index < DemoAccounts.ORGANIZATION_CODES.size(); index++) {
            assertThat(jdbc.sql("SELECT name FROM organization WHERE code=?")
                    .param(DemoAccounts.ORGANIZATION_CODES.get(index)).query(String.class).single())
                    .isEqualTo("사업부" + (index + 1));
        }
    }

    /** 과거 데모 자료의 참조를 보존하면서 수집 중 요청을 17개 사업부 대상으로 보정한다.
     * Args: 없음. Returns: 없음. Raises: 검증/DB 오류.
     * Side Effects: 메모리 DB에 과거 데모 조직·사용자·제출을 구성하고 부트스트랩을 재실행하며 테스트 후 롤백.
     */
    @Test
    void bootstrapRetiresLegacyDemoOrganizationsWithoutDeletingHistory() {
        jdbc.sql("INSERT INTO organization(code,name,active) VALUES ('D100','네트워크인프라부문',TRUE),('D200','미디어사업부',TRUE)").update();
        jdbc.sql("""
                INSERT INTO app_user(email,name,role,organization_id,active)
                SELECT 'demo-manager@example.test','조직 담당자 (데모)','ORG_MANAGER',id,TRUE
                FROM organization WHERE code='D100'
                """).update();
        jdbc.sql("DELETE FROM request_target WHERE request_id=?").param(Long.parseLong(requestId())).update();
        jdbc.sql("""
                INSERT INTO request_target(request_id,organization_id)
                SELECT ?,id FROM organization WHERE code IN ('D100','D200')
                """).param(Long.parseLong(requestId())).update();
        jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES ('legacy-demo-source','application/octet-stream',?)")
                .param(new byte[]{1, 2, 3}).update();
        jdbc.sql("""
                INSERT INTO submission(request_id,organization_id,status,original_filename,source_object_key,
                                       source_sha256,uploaded_by,uploaded_at)
                SELECT ?,u.organization_id,'DRAFT','D100_2027-PLAN.xlsx','legacy-demo-source',?,u.id,CURRENT_TIMESTAMP
                FROM app_user u WHERE u.email='demo-manager@example.test'
                """).params(Long.parseLong(requestId()), "a".repeat(64)).update();
        bootstrap.run(new DefaultApplicationArguments());
        bootstrap.run(new DefaultApplicationArguments());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM organization WHERE active=TRUE").query(Long.class).single()).isEqualTo(17);
        assertThat(jdbc.sql("SELECT active FROM app_user WHERE email='demo-manager@example.test'").query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_target WHERE request_id=? AND excluded=FALSE")
                .param(Long.parseLong(requestId())).query(Long.class).single()).isEqualTo(17);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_target WHERE request_id=? AND excluded=TRUE")
                .param(Long.parseLong(requestId())).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submission WHERE source_object_key='legacy-demo-source'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT payload FROM stored_object WHERE object_key='legacy-demo-source'")
                .query(byte[].class).single()).containsExactly(new byte[]{1, 2, 3});
    }

    /** 연간 계획이 확정된 요청의 대상 구성과 제외 상태를 재시작으로 변경하지 않는다.
     * Args: 없음. Returns: 없음. Raises: 검증/DB 오류.
     * Side Effects: 메모리 DB의 확인용 확정 계획·과거 대상을 구성하고 테스트 후 롤백.
     */
    @Test
    void bootstrapPreservesConfirmedPlanTargets() {
        jdbc.sql("INSERT INTO organization(code,name,active) VALUES ('D100','네트워크인프라부문',TRUE)").update();
        jdbc.sql("DELETE FROM request_target WHERE request_id=?").param(Long.parseLong(requestId())).update();
        jdbc.sql("INSERT INTO request_target(request_id,organization_id) SELECT ?,id FROM organization WHERE code='D100'")
                .param(Long.parseLong(requestId())).update();
        jdbc.sql("UPDATE annual_plan SET status='CONFIRMED' WHERE year=2027").update();
        bootstrap.run(new DefaultApplicationArguments());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_target WHERE request_id=? AND excluded=FALSE")
                .param(Long.parseLong(requestId())).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_target WHERE request_id=? AND excluded=TRUE")
                .param(Long.parseLong(requestId())).query(Long.class).single()).isZero();
    }

    /** 연간 마스터를 발행한 요청은 계획이 수집 중이어도 대상 구성과 제외 상태를 보존한다.
     * Args: 없음. Returns: 없음. Raises: HTTP/검증/DB 오류.
     * Side Effects: 메모리 DB에 대상·승인본·마스터 참조 fixture를 구성하고 부트스트랩을 실행하며 테스트 후 롤백.
     */
    @Test
    void bootstrapPreservesPublishedMasterTargets() throws Exception {
        long requestId = Long.parseLong(requestId());
        long submissionId = Long.parseLong(upload(Long.toString(requestId), template(Long.toString(requestId))));
        jdbc.sql("UPDATE submission SET status='APPROVED',seq=1 WHERE id=?").param(submissionId).update();
        jdbc.sql("INSERT INTO organization(code,name,active) VALUES ('D100','네트워크인프라부문',TRUE)").update();
        jdbc.sql("DELETE FROM request_target WHERE request_id=? AND organization_id=(SELECT id FROM organization WHERE code='F12')")
                .param(requestId).update();
        jdbc.sql("INSERT INTO request_target(request_id,organization_id) SELECT ?,id FROM organization WHERE code='D100'")
                .param(requestId).update();
        jdbc.sql("""
                INSERT INTO annual_master(request_id,seq,trigger_submission_id,content_sha256,calculation_version,created_by,created_at)
                SELECT id,1,?,?,'test-fixture',requested_by,CURRENT_TIMESTAMP FROM submission_request WHERE id=?
                """).params(submissionId, "b".repeat(64), requestId).update();
        jdbc.sql("""
                INSERT INTO annual_master_source(master_id,organization_id,submission_id)
                SELECT m.id,s.organization_id,s.id FROM annual_master m JOIN submission s ON s.id=m.trigger_submission_id
                WHERE m.request_id=?
                """).param(requestId).update();
        bootstrap.run(new DefaultApplicationArguments());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_target WHERE request_id=? AND excluded=FALSE")
                .param(requestId).query(Long.class).single()).isEqualTo(17);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_target WHERE request_id=? AND excluded=TRUE")
                .param(requestId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_target WHERE request_id=? AND organization_id=(SELECT id FROM organization WHERE code='F12')")
                .param(requestId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM annual_master_source WHERE submission_id=?")
                .param(submissionId).query(Long.class).single()).isEqualTo(1);
    }

    /** 설정 이메일에 기존 다른 사용자가 있으면 이름을 덮어쓰지 않고 실패한다.
     * Args: 없음. Returns: 없음. Raises: 검증/DB 오류.
     * Side Effects: 메모리 DB의 테스트 이름만 바꾸며 테스트 후 롤백.
     */
    @Test
    void bootstrapRejectsExistingEmailConflictWithoutOverwriting() {
        jdbc.sql("UPDATE app_user SET name='보존할 기존 이름' WHERE email='demo-planner@example.test'").update();
        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT name FROM app_user WHERE email='demo-planner@example.test'").query(String.class).single())
                .isEqualTo("보존할 기존 이름");
    }

    /** 허용된 역할·Origin·Host·JSON 요청만 체험 자격을 받는지 확인한다.
     * Args: 없음. Returns: 없음. Raises: HTTP/검증 오류.
     * Side Effects: 메모리 HTTP·DB 읽기; 실제 토큰이나 파일을 저장하지 않는다.
     */
    @Test
    void sessionRequiresAllowedOriginHostAndJson() throws Exception {
        mvc.perform(post("/api/dev/session").header("Origin", "http://localhost:5173").header("Host", "localhost:5173")
                .contentType("application/json").content("{\"role\":\"ORG_MANAGER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actor.role").value("ORG_MANAGER"))
                .andExpect(jsonPath("$.actor.organization.code").value("F10"))
                .andExpect(jsonPath("$.organizations.length()").value(17))
                .andExpect(jsonPath("$.token").value("manager-demo-token-at-least-32-characters"));
        mvc.perform(post("/api/dev/session").header("Origin", "http://127.0.0.1:5173").header("Host", "localhost:8080")
                .contentType("application/json").content("{\"role\":\"PLANNER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actor.role").value("PLANNER"))
                .andExpect(jsonPath("$.organizations.length()").value(0));
        mvc.perform(post("/api/dev/session").header("Origin", "https://other.example").header("Host", "localhost:5173")
                .contentType("application/json").content("{\"role\":\"PLANNER\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DEMO_ORIGIN_FORBIDDEN"));
        mvc.perform(post("/api/dev/session").header("Origin", "http://localhost:5173").header("Host", "other.example:5173")
                .contentType("application/json").content("{\"role\":\"PLANNER\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/dev/session").header("Host", "localhost:8080")
                .contentType("application/json").content("{\"role\":\"PLANNER\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/dev/session").header("Origin", "http://localhost:5173").header("Host", "localhost:5173")
                .contentType("text/plain").content("{\"role\":\"PLANNER\"}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    /** 조직 선택이 별도 자격에 묶이고 이전 세션의 소속·기존 요청 대상은 유지되는지 확인한다.
     * Args: 없음. Returns: 없음. Raises: HTTP/DB/검증 오류.
     * Side Effects: 메모리 HTTP·DB 읽기; 실제 토큰·파일을 출력하거나 저장하지 않는다.
     */
    @Test
    void organizationSelectionKeepsEachTokenAndExistingRequestIsolated() throws Exception {
        JsonNode first = organizationSession("F10");
        JsonNode second = organizationSession("F12");
        String firstToken = first.path("token").asText();
        String secondToken = second.path("token").asText();
        assertThat(firstToken.length()).isGreaterThanOrEqualTo(32);
        assertThat(firstToken.equals(secondToken)).isFalse();
        assertThat(firstToken.equals("manager-demo-token-at-least-32-characters")).isTrue();
        assertThat(first.path("organizations").findValuesAsText("code"))
                .containsExactly("F10", "F12", "F20", "F40", "F39", "F50", "F16", "F13", "F25",
                        "F43", "F42", "F44", "F73", "F38", "F66", "F36", "F37");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.organization.code").value("F10"));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.organization.code").value("F12"));
        mvc.perform(get("/api/v1/me").header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.organization.code").value("F10"));
        mvc.perform(get("/api/v1/submission-requests/" + requestId()).header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + firstToken + "invalid"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_target WHERE request_id=?")
                .param(Long.parseLong(requestId())).query(Long.class).single()).isEqualTo(17);
    }

    /** 새 연간 요청에 고정된 실제 조직별 권한으로 업로드하고 타 조직 조회를 차단한다.
     * Args: 없음. Returns: 없음. Raises: HTTP/DB/검증 오류.
     * Side Effects: 메모리 DB에 새 요청·합성 파일 초안을 생성하고 테스트 후 롤백.
     */
    @Test
    void addedOrganizationsUploadThroughNormalRequestAndSubmissionApis() throws Exception {
        String firstToken = organizationSession("F10").path("token").asText();
        String secondToken = organizationSession("F12").path("token").asText();
        byte[] created = mvc.perform(post("/api/v1/submission-requests")
                .header("Authorization", PLANNER).header("Idempotency-Key", "demo-added-plan-2026")
                .contentType("application/json").content("{\"type\":\"ANNUAL_PLAN\",\"year\":2026}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.targetCount").value(17))
                .andReturn().getResponse().getContentAsByteArray();
        String requestId = mapper.readTree(created).path("id").asText();
        byte[] bytes = mvc.perform(get("/api/dev/template").param("requestId", requestId)
                .header("Authorization", "Bearer " + firstToken)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        byte[] uploaded = mvc.perform(multipart("/api/v1/submission-requests/" + requestId + "/submissions")
                .file(new MockMultipartFile("file", "F10_2026-PLAN.xlsx", "application/octet-stream", bytes))
                .header("Authorization", "Bearer " + firstToken).header("Idempotency-Key", "demo-added-upload-f10"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsByteArray();
        String submissionId = mapper.readTree(uploaded).path("id").asText();
        mvc.perform(get("/api/v1/submissions/" + submissionId).header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/submission-requests/" + requestId + "/submissions")
                .file(new MockMultipartFile("file", "F12_2026-PLAN.xlsx", "application/octet-stream", bytes))
                .header("Authorization", "Bearer " + firstToken).header("Idempotency-Key", "demo-wrong-org-f12"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_FILENAME"));
    }

    /** 허용 목록 밖 조직·기획자의 조직 선택을 거부하고 비활성 계정을 재확인한다.
     * Args: 없음. Returns: 없음. Raises: HTTP/DB/검증 오류.
     * Side Effects: 메모리 DB에서 테스트 계정만 비활성화하며 테스트 후 롤백.
     */
    @Test
    void invalidOrganizationSelectionAndInactiveCredentialsAreRejected() throws Exception {
        for (String input : new String[]{
                "{\"role\":\"ORG_MANAGER\",\"organizationCode\":\"F99\"}",
                "{\"role\":\"ORG_MANAGER\",\"organizationCode\":\"D100\"}",
                "{\"role\":\"ORG_MANAGER\",\"organizationCode\":\"D200\"}",
                "{\"role\":\"ORG_MANAGER\",\"organizationCode\":\"\"}",
                "{\"role\":\"PLANNER\",\"organizationCode\":\"F10\"}"}) {
            mvc.perform(post("/api/dev/session").header("Origin", "http://localhost:5173")
                    .header("Host", "localhost:5173").contentType("application/json").content(input))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        String token = organizationSession("F10").path("token").asText();
        jdbc.sql("UPDATE app_user SET active=FALSE WHERE email='demo-f10@example.test'").update();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        JsonNode remaining = organizationSession("F12");
        assertThat(remaining.path("organizations").findValuesAsText("code")).doesNotContain("F10");
    }

    /** 발급 후 소속·역할·조직 코드가 바뀌어도 이전 토큰이 새 권한으로 전이되지 않음을 확인한다.
     * Args: 없음. Returns: 없음. Raises: HTTP/DB/검증 오류.
     * Side Effects: 메모리 DB의 테스트 사용자·조직만 변경하며 테스트 후 롤백.
     */
    @Test
    void issuedOrganizationTokenCannotFollowChangedRoleOrOrganization() throws Exception {
        String token = organizationSession("F10").path("token").asText();
        jdbc.sql("UPDATE app_user SET organization_id=(SELECT id FROM organization WHERE code='F12') WHERE email='demo-f10@example.test'").update();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        jdbc.sql("UPDATE app_user SET role='PLANNER',organization_id=NULL WHERE email='demo-f10@example.test'").update();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        jdbc.sql("UPDATE app_user SET role='ORG_MANAGER',organization_id=(SELECT id FROM organization WHERE code='F10') WHERE email='demo-f10@example.test'").update();
        jdbc.sql("UPDATE organization SET code='F99' WHERE code='F10'").update();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    /** 허용 Origin에서 지정 조직의 개발 세션을 메모리로 받는다.
     * Args: code 체험 조직 코드. Returns: 토큰·Actor·조직 목록 JSON.
     * Raises: HTTP/JSON/검증 오류. Side Effects: 메모리 HTTP·DB 읽기; 토큰 로깅·파일 저장 없음.
     */
    private JsonNode organizationSession(String code) throws Exception {
        byte[] response = mvc.perform(post("/api/dev/session").header("Origin", "http://localhost:5173")
                .header("Host", "localhost:5173").contentType("application/json")
                .content(mapper.writeValueAsBytes(java.util.Map.of("role", "ORG_MANAGER", "organizationCode", code))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return mapper.readTree(response);
    }

    /** 합성 양식의 실제 파싱·검증·최종 제출·승인과 정밀 금액·0 보존을 확인한다.
     * Args: 없음. Returns: 없음. Raises: HTTP/파서/검증 오류.
     * Side Effects: 메모리 ZIP·DB·-B Python 프로세스. 파일 fixture 생성 없음.
     */
    @Test
    void templateRunsRealParserAndPreviewKeepsAmountsAsStrings() throws Exception {
        String requestId = requestId();
        byte[] template = template(requestId);
        JsonNode result = parser.parse("F10_2027-PLAN.xlsx", template);
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("summary").path("sheetCount").asInt()).isEqualTo(5);
        assertThat(result.path("summary").path("unresolvedCount").asInt()).isZero();
        assertThat(result.path("summary").path("unusedTemplateRowCount").asInt()).isZero();
        assertThat(result.path("records").findValuesAsText("normalized_value")).contains("1000000.50", "300000.25", "0");
        assertThat(result.path("submissionData").path("owner").path("code").asText()).isEqualTo("F10");
        assertThat(result.path("submissionData").path("reportItems").size()).isEqualTo(2);
        assertThat(result.path("submissionData").path("diagnostics").isEmpty()).isTrue();
        String submissionId = upload(requestId, template);
        mvc.perform(get("/api/v1/submissions/" + submissionId + "/parsed-records").header("Authorization", MANAGER))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PARSING_NOT_READY"));
        new ParsingWorker(jdbc, transactions, parser, mapper, 90).runNext();
        var response = mvc.perform(get("/api/v1/submissions/" + submissionId + "/parsed-records?limit=100").header("Authorization", MANAGER))
                .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode page = mapper.readTree(response.getContentAsByteArray());
        assertThat(page.path("items").findValuesAsText("amount")).contains("1000000.50", "300000.25", "0");
        long monetaryCount = 0;
        for (JsonNode record : page.path("items")) {
            if (!record.path("amount").isNull()) {
                assertThat(record.path("amount").isTextual()).isTrue();
                assertThat(record.path("scenario").asText()).isEqualTo("PLAN");
                monetaryCount++;
            }
        }
        assertThat(monetaryCount).isEqualTo(84);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("source_object_key", "result_object_key", "raw_record_id", "source_id", "standard:");
        JsonNode first = mapper.readTree(mvc.perform(get("/api/v1/submissions/" + submissionId + "/parsed-records?limit=1")
                .header("Authorization", MANAGER)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(first.path("items").size()).isEqualTo(1);
        assertThat(first.path("nextCursor").isTextual()).isTrue();
        mvc.perform(get("/api/v1/submissions/" + submissionId + "/parsed-records").param("limit", "1")
                .param("cursor", first.path("nextCursor").asText()).header("Authorization", MANAGER)).andExpect(status().isOk());
        JsonNode draft = mapper.readTree(mvc.perform(get("/api/v1/submissions/" + submissionId).header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.readiness.canSubmit").value(false))
                .andReturn().getResponse().getContentAsByteArray());
        JsonNode validated = mapper.readTree(mvc.perform(post("/api/v1/submissions/" + submissionId + "/validation")
                .header("Authorization", MANAGER).header("Idempotency-Key", "demo-flow-validation")
                .contentType("application/json").content(mapper.writeValueAsBytes(java.util.Map.of(
                        "expectedStateToken", draft.path("stateToken").asText()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.submission.readiness.canSubmit").value(true))
                .andExpect(jsonPath("$.submission.readiness.requiredCount").value(0))
                .andReturn().getResponse().getContentAsByteArray()).path("submission");
        mvc.perform(get("/api/v1/submissions/" + submissionId + "/financial-values").header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.complete").value(true))
                .andExpect(jsonPath("$.summary.lineCount").value(2)).andExpect(jsonPath("$.summary.valueCount").value(24));
        JsonNode submitted = mapper.readTree(mvc.perform(post("/api/v1/submissions/" + submissionId + "/submit")
                .header("Authorization", MANAGER).header("Idempotency-Key", "demo-flow-submit")
                .contentType("application/json").content(mapper.writeValueAsBytes(java.util.Map.of(
                        "expectedStateToken", validated.path("stateToken").asText()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.seq").value(1)).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(post("/api/v1/submissions/" + submissionId + "/decisions")
                .header("Authorization", PLANNER).header("Idempotency-Key", "demo-flow-approve")
                .contentType("application/json").content(mapper.writeValueAsBytes(java.util.Map.of(
                        "expectedStateToken", submitted.path("stateToken").asText(), "action", "APPROVE"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.submission.status").value("APPROVED"))
                .andExpect(jsonPath("$.submission.seq").value(1))
                .andExpect(jsonPath("$.masterEffects.length()").value(1))
                .andExpect(jsonPath("$.masterEffects[0].action").value("WAITING_INITIAL"))
                .andExpect(jsonPath("$.masterEffects[0].approvedOrganizationCount").value(1))
                .andExpect(jsonPath("$.masterEffects[0].requiredOrganizationCount").value(17));
        mvc.perform(get("/api/v1/submission-requests/" + requestId).header("Authorization", PLANNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.approvedCount").value(1));
    }

    /** 미사용 행 수를 전달하고 구버전 저장 결과와 결과 없는 실패는 0으로 조회한다.
     * Args: 없음. Returns: 없음. Raises: HTTP/JSON/DB/검증 오류.
     * Side Effects: 메모리 DB의 합성 결과만 저장·변경하며 테스트 후 롤백; 파일 생성 없음.
     */
    @Test
    void previewPreservesUnusedTemplateCountAndReadsLegacyResults() throws Exception {
        String requestId = requestId();
        String submissionId = upload(requestId, template(requestId));
        ObjectNode result = (ObjectNode) mapper.readTree("""
                {"protocolVersion":"1.0","status":"SUCCEEDED","errorCode":null,"diagnostics":[],
                 "summary":{"sheetCount":1,"recordCount":1,"mappedCount":0,"unresolvedCount":0,"unusedTemplateRowCount":1},
                 "records":[{"source":{"sheet":"OpEx_세부_IFRS15後","cell":"Q30"},"role":{"kind":"inactive_template"}}]}
                """);
        var controlledParser = org.mockito.Mockito.mock(PythonParserClient.class);
        org.mockito.Mockito.when(controlledParser.parse(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(byte[].class)))
                .thenReturn(result);
        new ParsingWorker(jdbc, transactions, controlledParser, mapper, 90).runNext();
        mvc.perform(get("/api/v1/submissions/" + submissionId + "/parsed-records").header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.unusedTemplateRowCount").value(1))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].cell").value("Q30"));
        ((ObjectNode) result.path("summary")).remove("unusedTemplateRowCount");
        jdbc.sql("UPDATE stored_object SET payload=? WHERE object_key=(SELECT result_object_key FROM parsing_job WHERE submission_id=?)")
                .params(mapper.writeValueAsBytes(result), Long.parseLong(submissionId)).update();
        mvc.perform(get("/api/v1/submissions/" + submissionId + "/parsed-records").header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.unusedTemplateRowCount").value(0))
                .andExpect(jsonPath("$.items.length()").value(1));
        jdbc.sql("UPDATE parsing_job SET status='FAILED',result_object_key=NULL WHERE submission_id=?")
                .param(Long.parseLong(submissionId)).update();
        mvc.perform(get("/api/v1/submissions/" + submissionId + "/parsed-records").header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.unusedTemplateRowCount").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    /** 선택 행 수는 누락·0·양의 정수만 허용하고 실패 응답도 0을 명시한다.
     * Args: 없음. Returns: 없음. Raises: JSON/검증 오류.
     * Side Effects: 메모리 JSON 검증만 수행하며 Python·DB·파일을 변경하지 않는다.
     */
    @Test
    void parserValidatesOptionalUnusedTemplateCount() throws Exception {
        ObjectNode result = (ObjectNode) parser.failure("PARSER_TIMEOUT");
        ObjectNode summary = (ObjectNode) result.path("summary");
        assertThat(summary.path("unusedTemplateRowCount").intValue()).isZero();
        for (String input : new String[]{"0", "345"}) {
            summary.set("unusedTemplateRowCount", mapper.readTree(input));
            Boolean valid = ReflectionTestUtils.invokeMethod(parser, "validResult", result);
            assertThat(valid).isTrue();
        }
        summary.remove("unusedTemplateRowCount");
        Boolean legacyValid = ReflectionTestUtils.invokeMethod(parser, "validResult", result);
        assertThat(legacyValid).isTrue();
        for (String input : new String[]{"-1", "1.5", "\"2\"", "null", "2147483648"}) {
            summary.set("unusedTemplateRowCount", mapper.readTree(input));
            Boolean valid = ReflectionTestUtils.invokeMethod(parser, "validResult", result);
            assertThat(valid).isFalse();
        }
    }

    /** 기획자의 대리 양식 다운로드·타 조직 후보 조회와 잘못된 페이지를 차단한다.
     * Args: 없음. Returns: 없음. Raises: HTTP/검증 오류.
     * Side Effects: 메모리 DB·HTTP만 사용하며 테스트 후 롤백.
     */
    @Test
    void templateAndPreviewKeepRoleAndOrganizationBoundaries() throws Exception {
        String requestId = requestId();
        mvc.perform(get("/api/dev/template").param("requestId", requestId).header("Authorization", PLANNER))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/dev/template").param("requestId", requestId)).andExpect(status().isUnauthorized());
        String submissionId = upload(requestId, template(requestId));
        mvc.perform(get("/api/v1/submissions/" + submissionId + "/parsed-records?limit=0").header("Authorization", MANAGER))
                .andExpect(status().isBadRequest());
        jdbc.sql("UPDATE submission SET organization_id=(SELECT id FROM organization WHERE code='F12') WHERE id=?")
                .param(Long.parseLong(submissionId)).update();
        mvc.perform(get("/api/v1/submissions/" + submissionId + "/parsed-records").header("Authorization", MANAGER))
                .andExpect(status().isNotFound());
    }

    /** 합성 연간 요청 ID를 조회한다.
     * Args: 없음. Returns: 요청 ID 문자열. Raises: DB 오류. Side Effects: 메모리 DB 읽기.
     */
    private String requestId() { return jdbc.sql("SELECT id FROM submission_request WHERE period_key='2027-PLAN'").query(Long.class).single().toString(); }

    /** 인증된 합성 양식 응답을 메모리에 받는다.
     * Args: requestId 요청. Returns: XLSX 바이트. Raises: HTTP/검증 오류. Side Effects: 메모리 HTTP·DB 읽기.
     */
    private byte[] template(String requestId) throws Exception {
        return mvc.perform(get("/api/dev/template").param("requestId", requestId).header("Authorization", MANAGER))
                .andExpect(status().isOk()).andExpect(header().exists("Content-Disposition"))
                .andReturn().getResponse().getContentAsByteArray();
    }

    /** 실제 업로드 API로 합성 원본을 접수한다.
     * Args: requestId 요청, bytes 메모리 양식. Returns: DRAFT ID.
     * Raises: HTTP/검증 오류. Side Effects: 메모리 DB에 원본·작업 등록.
     */
    private String upload(String requestId, byte[] bytes) throws Exception {
        byte[] response = mvc.perform(multipart("/api/v1/submission-requests/" + requestId + "/submissions")
                .file(new MockMultipartFile("file", "F10_2027-PLAN.xlsx", "application/octet-stream", bytes))
                .header("Authorization", MANAGER).header("Idempotency-Key", "demo-test-upload-001"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsByteArray();
        return mapper.readTree(response).path("id").asText();
    }
}
