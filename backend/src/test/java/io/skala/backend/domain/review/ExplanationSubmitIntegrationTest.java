package io.skala.backend.domain.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.file.service.FileStorage;
import io.skala.backend.domain.file.value.FilePurpose;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Account;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Line;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Owner;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.ReportItem;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Source;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Value;
import io.skala.backend.domain.submission.parser.ParserBridgeResponse;
import io.skala.backend.domain.submission.service.ParsingResultService;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.support.PostgresIntegrationTest;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 조직 담당자 소명 흐름: 변경 확인(PROOF-01) → 대상 목록(02) → 상세(03) → 작성(04) → 첨부(05·06)
 * → 제출 확인(07) → 제출(08). 10월 승인본 대비 11월 초안을 비교한다.
 */
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = "app.parser.sweeper.enabled=false")
class ExplanationSubmitIntegrationTest extends PostgresIntegrationTest {

    private static final String SALES_KEY = "[\"REVENUE\",[\"매출\",\"제품매출\"]]";
    private static final String RENT_KEY = "[\"COST\",[\"판관비\",\"임차료\"]]";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    FileStorage fileStorage;
    @Autowired
    ParsingResultService parsingResultService;

    String orgCode;
    String orgName;
    long orgId;
    long managerId;
    RequestPostProcessor manager;
    RequestPostProcessor planner;

    @Test
    void 바뀐_항목을_모두_소명하고_첨부해_제출하면_검토_대기가_되고_원본이_보존된다() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase().replaceAll("[^A-Z0-9]", "0");
        orgCode = "P" + suffix;
        orgName = "소명사업부" + suffix;
        orgId = insert("insert into organization(code, name) values (?, ?) returning id", orgCode, orgName);
        long plannerId = insert("insert into app_user(email, name, role) values (?, '기획', 'PLANNER') returning id",
                "p" + suffix + "@x.io");
        managerId = insert("insert into app_user(email, name, role, organization_id) values (?, '담당', 'ORG_MANAGER', ?) returning id",
                "o" + suffix + "@x.io", orgId);
        manager = as(new AuthenticatedUser(managerId, UserRole.ORG_MANAGER, orgId));
        planner = as(new AuthenticatedUser(plannerId, UserRole.PLANNER, null));
        long plan = insert("insert into annual_plan(year, status) values (2026, 'CONFIRMED') "
                + "on conflict (year) do update set year = excluded.year returning id");
        long oct = request(plan, 10, plannerId, suffix);
        long nov = request(plan, 11, plannerId, suffix);

        // 10월: 적재 → 변경 확인(기준 없음) → 승인, 실적 항목에 승인 소명
        long octSubmission = draft(oct, "oct", data(List.of(
                line(10, SALES_KEY, "REVENUE", value(10, "ACTUAL", "1000", "N10"), value(11, "ESTIMATE", "1100", "O10"), value(12, "ESTIMATE", "100", "P10")))));
        validate(octSubmission).andExpect(jsonPath("$.baseline.kind").value("NONE"));
        jdbc.update("update submission set status = 'APPROVED', seq = 1, approved_seq = 1 where id = ?", octSubmission);
        long octActual = jdbc.queryForObject("select id from review_item where submission_id = ? and month = 12",
                Long.class, octSubmission);
        jdbc.update("""
                insert into review_explanation(review_item_id, round, answer, answered_by, answered_at, verdict)
                values (?, 1, '대형 수주 반영', ?, now(), 'ACCEPTED')""", octActual, managerId);

        // 11월: 10월 실적 그대로(이어받음), 11월 추정 변경, 임차료 신규
        long novSubmission = draft(nov, "nov", data(List.of(
                line(10, SALES_KEY, "REVENUE", value(10, "ACTUAL", "1000", "N10"), value(11, "ACTUAL", "1300", "O10"), value(12, "ESTIMATE", "100", "P10")),
                line(5, RENT_KEY, "COST", value(11, "ACTUAL", "50", "O5")))));
        validate(novSubmission)
                .andExpect(jsonPath("$.baseline.kind").value("PREV_MONTH_APPROVED"))
                .andExpect(jsonPath("$.progress.required").value(2));

        // PROOF-02 목록: 매출(추정 변경 + 실적 이어받음), 비용(신규)
        JsonNode list = json(mvc.perform(get("/api/v1/review-items").param("view", "ORG")
                .param("submissionId", String.valueOf(novSubmission)).with(manager)).andExpect(status().isOk()));
        assertThat(list.at("/progress/required").asInt()).isEqualTo(2);
        assertThat(list.at("/baseline/label").asText()).isEqualTo("2026-10-01");
        assertThat(list.findValuesAsText("group")).containsExactly("REVENUE", "COST");
        JsonNode salesEstimate = find(list, "11", "ACTUAL", "제품매출");
        assertThat(salesEstimate.get("baseline").asText()).isEqualTo("1100");
        assertThat(salesEstimate.get("current").asText()).isEqualTo("1300");
        assertThat(salesEstimate.get("rate").asText()).isEqualTo("18.2");
        assertThat(salesEstimate.get("answerState").asText()).isEqualTo("EMPTY");
        assertThat(salesEstimate.get("sourceCell").asText()).isEqualTo("매출!O10");
        JsonNode inherited = find(list, "12", "ESTIMATE", "제품매출");
        assertThat(inherited.get("answerState").asText()).isEqualTo("INHERITED");
        JsonNode rent = find(list, "11", "ACTUAL", "임차료");
        assertThat(rent.get("baseline").isNull()).isTrue();
        assertThat(rent.get("rate").isNull()).isTrue();

