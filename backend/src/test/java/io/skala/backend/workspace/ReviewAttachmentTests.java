package io.skala.backend.workspace;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.review.ReviewService;
import io.skala.backend.security.Actor;
import io.skala.backend.security.Organization;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

/** 소명 첨부의 권한·고정 회차·파일 형식·멱등성과 토큰 경합을 H2에서 검증한다. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:review_attachments;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
    "app.local-auth.planner-email=attachment-planner@example.test",
    "app.local-auth.planner-token=attachment-planner-token-at-least-32-characters",
    "app.local-auth.organization-email=attachment-manager@example.test",
    "app.local-auth.organization-token=attachment-manager-token-at-least-32-characters"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "local"})
@Transactional
class ReviewAttachmentTests {
    private static final String PLANNER = "Bearer attachment-planner-token-at-least-32-characters";
    private static final String MANAGER = "Bearer attachment-manager-token-at-least-32-characters";
    private static final byte[] PDF = "%PDF-1.4\n% synthetic attachment\n%%EOF".getBytes(StandardCharsets.UTF_8);
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ReviewAttachmentService attachments;
    @Autowired ReviewService reviews;
    private Actor manager;

    /** 파일 없이 합성 소명 재요청 회차를 메모리에 구성한다.
     * Args: 없음. Returns: 없음. Raises: DB 오류. Side Effects: 롤백되는 H2 데이터만 생성한다.
     */
    @BeforeEach
    void prepare() {
        jdbc.sql("INSERT INTO organization(id,code,name) VALUES (1,'D100','사업조직'),(2,'D200','다른조직')").update();
        jdbc.sql("""
                INSERT INTO app_user(id,email,name,role,organization_id) VALUES
                (11,'attachment-planner@example.test','기획','PLANNER',NULL),
                (12,'attachment-manager@example.test','담당','ORG_MANAGER',1),
                (13,'attachment-other@example.test','다른 담당','ORG_MANAGER',2)
                """).update();
        jdbc.sql("INSERT INTO annual_plan(id,year,status) VALUES (1,2031,'CONFIRMED')").update();
        jdbc.sql("INSERT INTO submission_request(id,annual_plan_id,type,month,status,requested_by,requested_at,period_key) VALUES (1,1,'MONTHLY_ACTUAL',1,'OPEN',11,CURRENT_TIMESTAMP,'2031-01')").update();
        jdbc.sql("INSERT INTO request_target(request_id,organization_id) VALUES (1,1)").update();
        jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES ('source/attachment-test','application/octet-stream',?)").param(new byte[]{1}).update();
        jdbc.sql("INSERT INTO submission(id,request_id,organization_id,seq,status,original_filename,source_object_key,source_sha256,uploaded_by,uploaded_at) VALUES (20,1,1,1,'PENDING','synthetic.xlsx','source/attachment-test',?,12,CURRENT_TIMESTAMP)").param("a".repeat(64)).update();
        jdbc.sql("INSERT INTO report_item(id,code,name,kind,is_leaf) VALUES (99,'ATTACH_REV','매출','REVENUE',TRUE)").update();
        jdbc.sql("""
                INSERT INTO review_item(id,submission_id,report_item_id,year,month,scenario,amount_present,current_amount,
                change_detected,gap_exceeded,source_fingerprint,context_fingerprint,current_round,review_policy_version)
                VALUES (100,20,99,2031,1,'ACTUAL',TRUE,120,TRUE,TRUE,?,?,1,'monthly-actual/2.0')
                """).params("a".repeat(64), "b".repeat(64)).update();
        jdbc.sql("INSERT INTO review_explanation(review_item_id,round,request_note,requested_by,requested_at,verdict) VALUES (100,1,'추가 근거 요청',11,CURRENT_TIMESTAMP,'PENDING')").update();
        manager = new Actor("12", "attachment-manager@example.test", "담당", "ORG_MANAGER", new Organization("1", "D100", "사업조직"));
    }

    /** 원문이 그대로 다운로드되고 재시도는 한 번만 저장되며 새 첨부가 토큰에 반영되는지 확인한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·JSON·검증 실패. Side Effects: H2 첨부만 추가한다.
     */
    @Test
    void storesImmutableBytesReplaysOnceAndChangesReviewToken() throws Exception {
        String before = token();
        JsonNode created = upload("attachment-001", before, "근거.pdf", "application/pdf", PDF, 201);
        String attachmentId = created.path("attachment").path("id").asText();
        assertThat(created.path("review").path("stateToken").asText()).isNotEqualTo(before);
        mvc.perform(multipart("/api/v1/review-items/100/attachments")
                .file(new MockMultipartFile("file", "근거.pdf", "application/pdf", PDF))
                .param("round", "1").param("expectedStateToken", before)
                .header("Authorization", MANAGER).header("Idempotency-Key", "attachment-001"))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "true"));
        mvc.perform(get("/api/v1/review-items/100/attachments/" + attachmentId + "/file").header("Authorization", PLANNER))
                .andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, no-store")).andExpect(content().bytes(PDF));
        mvc.perform(get("/api/v1/review-items/100/attachments").header("Authorization", MANAGER))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].round").value(1));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM review_attachment").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT current_amount FROM review_item WHERE id=100").query(java.math.BigDecimal.class).single()).isEqualByComparingTo("120");
    }

    /** 오래된 화면 토큰과 답변 저장 후 첨부 추가를 차단한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·검증 실패. Side Effects: 첫 첨부와 답변만 H2에 저장한다.
     */
    @Test
    void staleTokensAndSubmittedAnswersCannotReceiveNewAttachments() throws Exception {
        String stale = token();
        upload("attachment-first", stale, "근거.pdf", "application/pdf", PDF, 201);
        JsonNode conflict = upload("attachment-stale", stale, "추가.pdf", "application/pdf", PDF, 409);
        assertThat(conflict.path("code").asText()).isEqualTo("RESOURCE_CHANGED");
        reviews.answer(manager, "100", "attachment-answer", new ReviewService.AnswerInput(token(), 1, "계약 근거를 첨부했습니다."));
        JsonNode locked = upload("attachment-locked", token(), "추가.pdf", "application/pdf", PDF, 409);
        assertThat(locked.path("code").asText()).isEqualTo("EXPLANATION_LOCKED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM review_attachment").query(Integer.class).single()).isEqualTo(1);
    }

    /** 다른 조직·기획자의 업로드와 다른 검토 첨부 접근을 거절한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·검증 실패. Side Effects: 권한 있는 첫 첨부만 H2 저장.
     */
    @Test
    void attachmentAccessIsBoundToOrganizationAndReview() throws Exception {
        var other = new Actor("13", "attachment-other@example.test", "다른 담당", "ORG_MANAGER", new Organization("2", "D200", "다른조직"));
        assertThatThrownBy(() -> attachments.list(other, "100")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("REVIEW_ITEM_NOT_FOUND");
        mvc.perform(multipart("/api/v1/review-items/100/attachments").file(new MockMultipartFile("file", "근거.pdf", "application/pdf", PDF))
                .param("round", "1").param("expectedStateToken", token()).header("Authorization", PLANNER).header("Idempotency-Key", "planner-attachment"))
                .andExpect(status().isForbidden());
        String createdId = upload("attachment-valid", token(), "근거.pdf", "application/pdf", PDF, 201).path("attachment").path("id").asText();
        jdbc.sql("INSERT INTO review_item(id,submission_id,report_item_id,year,month,scenario,amount_present,current_amount,change_detected,gap_exceeded,source_fingerprint,context_fingerprint,current_round) VALUES (101,20,99,2031,2,'ACTUAL',TRUE,120,TRUE,TRUE,?,?,1)")
                .params("a".repeat(64), "b".repeat(64)).update();
        jdbc.sql("INSERT INTO review_explanation(review_item_id,round,verdict) VALUES (101,1,'PENDING')").update();
        mvc.perform(get("/api/v1/review-items/101/attachments/" + createdId + "/file").header("Authorization", MANAGER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/review-items/100/attachments/99999/file").header("Authorization", MANAGER)).andExpect(status().isNotFound());
        assertThatThrownBy(() -> attachments.file(other, "100", "1")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("REVIEW_ITEM_NOT_FOUND");
    }

    /** 파일 확장자 위장·경로 파일명·MIME 불일치·과대 파일·중복 part를 차단한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·검증 실패. Side Effects: 실패한 입력은 DB에 남기지 않는다.
     */
    @Test
    void invalidFilesAndExtraPartsAreRejectedWithoutPersistence() throws Exception {
        String token = token();
        upload("invalid-signature", token, "위장.pdf", "application/pdf", "not pdf".getBytes(StandardCharsets.UTF_8), 400);
        upload("invalid-path", token, "../근거.pdf", "application/pdf", PDF, 400);
        upload("invalid-mime", token, "근거.pdf", "text/html", PDF, 400);
        upload("invalid-extension", token, "근거.html", "application/pdf", PDF, 400);
        upload("invalid-empty", token, "근거.pdf", "application/pdf", new byte[0], 400);
        byte[] huge = new byte[16 * 1024 * 1024 + 1]; System.arraycopy(PDF, 0, huge, 0, PDF.length);
        upload("invalid-size", token, "근거.pdf", "application/pdf", huge, 400);
        mvc.perform(multipart("/api/v1/review-items/100/attachments")
                .file(new MockMultipartFile("file", "첫째.pdf", "application/pdf", PDF)).file(new MockMultipartFile("file", "둘째.pdf", "application/pdf", PDF))
                .param("round", "1").param("expectedStateToken", token).header("Authorization", MANAGER).header("Idempotency-Key", "extra-attachment"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM review_attachment").query(Integer.class).single()).isZero();
    }

    /** 수락·확정·반려·제외·지난 회차를 변경하지 못하는지 확인한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·검증 실패. Side Effects: 합성 상태만 바꾸며 첨부는 저장되지 않는다.
     */
    @Test
    void terminalAndExcludedRoundsStayImmutable() throws Exception {
        for (String status : List.of("APPROVED", "REJECTED")) {
            jdbc.sql("UPDATE submission SET status=? WHERE id=20").param(status).update();
            assertThat(upload("locked-" + status, token(), "근거.pdf", "application/pdf", PDF, 409).path("code").asText()).isEqualTo("EXPLANATION_LOCKED");
        }
        jdbc.sql("UPDATE submission SET status='PENDING' WHERE id=20").update();
        jdbc.sql("UPDATE review_explanation SET verdict='ACCEPTED' WHERE review_item_id=100").update();
        upload("locked-accepted", token(), "근거.pdf", "application/pdf", PDF, 409);
        jdbc.sql("UPDATE review_explanation SET verdict='PENDING' WHERE review_item_id=100").update();
        jdbc.sql("UPDATE review_item SET workflow_state='EXCLUDED' WHERE id=100").update();
        upload("locked-excluded", token(), "근거.pdf", "application/pdf", PDF, 409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM review_attachment").query(Integer.class).single()).isZero();
    }

    /** 새 보완 회차가 생겨도 이전 원문을 보존하고 지난 회차에 추가할 수 없는지 확인한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·검증 실패. Side Effects: H2 소명 회차와 첨부만 추가한다.
     */
    @Test
    void newRoundKeepsOldBytesAndRejectsOldRoundWrites() throws Exception {
        String fileId = upload("round-first-file", token(), "근거.pdf", "application/pdf", PDF, 201).path("attachment").path("id").asText();
        reviews.answer(manager, "100", "round-first-answer", new ReviewService.AnswerInput(token(), 1, "확인한 계약입니다."));
        var planner = new Actor("11", "attachment-planner@example.test", "기획", "PLANNER", null);
        reviews.decide(planner, "100", "round-reopen", new ReviewService.DecisionInput(token(), 1, "REQUEST_CHANGES", "세부 근거를 추가해 주세요."));
        assertThat(reviews.get(manager, "100").get("currentRound")).isEqualTo(2);
        JsonNode oldRound = upload("round-old-write", token(), "추가.pdf", "application/pdf", PDF, 409);
        assertThat(oldRound.path("code").asText()).isEqualTo("EXPLANATION_ROUND_CHANGED");
        mvc.perform(get("/api/v1/review-items/100/attachments/" + fileId + "/file").header("Authorization", PLANNER))
                .andExpect(status().isOk()).andExpect(content().bytes(PDF));
        mvc.perform(get("/api/v1/review-items/100/attachments").header("Authorization", PLANNER))
                .andExpect(jsonPath("$.items[0].round").value(1));
    }

    /** 이미지 서명과 필수 XLSX 항목이 있는 허용 파일의 원문을 보존한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·ZIP·검증 실패. Side Effects: 메모리 ZIP과 H2 첨부만 생성한다.
     */
    @Test
    void supportedImageAndWorkbookSignaturesAreAccepted() throws Exception {
        upload("image-png", token(), "확인.png", "image/png", new byte[]{(byte)137,80,78,71,13,10,26,10}, 201);
        upload("image-jpeg", token(), "확인.jpg", "image/jpeg", new byte[]{(byte)255,(byte)216,(byte)255,(byte)217}, 201);
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (String name : List.of("[Content_Types].xml", "xl/workbook.xml")) {
                zip.putNextEntry(new ZipEntry(name)); zip.write("<test/>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        upload("workbook-xlsx", token(), "확인.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray(), 201);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM review_attachment").query(Integer.class).single()).isEqualTo(3);
    }

    /** 현재 검토 토큰을 권한 있는 조직 담당자로 읽는다.
     * Args: 없음. Returns: 토큰 문자열. Raises: 조회 오류. Side Effects: H2 읽기.
     */
    private String token() { return reviews.get(manager, "100").get("stateToken").toString(); }

    /** 메모리 파일로 첨부 HTTP 명령을 실행하고 응답을 확인한다.
     * Args: key 멱등키, token 상태, filename 이름, type MIME, bytes 원문, expected HTTP상태.
     * Returns: JSON 응답. Raises: HTTP·JSON·검증 실패. Side Effects: H2 명령만 실행한다.
     */
    private JsonNode upload(String key, String token, String filename, String type, byte[] bytes, int expected) throws Exception {
        return mapper.readTree(mvc.perform(multipart("/api/v1/review-items/100/attachments")
                .file(new MockMultipartFile("file", filename, type, bytes)).param("round", "1").param("expectedStateToken", token)
                .header("Authorization", MANAGER).header("Idempotency-Key", key))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray());
    }
}
