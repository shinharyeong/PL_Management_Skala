package io.skala.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.global.security.SessionTokenStore;
import io.skala.backend.support.PostgresIntegrationTest;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * RPT-12~14 소명 사유 유형 분류(FR-RPT-007).
 * <pre>
 * 10월 실적 vs 계획 Gap(임계값 넘음)
 *   A 판촉비   Gap 100  수락 "행사 비용이 다음 달로 이연"   → 규칙 후보 3(이연 효과)
 *   A 인건비   Gap  50  수락 "충원 반영"                    → 지난달(9월) 같은 조직·항목 분류 4(일시적 효과)
 *   B 제품매출 Gap -30  검토 중                             → 사유 확인 중(분류 불가)
 * </pre>
 */
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ReportExplanationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SessionTokenStore sessions;
    @Autowired
    ObjectMapper objectMapper;

    long orgA;
    long orgB;
    long plannerId;
    String planner;
    String manager;
    int year;
    long planId;
    long product;
    long promotion;
    long labor;
    long threshold;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        orgA = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '분류A') RETURNING id", Long.class, "CA" + s);
        orgB = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '분류B') RETURNING id", Long.class, "CB" + s);
        plannerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role) VALUES (?, '기획', 'PLANNER') RETURNING id",
                Long.class, "cp" + s + "@sk.com");
        long managerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role, organization_id) VALUES (?, '담당', 'ORG_MANAGER', ?) RETURNING id",
                Long.class, "cm" + s + "@sk.com", orgA);
        planner = sessions.issue(plannerId).token();
        manager = sessions.issue(managerId).token();
        do {
            year = ThreadLocalRandom.current().nextInt(2100, 9999);
        } while (jdbc.queryForObject("SELECT count(*) FROM annual_plan WHERE year = ?", Integer.class, year) > 0);
        planId = jdbc.queryForObject("INSERT INTO annual_plan(year, status) VALUES (?, 'CONFIRMED') RETURNING id", Long.class, year);
        long revenueRoot = item("매출", null, "REVENUE", false, 1);
        product = item("제품매출", revenueRoot, "REVENUE", true, 2);
        long costRoot = item("영업비용", null, "COST", false, 3);
        promotion = item("판촉비", costRoot, "COST", true, 4);
        labor = item("인건비", costRoot, "COST", true, 5);
        threshold = jdbc.queryForObject("""
                INSERT INTO gap_threshold(comparison, percent, amount, applied_by, applied_at)
                VALUES ('ACTUAL_VS_PLAN', 10, 10, ?, now()) RETURNING id""", Long.class, plannerId);
    }

    @Test
    void classifyExplanationsIntoCategoriesAndCheckGapTotal() throws Exception {
        // RPT-12: 기본 분류표. 2는 3+4의 상위 유형이라 직접 넣을 수 없고 5가 '기타'
        mvc.perform(auth(get("/api/v1/report/explanation-categories"), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableVersion").value(1))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[1].code").value(2))
                .andExpect(jsonPath("$.items[1].assignable").value(false))
                .andExpect(jsonPath("$.items[2].parentCode").value(2))
                .andExpect(jsonPath("$.items[4].catchAll").value(true));
        long etc = category(5);
        long deferral = category(3);
        long temporary = category(4);
        long parent = category(2);

        // 9월: A 인건비 소명을 4(일시적 효과)로 분류해 둠
        long september = request(9);
        long sepA = submission(september, orgA, "APPROVED");
        long sepLabor = gapItem(sepA, labor, 9, "40");
        long sepExplanation = explanation(sepLabor, "지난달 일시 충원", "ACCEPTED");
        jdbc.update("INSERT INTO explanation_classification(review_explanation_id, category_id, classified_by, classified_at) VALUES (?, ?, ?, now())",
                sepExplanation, temporary, plannerId);

        // 10월
        long october = request(10);
        long octA = submission(october, orgA, "APPROVED");
        long octB = submission(october, orgB, "APPROVED");
        long promoExplanation = explanation(gapItem(octA, promotion, 10, "100"), "행사 비용이 다음 달로 이연", "ACCEPTED");
        long laborExplanation = explanation(gapItem(octA, labor, 10, "50"), "충원 반영", "ACCEPTED");
        long pendingExplanation = explanation(gapItem(octB, product, 10, "-30"), "확인 중", "PENDING");

        // RPT-13: 수락된 카드 2장, 사유 확인 중 1건. 미분류는 '기타' 합계. 수락 전 소명 때문에 Gap 합계와 아직 다름
        JsonNode block = json(mvc.perform(auth(get("/api/v1/report/explanations").param("year", y()).param("month", "10")
                        .param("comparison", "ACTUAL_VS_PLAN"), planner))
                .andExpect(status().isOk()));
        assertThat(block.get("gapTotal").asText()).isEqualTo("120");
        assertThat(block.get("items")).hasSize(2);
        // 카드는 보고 항목 표시 순서(판촉비 → 인건비)
        assertThat(block.at("/items/0/explanationId").asText()).isEqualTo(String.valueOf(promoExplanation));
        assertThat(block.at("/items/0/gapAmount").asText()).isEqualTo("100");
        assertThat(block.at("/items/0/suggestedCategoryId").asText()).isEqualTo(String.valueOf(deferral));
        assertThat(block.at("/items/0/suggestionSource").asText()).isEqualTo("RULE");
        assertThat(block.at("/items/0/categoryId").isNull()).isTrue();
        assertThat(block.at("/items/1/explanationId").asText()).isEqualTo(String.valueOf(laborExplanation));
        assertThat(block.at("/items/1/suggestedCategoryId").asText()).isEqualTo(String.valueOf(temporary));
        assertThat(block.at("/items/1/suggestionSource").asText()).isEqualTo("LAST_MONTH");
        assertThat(block.at("/reviewing/0/item").asText()).isEqualTo("제품매출");
        assertThat(block.at("/reviewing/0/gapAmount").asText()).isEqualTo("-30");
        assertThat(block.at("/categoryTotals/" + etc).asText()).isEqualTo("150");
        assertThat(block.get("matchesGapTotal").asBoolean()).isFalse();

        // RPT-14: 판촉비를 3(이연)에. 상위 유형 2는 하위 합으로 계산된다. 같은 키 재전송은 첫 응답
        String key = "it-" + UUID.randomUUID();
        classify(promoExplanation, String.valueOf(deferral), key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(String.valueOf(deferral)))
                .andExpect(jsonPath("$.categoryTotals." + deferral).value("100"))
                .andExpect(jsonPath("$.categoryTotals." + parent).value("100"))
                .andExpect(jsonPath("$.categoryTotals." + etc).value("50"))
                .andExpect(jsonPath("$.classifiedTotal").value("150"))
                .andExpect(jsonPath("$.gapTotal").value("120"))
                .andExpect(jsonPath("$.matchesGapTotal").value(false));
        classify(promoExplanation, String.valueOf(deferral), key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));

        // 상위 유형·수락 전 소명·조직 담당자는 거부
        classify(laborExplanation, String.valueOf(parent), "it-" + UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_ASSIGNABLE"));
        classify(pendingExplanation, String.valueOf(deferral), "it-" + UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPLANATION_NOT_ACCEPTED"));
        mvc.perform(auth(put("/api/v1/report/explanations/{id}/classification", laborExplanation), manager)
                        .header("Idempotency-Key", "it-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":\"" + temporary + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/report/explanations").param("year", y()).param("month", "10")
                        .param("comparison", "ACTUAL_VS_PLAN"), manager))
                .andExpect(status().isForbidden());

        // B 소명이 수락되면 분류 합계 = Gap 합계(미분류 B는 '기타')
        jdbc.update("UPDATE review_explanation SET verdict = 'ACCEPTED' WHERE id = ?", pendingExplanation);
        JsonNode after = json(mvc.perform(auth(get("/api/v1/report/explanations").param("year", y()).param("month", "10")
                        .param("comparison", "ACTUAL_VS_PLAN"), planner))
                .andExpect(status().isOk()));
        assertThat(after.get("reviewing")).isEmpty();
        assertThat(after.at("/categoryTotals/" + etc).asText()).isEqualTo("20");
        assertThat(after.get("classifiedTotal").asText()).isEqualTo("120");
        assertThat(after.get("matchesGapTotal").asBoolean()).isTrue();

        // categoryId null: 분류에서 빼면 '기타'로 돌아감
        classify(promoExplanation, null, "it-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").isEmpty())
                .andExpect(jsonPath("$.categoryTotals." + deferral).value("0"))
                .andExpect(jsonPath("$.categoryTotals." + etc).value("120"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM explanation_classification WHERE review_explanation_id = ?",
                Integer.class, promoExplanation)).isZero();
    }

    private ResultActions classify(long explanationId, String categoryId, String key) throws Exception {
        String body = categoryId == null ? "{\"categoryId\":null}" : "{\"categoryId\":\"" + categoryId + "\"}";
        return mvc.perform(auth(put("/api/v1/report/explanations/{id}/classification", explanationId), planner)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long category(int code) {
        return jdbc.queryForObject("SELECT id FROM explanation_category WHERE table_version = 1 AND code = ?", Long.class, code);
    }

    /** 임계값을 넘은 실적 vs 계획 Gap 항목 */
    private long gapItem(long submissionId, long reportItem, int month, String gap) {
        long id = jdbc.queryForObject("""
                INSERT INTO review_item(submission_id, report_item_id, year, month, scenario, amount_present, current_amount,
                    change_detected, gap_exceeded, source_fingerprint, context_fingerprint, current_round)
                VALUES (?, ?, ?, ?, 'ACTUAL', true, 100, true, true, ?, ?, 1) RETURNING id""", Long.class,
                submissionId, reportItem, year, month, "a".repeat(64), "b".repeat(64));
        jdbc.update("""
                INSERT INTO gap_comparison(review_item_id, basis, baseline_submission_id, baseline_scenario, baseline_amount,
                    current_amount, gap_amount, gap_rate, threshold_id, exceeded)
                VALUES (?, 'PLAN', ?, 'PLAN', 100, 100 + ?::numeric, ?::numeric, 10, ?, true)""",
                id, submissionId, gap, gap, threshold);
        return id;
    }

    private long explanation(long reviewItem, String answer, String verdict) {
        return jdbc.queryForObject("INSERT INTO review_explanation(review_item_id, round, answer, verdict) VALUES (?, 1, ?, ?) RETURNING id",
                Long.class, reviewItem, answer, verdict);
    }

    private long item(String name, Long parent, String kind, boolean leaf, int order) {
        return jdbc.queryForObject("""
                INSERT INTO report_item(code, name, parent_id, kind, is_leaf, display_order)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                "CL" + UUID.randomUUID(), name, parent, kind, leaf, 3_000_000 + order);
    }

    private long request(int month) {
        long id = jdbc.queryForObject("""
                INSERT INTO submission_request(annual_plan_id, type, month, status, requested_by, requested_at, period_key)
                VALUES (?, 'MONTHLY_ACTUAL', ?, 'OPEN', ?, now(), ?) RETURNING id""", Long.class, planId, month, plannerId,
                year + "-M-" + month);
        jdbc.update("INSERT INTO request_target(request_id, organization_id, selected) VALUES (?, ?, true), (?, ?, true)",
                id, orgA, id, orgB);
        return id;
    }

    private long submission(long requestId, long orgId, String status) {
        String key = "uploads/test/" + UUID.randomUUID() + ".xlsx";
        jdbc.update("INSERT INTO stored_object(object_key, content_type, size_bytes, sha256) VALUES (?, 'application/octet-stream', 1, ?)",
                key, "0".repeat(64));
        return jdbc.queryForObject("""
                INSERT INTO submission(request_id, organization_id, seq, status, original_filename, source_object_key,
                    source_sha256, uploaded_by, uploaded_at, submitted_by, submitted_at, reviewed_by, reviewed_at, approved_seq)
                VALUES (?, ?, 1, ?, 'f.xlsx', ?, ?, ?, now(), ?, now(), ?, now(), 1) RETURNING id""", Long.class,
                requestId, orgId, status, key, "a".repeat(64), plannerId, plannerId, plannerId);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder auth(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private String y() {
        return String.valueOf(year);
    }
}