        // PROOF-03 상세
        mvc.perform(get("/api/v1/review-items/{id}", salesEstimate.get("reviewItemId").asText()).with(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("매출 › 제품매출 › 11월 실적"))
                .andExpect(jsonPath("$.rows[0].sourceCell").value("매출!O10"))
                .andExpect(jsonPath("$.rows[0].diff").value("200"))
                .andExpect(jsonPath("$.summary").doesNotExist());
        mvc.perform(get("/api/v1/review-items/{id}", inherited.get("reviewItemId").asText()).with(manager))
                .andExpect(jsonPath("$.previousExplanation.answer").value("대형 수주 반영"))
                .andExpect(jsonPath("$.explanations[0].inheritedFrom.label").value("ver10.01"));

        // 미작성 상태로 제출 → 422 ANSWER_REQUIRED(미작성 항목 2개)
        String submissionToken = submissionToken(novSubmission);
        submit(novSubmission, submissionToken).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANSWER_REQUIRED"))
                .andExpect(jsonPath("$.errors.length()").value(2));

        // PROOF-04 작성: 낡은 토큰 409, 이어받은 항목 409, 정상 작성
        answer(salesEstimate.get("reviewItemId").asText(), "st_old", "x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CHANGED"));
        answer(inherited.get("reviewItemId").asText(), inherited.get("stateToken").asText(), "x")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_CONTEXT_CHANGED"));
        answer(salesEstimate.get("reviewItemId").asText(), salesEstimate.get("stateToken").asText(), "   ")
                .andExpect(status().isBadRequest());
        JsonNode written = json(answer(salesEstimate.get("reviewItemId").asText(),
                salesEstimate.get("stateToken").asText(), "  단가 인상 반영  ").andExpect(status().isOk()));
        assertThat(written.at("/progress/answered").asInt()).isEqualTo(1);
        answer(rent.get("reviewItemId").asText(), rent.get("stateToken").asText(), "신규 사무실 임차")
                .andExpect(status().isOk());

