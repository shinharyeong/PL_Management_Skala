package io.skala.backend.workspace;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.submission.ApiData;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 인증 체험·업무 알림·실제 양식 등록의 HTTP와 DB 경계를 합성 자료로 검증한다. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:workspace_prd;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
    "app.local-auth.planner-email=workspace-planner@example.test",
    "app.local-auth.planner-token=workspace-planner-token-at-least-32-characters",
    "app.local-auth.organization-email=workspace-manager@example.test",
    "app.local-auth.organization-token=workspace-manager-token-at-least-32-characters",
    "app.demo.frontend-origin=http://localhost:5173,http://127.0.0.1:5173"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test","local","demo"})
@Transactional
class WorkspaceTests {
    private static final String PLANNER="Bearer workspace-planner-token-at-least-32-characters";
    private static final String MANAGER="Bearer workspace-manager-token-at-least-32-characters";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    /** 허용 출처·등록 계정·재요청 제한·일회용 코드·로그아웃 폐기를 연결한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·JSON 검증 오류. Side Effects: 합성 사용자와 메모리 인증 세션만 생성·폐기한다.
     */
    @Test
    void emailCodeSessionRequiresAllowedOriginAndIsRevokedOnLogout() throws Exception {
        String email=registerPlanner();
        mvc.perform(post("/api/v1/auth/codes").contentType("application/json").content(mapper.writeValueAsBytes(Map.of("email",email))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/codes").header("Origin","https://outside.example").header("Host","localhost:8080")
                .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("email",email)))).andExpect(status().isForbidden());
        var challenge=challenge(email);String code=challenge.path("localCode").asText();
        assertThat(code).matches("[0-9]{6}");assertThat(challenge.path("delivery").asText()).isEqualTo("LOCAL_PREVIEW");
        assertThat(java.time.Duration.between(java.time.Instant.parse(challenge.path("resendAt").asText()),java.time.Instant.parse(challenge.path("expiresAt").asText())).toSeconds()).isEqualTo(120);
        mvc.perform(post("/api/v1/auth/codes").header("Origin","http://localhost:5173").header("Host","localhost:8080")
                .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("email",email))))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("CODE_RESEND_WAIT"));
        mvc.perform(post("/api/v1/auth/sessions").header("Origin","http://localhost:5173").header("Host","localhost:8080")
                .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("challengeId",challenge.path("challengeId").asText(),"code","000000".equals(code)?"111111":"000000"))))
                .andExpect(status().isBadRequest());
        String bearer=authenticate(challenge);
        mvc.perform(get("/api/v1/me").header("Authorization",bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.role").value("PLANNER"));
        mvc.perform(post("/api/v1/auth/sessions").header("Origin","http://localhost:5173").header("Host","localhost:8080")
                .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("challengeId",challenge.path("challengeId").asText(),"code",code)))).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/auth/session").header("Authorization",bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.loggedOut").value(true));
        mvc.perform(get("/api/v1/me").header("Authorization",bearer)).andExpect(status().isUnauthorized());
    }

    /** 틀린 코드를 다섯 번 입력하면 올바른 코드도 재사용할 수 없고 미등록 계정은 코드를 받지 못한다.
     * Args: 없음. Returns: 없음. Raises: HTTP 검증 오류. Side Effects: 합성 메모리 챌린지만 소진한다.
     */
    @Test
    void invalidAttemptsConsumeChallengeAndUnknownAccountsStayDenied() throws Exception {
        var challenge=challenge(registerPlanner());String code=challenge.path("localCode").asText();
        for(int index=0;index<5;index++)mvc.perform(post("/api/v1/auth/sessions").header("Origin","http://localhost:5173").header("Host","localhost:8080")
                .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("challengeId",challenge.path("challengeId").asText(),"code","000000".equals(code)?"111111":"000000"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/sessions").header("Origin","http://localhost:5173").header("Host","localhost:8080")
                .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("challengeId",challenge.path("challengeId").asText(),"code",code)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/codes").header("Origin","http://localhost:5173").header("Host","localhost:8080")
                .contentType("application/json").content("{\"email\":\"unknown-workspace@example.test\"}")).andExpect(status().isUnauthorized());
    }

    /** 알림은 실제 제출·회신·선정된 미제출 조직으로 계산하며 읽음은 기획자별로만 남긴다.
     * Args: 없음. Returns: 없음. Raises: HTTP·DB 검증 오류. Side Effects: 합성 업무 사실·읽음 기록만 추가한다.
     */
    @Test
    void notificationsUseRealEventsSelectedTargetsAndPerUserReadState() throws Exception {
        long request=requestId(),organization=organizationId("F10"),manager=managerId();
        jdbc.sql("UPDATE submission_request SET due_at='2020-01-01T00:00:00Z' WHERE id=?").param(request).update();
        jdbc.sql("UPDATE request_target SET selected=FALSE WHERE request_id=?").param(request).update();
        jdbc.sql("UPDATE request_target SET selected=TRUE WHERE request_id=? AND organization_id IN (?,?)").params(request,organization,organizationId("F12")).update();
        jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES ('workspace/source','application/octet-stream',?)").param(new byte[]{1}).update();
        jdbc.sql("INSERT INTO submission(id,request_id,organization_id,seq,status,original_filename,source_object_key,source_sha256,uploaded_by,uploaded_at,submitted_by,submitted_at) VALUES (9001,?,?,1,'PENDING','F10_2027-PLAN.xlsx','workspace/source',?,?,CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP)")
                .params(request,organization,"a".repeat(64),manager,manager).update();
        jdbc.sql("INSERT INTO report_item(id,code,name,kind,is_leaf) VALUES (9001,'TEST_REVENUE','합성 매출','REVENUE',TRUE)").update();
        jdbc.sql("INSERT INTO review_item(id,submission_id,report_item_id,year,month,scenario,amount_present,current_amount,change_detected,gap_exceeded,source_fingerprint,context_fingerprint,current_round) VALUES (9001,9001,9001,2027,1,'PLAN',TRUE,100,TRUE,TRUE,?,?,1)")
                .params("a".repeat(64),"b".repeat(64)).update();
        jdbc.sql("INSERT INTO review_explanation(id,review_item_id,round,request_note,requested_by,requested_at,answer,answered_by,answered_at,verdict) VALUES (9001,9001,1,'소명 요청',(SELECT id FROM app_user WHERE email='workspace-planner@example.test'),CURRENT_TIMESTAMP,'검증 소명',?,CURRENT_TIMESTAMP,'PENDING')").param(manager).update();
        JsonNode result=mapper.readTree(mvc.perform(get("/api/v1/notifications").header("Authorization",PLANNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.unreadCount").value(3)).andExpect(jsonPath("$.summary.replyPendingCount").value(1))
                .andExpect(jsonPath("$.summary.overdueOrganizationCount").value(1)).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(get("/api/v1/notifications").header("Authorization",MANAGER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/notifications?type=OVERDUE").header("Authorization",PLANNER))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].organizationCode").value("F12"));
        List<String> ids=new ArrayList<>();for(JsonNode event:result.path("items"))ids.add(event.path("id").asText());
        var body=mapper.writeValueAsBytes(Map.of("eventIds",ids));
        for(int index=0;index<2;index++)mvc.perform(post("/api/v1/notifications/read").header("Authorization",PLANNER).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.unreadCount").value(0));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_read").query(Integer.class).single()).isEqualTo(3);
        String other=authenticate(challenge(registerPlanner()));
        mvc.perform(get("/api/v1/notifications?unreadOnly=true").header("Authorization",other)).andExpect(jsonPath("$.summary.unreadCount").value(3));
        mvc.perform(post("/api/v1/notifications/read").header("Authorization",PLANNER).contentType("application/json").content("{\"eventIds\":[\"invented-event\"]}"))
                .andExpect(status().isBadRequest());
    }

    /** 최초 소명·상속은 회신 알림과 분리하고 완료·제외 이력은 남기되 실제 검토 대기만 센다.
     * Args: 없음. Returns: 없음. Raises: HTTP·DB 검증 오류.
     * Side Effects: 합성 H2 소명·제출 상태와 기획자 읽음 행만 변경한다. 원문 소명은 수정하지 않는다.
     */
    @Test
    void replyNotificationsRequireExplicitOriginalRepliesAndKeepCompletedHistory() throws Exception {
        long request=requestId(),organization=organizationId("F10"),manager=managerId();
        long planner=jdbc.sql("SELECT id FROM app_user WHERE email='workspace-planner@example.test'").query(Long.class).single();
        jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES ('workspace/notification-source','application/octet-stream',?)").param(new byte[]{1}).update();
        jdbc.sql("INSERT INTO submission(id,request_id,organization_id,seq,status,original_filename,source_object_key,source_sha256,uploaded_by,uploaded_at,submitted_by,submitted_at) VALUES (9100,?,?,1,'PENDING','F10_2027-PLAN.xlsx','workspace/notification-source',?,?,CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP)")
                .params(request,organization,"a".repeat(64),manager,manager).update();
        jdbc.sql("INSERT INTO report_item(id,code,name,kind,is_leaf) VALUES (9100,'NOTIFICATION_REVENUE','알림 합성 매출','REVENUE',TRUE)").update();
        for(int month=1;month<=6;month++) {
            long id=9100+month;
            jdbc.sql("INSERT INTO review_item(id,submission_id,report_item_id,year,month,scenario,amount_present,current_amount,change_detected,gap_exceeded,source_fingerprint,context_fingerprint,current_round) VALUES (?,9100,9100,2027,?,'PLAN',TRUE,100,TRUE,TRUE,?,?,1)")
                    .params(id,month,"a".repeat(64),"b".repeat(64)).update();
            jdbc.sql("INSERT INTO review_explanation(id,review_item_id,round,request_note,requested_by,requested_at,answer,answered_by,answered_at,verdict) VALUES (?,?,1,?,?,?,?,?,CURRENT_TIMESTAMP,?)")
                    .params(id,id,month==1?null:"명시적 소명 요청",month==1?null:planner,month==1?null:java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
                            month==6?null:"검증 소명",month==6?null:manager,month==3||month==4?"ACCEPTED":"PENDING").update();
        }
        jdbc.sql("UPDATE review_explanation SET inherited_from_id=9102 WHERE id=9103").update();
        jdbc.sql("UPDATE review_item SET workflow_state='EXCLUDED' WHERE id=9105").update();
        JsonNode first=mapper.readTree(mvc.perform(get("/api/v1/notifications?type=REPLY").header("Authorization",PLANNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.counts.SUBMISSION").value(1))
                .andExpect(jsonPath("$.counts.REPLY").value(3)).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.summary.replyPendingCount").value(1)).andReturn().getResponse().getContentAsByteArray());
        var ids=new HashSet<String>();for(JsonNode event:first.path("items"))ids.add(event.path("id").asText().split(":")[1]);
        assertThat(ids).containsExactlyInAnyOrder("9102","9104","9105");
        String readEvent=first.path("items").get(0).path("id").asText();
        mvc.perform(post("/api/v1/notifications/read").header("Authorization",PLANNER).contentType("application/json")
                .content(mapper.writeValueAsBytes(Map.of("eventIds",List.of(readEvent))))).andExpect(status().isOk());
        for(String status:List.of("APPROVED","REJECTED")) {
            jdbc.sql("UPDATE submission SET status=? WHERE id=9100").param(status).update();
            JsonNode changed=mapper.readTree(mvc.perform(get("/api/v1/notifications?type=REPLY").header("Authorization",PLANNER))
                    .andExpect(jsonPath("$.counts.REPLY").value(3)).andExpect(jsonPath("$.items.length()").value(3))
                    .andExpect(jsonPath("$.summary.replyPendingCount").value(0)).andReturn().getResponse().getContentAsByteArray());
            boolean preserved=false;for(JsonNode event:changed.path("items"))if(readEvent.equals(event.path("id").asText()))preserved=event.path("read").asBoolean();
            assertThat(preserved).isTrue();
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_read").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM review_explanation WHERE answer='검증 소명'").query(Integer.class).single()).isEqualTo(5);
    }

    /** 등록 양식은 원본 바이트·해시·멱등 이력을 보존하며 미선정 조직의 접근과 잘못된 파일을 막는다.
     * Args: 없음. Returns: 없음. Raises: HTTP·ZIP·DB 검증 오류. Side Effects: H2 바이트와 양식 행만 저장하고 디스크 파일은 생성하지 않는다.
     */
    @Test
    void templateRegistrationPreservesBytesAndTargetAuthorization() throws Exception {
        String route="/api/v1/submission-requests/"+requestId()+"/template";byte[] bytes=workbook();
        mvc.perform(get(route).header("Authorization",MANAGER)).andExpect(jsonPath("$.available").value(false));
        mvc.perform(get(route+"/file").header("Authorization",MANAGER)).andExpect(status().isNotFound());
        var file=new MockMultipartFile("file","original-template.xlsx","application/octet-stream",bytes);
        mvc.perform(multipart(route).file(file).header("Authorization",MANAGER).header("Idempotency-Key","template-wrong-role")).andExpect(status().isForbidden());
        mvc.perform(multipart(route).file(file).header("Authorization",PLANNER).header("Idempotency-Key","template-create-once"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sha256").value(ApiData.hash(bytes)));
        mvc.perform(multipart(route).file(file).header("Authorization",PLANNER).header("Idempotency-Key","template-create-once"))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed","true"));
        mvc.perform(get(route+"/file").header("Authorization",MANAGER)).andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control","private, no-store"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_template").query(Integer.class).single()).isEqualTo(1);
        mvc.perform(multipart(route).file(new MockMultipartFile("file","invalid.xlsx","application/octet-stream",new byte[]{'P','K',3,4}))
                .header("Authorization",PLANNER).header("Idempotency-Key","template-invalid-file")).andExpect(status().isBadRequest());
        jdbc.sql("UPDATE request_target SET selected=FALSE WHERE request_id=? AND organization_id=?").params(requestId(),organizationId("F10")).update();
        mvc.perform(get(route+"/file").header("Authorization",MANAGER)).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_template").query(Integer.class).single()).isEqualTo(1);
    }

    /** 격리된 기획 계정을 등록한다. Args: 없음. Returns: 이메일. Raises: DB 오류. Side Effects: 합성 H2 사용자 생성. */
    private String registerPlanner() { String email="workspace-"+UUID.randomUUID()+"@example.test";jdbc.sql("INSERT INTO app_user(email,name,role) VALUES (?,'추가 기획','PLANNER')").param(email).update();return email; }
    /** 허용된 출처에서 코드를 요청한다. Args: email 등록 계정. Returns: 챌린지. Raises: HTTP·JSON 오류. Side Effects: 메모리 챌린지 생성. */
    private JsonNode challenge(String email) throws Exception { return mapper.readTree(mvc.perform(post("/api/v1/auth/codes").header("Origin","http://localhost:5173").header("Host","localhost:8080").contentType("application/json").content(mapper.writeValueAsBytes(Map.of("email",email)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()); }
    /** 로컬 코드로 일회용 인증을 완료한다. Args: challenge 발급 응답. Returns: Bearer 헤더. Raises: HTTP·JSON 오류. Side Effects: 메모리 세션 생성. */
    private String authenticate(JsonNode challenge) throws Exception { return "Bearer "+mapper.readTree(mvc.perform(post("/api/v1/auth/sessions").header("Origin","http://localhost:5173").header("Host","localhost:8080").contentType("application/json").content(mapper.writeValueAsBytes(Map.of("challengeId",challenge.path("challengeId").asText(),"code",challenge.path("localCode").asText())))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("token").asText(); }
    /** 시연 요청 ID를 읽는다. Args: 없음. Returns: ID. Raises: DB 오류. Side Effects: H2 읽기. */
    private long requestId() { return jdbc.sql("SELECT id FROM submission_request WHERE period_key='2027-PLAN'").query(Long.class).single(); }
    /** 사업조직 ID를 읽는다. Args: code 코드. Returns: ID. Raises: DB 오류. Side Effects: H2 읽기. */
    private long organizationId(String code) { return jdbc.sql("SELECT id FROM organization WHERE code=?").param(code).query(Long.class).single(); }
    /** 기본 사업조직 담당자 ID를 읽는다. Args: 없음. Returns: ID. Raises: DB 오류. Side Effects: H2 읽기. */
    private long managerId() { return jdbc.sql("SELECT id FROM app_user WHERE organization_id=? AND role='ORG_MANAGER'").param(organizationId("F10")).query(Long.class).single(); }
    /** 최소 OOXML 구조를 메모리 ZIP으로 만든다. Args: 없음. Returns: 합성 XLSX 바이트. Raises: ZIP 오류. Side Effects: 메모리 바이트만 생성한다. */
    private byte[] workbook() throws Exception {
        var output=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(output)) {
            for(var entry:Map.of("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>","xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"/>").entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
