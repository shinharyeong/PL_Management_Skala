package io.skala.backend.domain.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 경영기획팀 판단 흐름: 회신 이력(REQ-05) → 소명 재요청·회신·수락(GAP-11) → 승인과 master 발행(REQ-06),
 * 재제출 요청 → 반려 항목을 다시 소명하는 재제출(PROOF-01·08, HIST-01).
 */
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = "app.parser.sweeper.enabled=false")
class SubmissionDecisionIntegrationTest extends PostgresIntegrationTest {

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
    @Autowired
    javax.sql.DataSource dataSource;

    String orgCode;
    String orgName;
    long orgId;
    long managerId;
    long novRequest;
    int planYear;
    RequestPostProcessor manager;
    RequestPostProcessor planner;

    @BeforeEach
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase().replaceAll("[^A-Z0-9]", "0");
        orgCode = "Q" + suffix;
        orgName = "판단사업부" + suffix;
        orgId = insert("insert into organization(code, name) values (?, ?) returning id", orgCode, orgName);
        long plannerId = insert("insert into app_user(email, name, role) values (?, '기획', 'PLANNER') returning id",
                "p" + suffix + "@x.io");
        managerId = insert("insert into app_user(email, name, role, organization_id) values (?, '담당', 'ORG_MANAGER', ?) returning id",
                "o" + suffix + "@x.io", orgId);
        manager = as(new AuthenticatedUser(managerId, UserRole.ORG_MANAGER, orgId));
        planner = as(new AuthenticatedUser(plannerId, UserRole.PLANNER, null));
        // 다른 테스트의 요청·master와 섞이지 않도록 이 테스트만의 연도를 쓴다.
        planYear = 3000 + Math.floorMod(suffix.hashCode(), 5000);
        long plan = insert("insert into annual_plan(year, status) values (?, 'CONFIRMED') "
                + "on conflict (year) do update set year = excluded.year returning id", planYear);
        long octRequest = request(plan, 10, plannerId, suffix);
        novRequest = request(plan, 11, plannerId, suffix);
        long oct = draft(octRequest, "oct", List.of(
                line(10, SALES_KEY, "REVENUE", value(11, "ESTIMATE", "1100", "O10"))));
        jdbc.update("update parsing_job set status = 'SUCCEEDED' where submission_id = ?", oct);
        jdbc.update("update submission set status = 'APPROVED', seq = 1, approved_seq = 1 where id = ?", oct);
    }

    @Test
    void 소명을_재요청하고_회신을_수락한_뒤_승인하면_승인_버전과_master가_생긴다() throws Exception {
        long nov = submittedNovember();

        // REQ-05 회신 이력 카드
        mvc.perform(get("/api/v1/submissions").param("requestId", String.valueOf(novRequest))
                        .param("organizationId", String.valueOf(orgId)).with(planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].replyMessage").value("반영했습니다."));
        mvc.perform(get("/api/v1/submissions").with(planner)).andExpect(status().isBadRequest());

        // 수락하지 않은 소명이 있으면 승인 불가
        decide(nov, Map.of("decision", "APPROVE")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPLANATIONS_NOT_ACCEPTED"));

        // GAP-11: 메모 없는 재요청 422 → 재요청(2회차) → 조직 회신 → 수락
        Map<String, JsonNode> items = itemsByName(nov);
        String sales = items.get("제품매출").get("reviewItemId").asText();
        String rent = items.get("임차료").get("reviewItemId").asText();
        judge(sales, Map.of("verdict", "REQUEST_AGAIN")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOTE_REQUIRED"));
        judge(sales, Map.of("verdict", "REQUEST_AGAIN", "note", "근거 자료를 붙여 주세요.",
                "dueAt", "2099-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explanation.round").value(2))
                .andExpect(jsonPath("$.explanation.requestNote").value("근거 자료를 붙여 주세요."))
                .andExpect(jsonPath("$.tab").value("IN_PROGRESS"));
        judge(sales, Map.of("verdict", "ACCEPT")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANSWER_MISSING"));
        mvc.perform(put("/api/v1/review-items/{id}/answer", sales).with(manager).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("answer", "계약서를 첨부했습니다.",
                                "expectedStateToken", token(sales)))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                select count(*) from notification where type = 'REPLY' and submission_id = ?""", Integer.class, nov))
                .isPositive();
        judge(sales, Map.of("verdict", "ACCEPT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.explanation.verdict").value("ACCEPTED"))
                .andExpect(jsonPath("$.tab").value("ACCEPTED"));
        judge(rent, Map.of("verdict", "ACCEPT", "note", "확인")).andExpect(status().isOk());

        // REQ-06 승인 → 승인 버전 ver11.01, 대상 조직이 모두 승인돼 master 생성
        String beforeApproval = submissionToken(nov);
        decide(nov, Map.of("decision", "APPROVE"), beforeApproval).andExpect(status().isOk())
                .andExpect(jsonPath("$.submission.status").value("APPROVED"))
                .andExpect(jsonPath("$.submission.approvedLabel").value("ver11.01"))
                .andExpect(jsonPath("$.masterPublished.label").value("master_ver11.01"))
                .andExpect(jsonPath("$.masterPublished.month").value(11))
                .andExpect(jsonPath("$.masterPublished.state").value("CONFIRMED"));
        decide(nov, Map.of("decision", "APPROVE"), beforeApproval).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CHANGED"));
        // HIST-02 처리 이력: 제출 → 승인, 조직이 쓴 소명 2건
        mvc.perform(get("/api/v1/submissions/{id}", nov).with(manager))
                .andExpect(jsonPath("$.history[0].event").value("SUBMITTED"))
                .andExpect(jsonPath("$.history[1].event").value("APPROVED"))
                .andExpect(jsonPath("$.history[1].label").value("ver11.01"))
                .andExpect(jsonPath("$.explanations.length()").value(2))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        JsonNode approved = json(mvc.perform(get("/api/v1/submissions").param("displayStatus", "APPROVED")
                .param("year", String.valueOf(planYear)).with(manager)).andExpect(status().isOk()));
        assertThat(approved.findValuesAsText("displayStatus")).containsOnly("APPROVED");
        assertThat(approved.get("items").get(0).get("id").asText()).isEqualTo(String.valueOf(nov));
    }

    @Test
    void 재제출을_요청하면_반려_항목은_값이_같아도_다시_소명해야_한다() throws Exception {
        long nov = submittedNovember();
        String rent = itemsByName(nov).get("임차료").get("reviewItemId").asText();

        decide(nov, Map.of("decision", "REJECT", "comment", " ")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COMMENT_REQUIRED"));
        decide(nov, Map.of("decision", "REJECT", "comment", "임차료 근거가 부족합니다.",
                "rejectedReviewItemIds", List.of(Long.parseLong(rent))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submission.status").value("REJECTED"))
                .andExpect(jsonPath("$.submission.displayStatus").value("RESUBMIT_REQUESTED"))
                .andExpect(jsonPath("$.submission.reviewComment").value("임차료 근거가 부족합니다."))
                .andExpect(jsonPath("$.masterPublished").doesNotExist());
        assertThat(jdbc.queryForObject("select rejected from review_item where id = ?", Boolean.class,
                Long.parseLong(rent))).isTrue();
        // HIST-02 반려 항목: 임차료, 반려 사유, 마지막 소명
        mvc.perform(get("/api/v1/submissions/{id}", nov).with(manager))
                .andExpect(jsonPath("$.rejectedItems.length()").value(1))
                .andExpect(jsonPath("$.rejectedItems[0].reviewItemId").value(rent))
                .andExpect(jsonPath("$.rejectedItems[0].item").value("임차료 · 11월 추정"))
                .andExpect(jsonPath("$.rejectedItems[0].sourceCell").value("비용!O5"))
                .andExpect(jsonPath("$.rejectedItems[0].reason").value("임차료 근거가 부족합니다."))
                .andExpect(jsonPath("$.rejectedItems[0].previousExplanation.answer").value("변경 사유"));

        // 같은 값으로 재제출: 매출은 반려본과 같아 대상 아님, 임차료는 반려 항목이라 다시 소명
        long resubmission = draft(novRequest, "nov2", novemberLines());
        validate(resubmission).andExpect(jsonPath("$.baseline.kind").value("REJECTED"))
                .andExpect(jsonPath("$.progress.required").value(1));
        JsonNode rentAgain = itemsByName(resubmission).get("임차료");
        assertThat(rentAgain.get("answerState").asText()).isEqualTo("EMPTY");
        assertThat(rentAgain.get("previous").asText()).isEqualTo("50");
        mvc.perform(get("/api/v1/review-items/{id}", rentAgain.get("reviewItemId").asText()).with(manager))
                .andExpect(jsonPath("$.explanations[0].requestNote").value("임차료 근거가 부족합니다."));
        answer(rentAgain.get("reviewItemId").asText(), "임차 계약서 추가");
        submit(resubmission).andExpect(status().isOk()).andExpect(jsonPath("$.label")
                .value(planYear + "-11-02"));
        assertThat(jdbc.queryForObject("select previous_submission_id from submission where id = ?", Long.class,
                resubmission)).isEqualTo(nov);
        mvc.perform(get("/api/v1/submissions/{id}", resubmission).with(manager))
                .andExpect(jsonPath("$.history[*].event").value(org.hamcrest.Matchers.contains(
                        "SUBMITTED", "RESUBMIT_REQUESTED", "RESUBMITTED")));

        // unit: 원 단위 값은 그대로, 화면 단위 값이 옆에 붙는다. 잘못된 단위는 400
        mvc.perform(get("/api/v1/review-items").param("view", "ORG").param("submissionId", String.valueOf(resubmission))
                        .param("unit", "THOUSAND").with(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].months[0].items[0].current").value("50"))
                .andExpect(jsonPath("$.groups[0].months[0].items[0].displayCurrent").value("0"));
        mvc.perform(get("/api/v1/submissions/{id}/submit-preview", resubmission).param("unit", "WEIRD").with(manager))
                .andExpect(status().isBadRequest());

        // HIST-01: 반려본은 대체됨, 재제출본은 검토 대기
        JsonNode history = json(mvc.perform(get("/api/v1/submissions").param("year", String.valueOf(planYear))
                .param("type", "MONTHLY_ACTUAL").with(manager)).andExpect(status().isOk()));
        Map<String, String> states = new HashMap<>();
        history.get("items").forEach(i -> states.put(i.get("id").asText(), i.get("displayStatus").asText()));
        assertThat(states).containsEntry(String.valueOf(nov), "SUPERSEDED")
                .containsEntry(String.valueOf(resubmission), "PENDING");
    }

    @Test
    void 같은_제출본을_동시에_두_번_승인해도_한_번만_처리된다() throws Exception {
        long nov = submittedNovember();
        for (JsonNode item : itemsByName(nov).values()) {
            judge(item.get("reviewItemId").asText(), Map.of("verdict", "ACCEPT")).andExpect(status().isOk());
        }
        String token = submissionToken(nov);
        // 다른 연결에서 제출본 행을 잡아 두고 두 승인을 동시에 보낸 뒤 놓는다. 잠금 없이 상태를 읽는 구현이면
        // 둘 다 PENDING을 보고 성공(200, 200)하므로 이 테스트가 실패한다.
        List<Integer> statuses = new java.util.ArrayList<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try (java.sql.Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("select id from submission where id = ? for update")) {
                lock.setLong(1, nov);
                lock.executeQuery();
            }
            List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> decide(nov, Map.of("decision", "APPROVE"), token)
                        .andReturn().getResponse().getStatus()));
            }
            Thread.sleep(1500);
            holder.commit();
            for (java.util.concurrent.Future<Integer> result : results) {
                statuses.add(result.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdown();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("select approved_seq from submission where id = ?", Integer.class, nov))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from master_version where request_id = ?", Integer.class,
                novRequest)).isEqualTo(1);
    }

    /** 11월 초안 → 변경 확인 → 두 항목 소명 → 제출 */
    private long submittedNovember() throws Exception {
        long nov = draft(novRequest, "nov", novemberLines());
        validate(nov).andExpect(jsonPath("$.progress.required").value(2));
        itemsByName(nov).values().forEach(item -> {
            try {
                answer(item.get("reviewItemId").asText(), "변경 사유");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        submit(nov).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        return nov;
    }

    private List<Line> novemberLines() {
        return List.of(line(10, SALES_KEY, "REVENUE", value(11, "ESTIMATE", "1300", "O10")),
                line(5, RENT_KEY, "COST", value(11, "ESTIMATE", "50", "O5")));
    }

    private Map<String, JsonNode> itemsByName(long submissionId) throws Exception {
        JsonNode list = json(mvc.perform(get("/api/v1/review-items").param("view", "ORG")
                .param("submissionId", String.valueOf(submissionId)).with(manager)).andExpect(status().isOk()));
        Map<String, JsonNode> byName = new HashMap<>();
        for (JsonNode group : list.get("groups")) {
            for (JsonNode month : group.get("months")) {
                month.get("items").forEach(i -> byName.put(i.get("name").asText(), i));
            }
        }
        return byName;
    }

    private void answer(String reviewItemId, String text) throws Exception {
        mvc.perform(put("/api/v1/review-items/{id}/answer", reviewItemId).with(manager).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("answer", text,
                                "expectedStateToken", token(reviewItemId)))))
                .andExpect(status().isOk());
    }

    private String token(String reviewItemId) throws Exception {
        return json(mvc.perform(get("/api/v1/review-items/{id}", reviewItemId).with(planner))).get("stateToken").asText();
    }

    private ResultActions judge(String reviewItemId, Map<String, Object> body) throws Exception {
        Map<String, Object> content = new HashMap<>(body);
        content.put("expectedStateToken", token(reviewItemId));
        return mvc.perform(post("/api/v1/review-items/{id}/decisions", reviewItemId).with(planner)
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(content)));
    }

    private ResultActions decide(long submissionId, Map<String, Object> body) throws Exception {
        return decide(submissionId, body, submissionToken(submissionId));
    }

    private ResultActions decide(long submissionId, Map<String, Object> body, String token) throws Exception {
        Map<String, Object> content = new HashMap<>(body);
        content.put("expectedStateToken", token);
        return mvc.perform(post("/api/v1/submissions/{id}/decisions", submissionId).with(planner)
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(content)));
    }

    private ResultActions validate(long submissionId) throws Exception {
        return mvc.perform(post("/api/v1/submissions/{id}/validation", submissionId).with(manager)
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStateToken\":\"" + submissionToken(submissionId) + "\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions submit(long submissionId) throws Exception {
        return mvc.perform(post("/api/v1/submissions/{id}/submit", submissionId).with(manager)
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("expectedStateToken", submissionToken(submissionId),
                        "replyMessage", "반영했습니다."))));
    }

    private String submissionToken(long submissionId) throws Exception {
        return json(mvc.perform(get("/api/v1/submissions/{id}", submissionId).with(planner))).get("stateToken").asText();
    }

    private long draft(long requestId, String tag, List<Line> lines) {
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
        ParserSubmissionData data = new ParserSubmissionData("financial-submission/1.0", stored.getSha256(),
                new Owner(orgCode, orgName, "RESOLVED"),
                List.of(new ReportItem(SALES_KEY, "REVENUE", "제품매출", List.of("매출", "제품매출"), "RESOLVED"),
                        new ReportItem(RENT_KEY, "COST", "임차료", List.of("판관비", "임차료"), "RESOLVED")),
                lines, List.of());
        assertThat(parsingResultService.apply(job, 1, new ParserBridgeResponse("1.0", "test", "preview-records/1.0",
                "SUCCEEDED", null, List.of(), data)).orElseThrow().passed()).isTrue();
        return submissionId;
    }

    private Line line(int row, String reportKey, String domain, Value... values) {
        String sheet = "REVENUE".equals(domain) ? "매출" : "비용";
        return new Line("k" + row, domain, new Source(sheet, row), new Account("ACC" + row, "계정" + row),
                "D-" + row, null, reportKey, "RESOLVED", null, null, null, null, null, null, List.of(values));
    }

    private Value value(int month, String scenario, String amount, String cell) {
        return new Value(planYear, month, scenario, amount, "AVAILABLE", "PRESENT", cell, "rec:" + cell);
    }

    private long request(long plan, int month, long plannerId, String suffix) {
        long id = insert("""
                insert into submission_request(annual_plan_id, type, month, status, requested_by, requested_at, period_key)
                values (?, 'MONTHLY_ACTUAL', ?, 'OPEN', ?, now(), ?) returning id""",
                plan, month, plannerId, "D" + month + "-" + suffix);
        jdbc.update("insert into request_target(request_id, organization_id) values (?, ?)", id, orgId);
        return id;
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