        // PROOF-05·06 첨부: 허용하지 않는 형식 415, 정상 첨부 201 → 목록·다운로드
        String rentToken = json(mvc.perform(get("/api/v1/review-items/{id}", rent.get("reviewItemId").asText())
                .with(manager))).get("stateToken").asText();
        attach(rent.get("reviewItemId").asText(), "evil.exe", rentToken).andExpect(status().isUnsupportedMediaType());
        byte[] pdf = "%PDF-1.4 근거".getBytes();
        JsonNode attachment = json(mvc.perform(multipart("/api/v1/review-items/{id}/attachments", rent.get("reviewItemId").asText())
                        .file(new MockMultipartFile("file", "임차계약서.pdf", "application/pdf", pdf))
                        .param("expectedStateToken", rentToken)
                        .header("Idempotency-Key", key()).with(manager))
                .andExpect(status().isCreated()));
        mvc.perform(get("/api/v1/review-items/{id}/attachments", rent.get("reviewItemId").asText()).with(planner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").value("임차계약서.pdf"));
        byte[] downloaded = mvc.perform(get(attachment.get("url").asText()).with(manager))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(pdf);

        // PROOF-07 제출 확인
        mvc.perform(get("/api/v1/submissions/{id}/submit-preview", novSubmission).with(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedLabel").value("2026-11-01"))
                .andExpect(jsonPath("$.file.savedAs").value(orgCode + "_2026-11-01.xlsx"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.canSubmit").value(true));

        // PROOF-08 제출
        submit(novSubmission, submissionToken(novSubmission)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.label").value("2026-11-01"))
                .andExpect(jsonPath("$.replyMessage").value("11월 반영분입니다."));
        assertThat(jdbc.queryForObject("select source_object_key from submission where id = ?", String.class,
                novSubmission)).startsWith("uploads/");
        assertThat(jdbc.queryForObject("""
                select a.object_key from review_attachment a where a.id = ?""", String.class,
                Long.parseLong(attachment.get("id").asText()))).startsWith("uploads/");
        assertThat(jdbc.queryForObject("""
                select count(*) from notification where type = 'SUBMISSION' and submission_id = ?""",
                Integer.class, novSubmission)).isPositive();

        // 제출 뒤에는 소명을 고칠 수 없다
        String lockedToken = json(mvc.perform(get("/api/v1/review-items/{id}", rent.get("reviewItemId").asText())
                .with(manager))).get("stateToken").asText();
        answer(rent.get("reviewItemId").asText(), lockedToken, "수정").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPLANATION_LOCKED"));
    }

    private ResultActions validate(long submissionId) throws Exception {
        return mvc.perform(post("/api/v1/submissions/{id}/validation", submissionId).with(manager)
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStateToken\":\"" + submissionToken(submissionId) + "\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions submit(long submissionId, String token) throws Exception {
        return mvc.perform(post("/api/v1/submissions/{id}/submit", submissionId).with(manager)
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("expectedStateToken", token,
                        "replyMessage", "11월 반영분입니다."))));
    }

    private ResultActions answer(String reviewItemId, String token, String text) throws Exception {
        return mvc.perform(put("/api/v1/review-items/{id}/answer", reviewItemId).with(manager)
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("answer", text, "expectedStateToken", token))));
    }

    private ResultActions attach(String reviewItemId, String name, String token) throws Exception {
        return mvc.perform(multipart("/api/v1/review-items/{id}/attachments", reviewItemId)
                .file(new MockMultipartFile("file", name, "application/octet-stream", new byte[] {1, 2, 3}))
                .param("expectedStateToken", token).header("Idempotency-Key", key()).with(manager));
    }

    private String submissionToken(long submissionId) throws Exception {
        return json(mvc.perform(get("/api/v1/submissions/{id}", submissionId).with(manager))).get("stateToken").asText();
    }

    /** 실제 파일을 drafts/에 저장하고 초안·실행 중 파싱 작업을 만든 뒤 파서 결과를 반영한다. */
    private long draft(long requestId, String tag, java.util.function.Function<String, ParserSubmissionData> data) {
        StoredObject stored = fileStorage.save(FilePurpose.SUBMISSION_EXCEL, orgCode + "_" + tag + ".xlsx",
                "application/octet-stream", new ByteArrayInputStream(("PK\u0003\u0004" + tag + UUID.randomUUID()).getBytes()));
        long submissionId = insert("""
                insert into submission(request_id, organization_id, status, original_filename, source_object_key,
                  source_sha256, uploaded_by, uploaded_at)
                values (?, ?, 'DRAFT', ?, ?, ?, ?, now()) returning id""",
                requestId, orgId, orgCode + "_" + tag + ".xlsx", stored.getObjectKey(), stored.getSha256(), managerId);
        long job = insert("""
                insert into parsing_job(submission_id, status, engine, attempt_no) values (?, 'RUNNING', 'test', 1)
                returning id""", submissionId);
        assertThat(parsingResultService.apply(job, 1, new ParserBridgeResponse("1.0", "test", "preview-records/1.0",
                "SUCCEEDED", null, List.of(), data.apply(stored.getSha256()))).orElseThrow().passed()).isTrue();
        return submissionId;
    }

    private java.util.function.Function<String, ParserSubmissionData> data(List<Line> lines) {
        return sha -> new ParserSubmissionData("financial-submission/1.0", sha, new Owner(orgCode, orgName, "RESOLVED"),
                new ArrayList<>(List.of(
                        new ReportItem(SALES_KEY, "REVENUE", "제품매출", List.of("매출", "제품매출"), "RESOLVED"),
                        new ReportItem(RENT_KEY, "COST", "임차료", List.of("판관비", "임차료"), "RESOLVED"))),
                lines, List.of());
    }

    private static Line line(int row, String reportKey, String domain, Value... values) {
        String sheet = "REVENUE".equals(domain) ? "매출" : "비용";
        return new Line("k" + row, domain, new Source(sheet, row), new Account("ACC" + row, "계정" + row),
                "D-" + row, null, reportKey, "RESOLVED", null, null, null, null, null, null,
                null, null, null, null, List.of(values));
    }

    private static Value value(int month, String scenario, String amount, String cell) {
        return new Value(2026, month, scenario, amount, "AVAILABLE", "PRESENT", cell, "rec:" + cell);
    }

    private long request(long plan, int month, long plannerId, String suffix) {
        long id = insert("""
                insert into submission_request(annual_plan_id, type, month, status, requested_by, requested_at, period_key)
                values (?, 'MONTHLY_ACTUAL', ?, 'OPEN', ?, now(), ?) returning id""",
                plan, month, plannerId, "E" + month + "-" + suffix);
        jdbc.update("insert into request_target(request_id, organization_id) values (?, ?)", id, orgId);
        return id;
    }

    private static JsonNode find(JsonNode list, String month, String scenario, String name) {
        for (JsonNode group : list.get("groups")) {
            for (JsonNode m : group.get("months")) {
                if (m.get("month").asText().equals(month) && m.get("scenario").asText().equals(scenario)) {
                    for (JsonNode item : m.get("items")) {
                        if (item.get("name").asText().equals(name)) {
                            return item;
                        }
                    }
                }
            }
        }
        throw new AssertionError("항목 없음: " + month + " " + scenario + " " + name);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private long insert(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static String key() {
        return "it-" + UUID.randomUUID();
    }

    private static RequestPostProcessor as(AuthenticatedUser user) {
        return authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
