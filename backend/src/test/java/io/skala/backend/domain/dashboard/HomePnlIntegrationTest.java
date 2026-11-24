package io.skala.backend.domain.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.skala.backend.global.security.SessionTokenStore;
import io.skala.backend.support.PostgresIntegrationTest;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 조직 A·B, 10월 월간 요청.
 * <pre>
 *            실적(10월)        계획(확정 연간 계획 10월)   9월 시트의 10월 추정
 * 제품매출   A 1000 · B 500    A 800 · B 600              A 950
 * 판촉비     A 200             A 100
 * 인건비     A 300 · B 100     A 300 · B 100
 * </pre>
 * 연간 탭 확인용으로 A의 9월 실적 제품매출 900, 9월 계획 900.
 */
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class HomePnlIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SessionTokenStore sessions;

    long orgA;
    long orgB;
    long plannerId;
    String planner;
    String manager;
    int year;
    long planId;
    long revenueRoot;
    long product;
    long costRoot;
    long marketing;
    long promotion;
    long labor;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        orgA = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '손익A') RETURNING id", Long.class, "PA" + s);
        orgB = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '손익B') RETURNING id", Long.class, "PB" + s);
        plannerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role) VALUES (?, '기획', 'PLANNER') RETURNING id",
                Long.class, "hp" + s + "@sk.com");
        long managerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role, organization_id) VALUES (?, '담당', 'ORG_MANAGER', ?) RETURNING id",
                Long.class, "hm" + s + "@sk.com", orgA);
        planner = sessions.issue(plannerId).token();
        manager = sessions.issue(managerId).token();
        do {
            year = ThreadLocalRandom.current().nextInt(2100, 9999);
        } while (jdbc.queryForObject("SELECT count(*) FROM annual_plan WHERE year = ?", Integer.class, year) > 0);
        planId = jdbc.queryForObject("INSERT INTO annual_plan(year, status) VALUES (?, 'CONFIRMED') RETURNING id", Long.class, year);

        revenueRoot = item("매출", null, "REVENUE", false, 1);
        product = item("제품매출", revenueRoot, "REVENUE", true, 2);
        costRoot = item("영업비용", null, "COST", false, 3);
        marketing = item("마케팅비용", costRoot, "COST", false, 4);
        promotion = item("판촉비", marketing, "COST", true, 5);
        labor = item("인건비", costRoot, "COST", true, 6);
    }

    @Test
    void homePnlUsesMasterConfirmedPlanAndPreviousEstimate() throws Exception {
        long annual = request("ANNUAL_PLAN", null);
        long planA = submission(annual, orgA, "APPROVED", 1);
        values(planA, "PLAN", 10, product, 800, promotion, 100, labor, 300);
        values(planA, "PLAN", 9, product, 900);
        long planB = submission(annual, orgB, "APPROVED", 1);
        values(planB, "PLAN", 10, product, 600, labor, 100);

        long september = request("MONTHLY_ACTUAL", 9);
        long sepA = submission(september, orgA, "APPROVED", 1);
        values(sepA, "ESTIMATE", 10, product, 950);

        long october = request("MONTHLY_ACTUAL", 10);
        jdbc.update("UPDATE request_target SET confirmed_plan_submission_id = ? WHERE request_id = ? AND organization_id = ?", planA, october, orgA);
        jdbc.update("UPDATE request_target SET confirmed_plan_submission_id = ? WHERE request_id = ? AND organization_id = ?", planB, october, orgB);
        long octA = submission(october, orgA, "APPROVED", 1);
        values(octA, "ACTUAL", 10, product, 1000, promotion, 200, labor, 300);
        values(octA, "ACTUAL", 9, product, 900);
        long octB = submission(october, orgB, "PENDING", null);
        values(octB, "ACTUAL", 10, product, 500, labor, 100);

        // 판촉비가 Gap 기준을 넘었고 소명이 수락됨
        long reviewItem = jdbc.queryForObject("""
                INSERT INTO review_item(submission_id, report_item_id, year, month, scenario, amount_present, current_amount,
                    change_detected, gap_exceeded, source_fingerprint, context_fingerprint, current_round)
                VALUES (?, ?, ?, 10, 'ACTUAL', true, 200, true, true, ?, ?, 1) RETURNING id""", Long.class,
                octA, promotion, year, "a".repeat(64), "b".repeat(64));
        jdbc.update("INSERT INTO review_explanation(review_item_id, round, answer, verdict) VALUES (?, 1, '판촉 행사', 'ACCEPTED')", reviewItem);

        // B가 아직 승인 전이면 A 승인본만으로 잠정 계산
        mvc.perform(auth(get("/api/v1/home/pnl").param("year", y()).param("month", "10"), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("PROVISIONAL"))
                .andExpect(jsonPath("$.kpis[0].actual").value("1000"))
                .andExpect(jsonPath("$.kpis[0].vsPlanAmount").value("200"));

        jdbc.update("UPDATE submission SET status = 'APPROVED', approved_seq = 1, reviewed_by = ?, reviewed_at = now() WHERE id = ?", plannerId, octB);
        long master = jdbc.queryForObject("""
                INSERT INTO master_version(request_id, seq, trigger_submission_id, content_sha256, calculation_version,
                    created_by, created_at)
                VALUES (?, 1, ?, ?, 'test', ?, now()) RETURNING id""", Long.class, october, octB, "c".repeat(64), plannerId);
        jdbc.update("INSERT INTO master_source(master_id, organization_id, submission_id) VALUES (?, ?, ?), (?, ?, ?)",
                master, orgA, octA, master, orgB, octB);

        mvc.perform(auth(get("/api/v1/home/pnl").param("year", y()).param("month", "10")
                        .param("masterId", String.valueOf(master)), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.kpis[0].key").value("REVENUE"))
                .andExpect(jsonPath("$.kpis[0].actual").value("1500"))
                .andExpect(jsonPath("$.kpis[0].vsPlanAmount").value("100"))
                .andExpect(jsonPath("$.kpis[0].vsPlanRate").value("7.1"))
                .andExpect(jsonPath("$.kpis[1].actual").value("600"))
                .andExpect(jsonPath("$.kpis[1].vsPlanRate").value("20.0"))
                .andExpect(jsonPath("$.kpis[2].key").value("OP"))
                .andExpect(jsonPath("$.kpis[2].actual").value("900"))
                .andExpect(jsonPath("$.kpis[2].vsPlanAmount").value("0"))
                .andExpect(jsonPath("$.rows.length()").value(7))
                .andExpect(jsonPath("$.rows[0].name").value("매출"))
                .andExpect(jsonPath("$.rows[0].level").value(1))
                .andExpect(jsonPath("$.rows[1].name").value("제품매출"))
                .andExpect(jsonPath("$.rows[1].level").value(2))
                .andExpect(jsonPath("$.rows[1].vsPrevEstimateAmount").value("550"))
                .andExpect(jsonPath("$.rows[2].name").value("영업비용"))
                .andExpect(jsonPath("$.rows[2].actual").value("600"))
                .andExpect(jsonPath("$.rows[3].name").value("마케팅비용"))
                .andExpect(jsonPath("$.rows[4].name").value("판촉비"))
                .andExpect(jsonPath("$.rows[4].level").value(3))
                .andExpect(jsonPath("$.rows[4].gapExceeded").value(true))
                .andExpect(jsonPath("$.rows[4].explanation").value("손익A: 판촉 행사"))
                .andExpect(jsonPath("$.rows[5].name").value("인건비"))
                .andExpect(jsonPath("$.rows[5].gapExceeded").value(false))
                .andExpect(jsonPath("$.rows[6].kind").value("OP"))
                .andExpect(jsonPath("$.rows[6].reportItemId").isEmpty())
                .andExpect(jsonPath("$.rows[6].actual").value("900"))
                .andExpect(jsonPath("$.opMargin.actualRate").value("60.0"))
                .andExpect(jsonPath("$.opMargin.planRate").value("64.3"))
                .andExpect(jsonPath("$.opMargin.vsPlanPoint").value("-4.3"));

        // 연간 탭: 1~10월 누적, 전월 추정 비교 없음
        mvc.perform(auth(get("/api/v1/home/pnl").param("period", "YEAR").param("year", y()).param("month", "10"), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis[0].actual").value("2400"))
                .andExpect(jsonPath("$.kpis[0].vsPlanAmount").value("100"))
                .andExpect(jsonPath("$.rows[1].vsPrevEstimateAmount").isEmpty());

        mvc.perform(auth(get("/api/v1/home/op-drivers").param("year", y()).param("month", "10"), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("0"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].organization.name").value("손익A"))
                .andExpect(jsonPath("$.items[0].opDiff").value("100"))
                .andExpect(jsonPath("$.items[0].opDiffRate").value("25.0"))
                .andExpect(jsonPath("$.items[1].opDiff").value("-100"))
                .andExpect(jsonPath("$.items[1].opDiffRate").value("-20.0"));

        mvc.perform(auth(get("/api/v1/home/divisions").param("year", y()).param("month", "10").param("unit", "THOUSAND"), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].revenue").value("1000"))
                .andExpect(jsonPath("$.columns[0].displayRevenue").value("1"))
                .andExpect(jsonPath("$.columns[0].op").value("500"))
                .andExpect(jsonPath("$.columns[0].opContributionRate").value("55.6"))
                .andExpect(jsonPath("$.columns[0].opMarginRate").value("50.0"))
                .andExpect(jsonPath("$.columns[1].vsPlanAmount").value("-100"))
                .andExpect(jsonPath("$.total.revenue").value("1500"))
                .andExpect(jsonPath("$.total.displayOp").value("1"))
                .andExpect(jsonPath("$.total.opMarginRate").value("60.0"));

        mvc.perform(auth(get("/api/v1/home/waterfall").param("year", y()).param("month", "10"), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(4))
                .andExpect(jsonPath("$.steps[0].key").value("REVENUE"))
                .andExpect(jsonPath("$.steps[0].amount").value("1500"))
                .andExpect(jsonPath("$.steps[0].breakdown[0].name").value("제품매출"))
                .andExpect(jsonPath("$.steps[1].name").value("마케팅비용"))
                .andExpect(jsonPath("$.steps[1].kind").value("DEDUCTION"))
                .andExpect(jsonPath("$.steps[1].breakdown[0].amount").value("200"))
                .andExpect(jsonPath("$.steps[2].name").value("인건비"))
                .andExpect(jsonPath("$.steps[2].amount").value("400"))
                .andExpect(jsonPath("$.steps[3].key").value("OP"))
                .andExpect(jsonPath("$.steps[3].amount").value("900"));
    }

    @Test
    void emptyMonthInvalidInputAndPermission() throws Exception {
        request("MONTHLY_ACTUAL", 10);
        long otherMonth = request("MONTHLY_ACTUAL", 11);
        long trigger = submission(otherMonth, orgA, "APPROVED", 1);
        long master = jdbc.queryForObject("""
                INSERT INTO master_version(request_id, seq, trigger_submission_id, content_sha256, calculation_version,
                    created_by, created_at)
                VALUES (?, 1, ?, ?, 'test', ?, now()) RETURNING id""", Long.class, otherMonth, trigger, "d".repeat(64), plannerId);

        mvc.perform(auth(get("/api/v1/home/pnl").param("year", y()).param("month", "10"), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("NONE"))
                .andExpect(jsonPath("$.kpis[0].actual").isEmpty())
                .andExpect(jsonPath("$.rows.length()").value(0));
        mvc.perform(auth(get("/api/v1/home/waterfall").param("year", y()).param("month", "10"), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(0));
        // 다른 달의 master
        mvc.perform(auth(get("/api/v1/home/pnl").param("year", y()).param("month", "10")
                .param("masterId", String.valueOf(master)), planner)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/home/pnl").param("year", y()).param("period", "WEEK"), planner))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/home/op-drivers").param("year", y()).param("limit", "11"), planner))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/home/divisions").param("year", y()).param("unit", "KRW"), planner))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/home/pnl").param("year", y()), manager)).andExpect(status().isForbidden());
    }

    private String y() {
        return String.valueOf(year);
    }

    private long item(String name, Long parent, String kind, boolean leaf, int order) {
        return jdbc.queryForObject("""
                INSERT INTO report_item(code, name, parent_id, kind, is_leaf, display_order)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                "HP" + UUID.randomUUID(), name, parent, kind, leaf, 1_000_000 + order);
    }

    /** A·B 모두에 요청을 보낸다. */
    private long request(String type, Integer month) {
        long id = jdbc.queryForObject("""
                INSERT INTO submission_request(annual_plan_id, type, month, status, requested_by, requested_at, period_key)
                VALUES (?, ?, ?, 'OPEN', ?, now(), ?) RETURNING id""", Long.class, planId, type, month, plannerId,
                year + "-" + type + "-" + month);
        jdbc.update("INSERT INTO request_target(request_id, organization_id, selected) VALUES (?, ?, true), (?, ?, true)",
                id, orgA, id, orgB);
        return id;
    }

    private long submission(long requestId, long orgId, String status, Integer approvedSeq) {
        String key = "uploads/test/" + UUID.randomUUID() + ".xlsx";
        jdbc.update("INSERT INTO stored_object(object_key, content_type, size_bytes, sha256) VALUES (?, 'application/octet-stream', 1, ?)",
                key, "0".repeat(64));
        return jdbc.queryForObject("""
                INSERT INTO submission(request_id, organization_id, seq, status, original_filename, source_object_key,
                    source_sha256, uploaded_by, uploaded_at, submitted_by, submitted_at, reviewed_by, reviewed_at, approved_seq)
                VALUES (?, ?, 1, ?, 'f.xlsx', ?, ?, ?, now(), ?, now(), ?, now(), ?) RETURNING id""", Long.class,
                requestId, orgId, status, key, "a".repeat(64), plannerId, plannerId,
                "APPROVED".equals(status) ? plannerId : null, approvedSeq);
    }

    /** (보고 항목, 금액) 쌍마다 줄 하나와 값 하나 */
    private void values(long submissionId, String scenario, int month, long... itemAndAmount) {
        for (int i = 0; i < itemAndAmount.length; i += 2) {
            long line = jdbc.queryForObject("""
                    INSERT INTO financial_line(submission_id, report_item_id, source_sheet, source_row)
                    VALUES (?, ?, '손익', ?) RETURNING id""", Long.class, submissionId, itemAndAmount[i], i + month * 100);
            jdbc.update("""
                    INSERT INTO financial_value(line_id, year, month, scenario, amount, source_record_id, source_cell)
                    VALUES (?, ?, ?, ?, ?, ?, 'N1')""", line, year, month, scenario, itemAndAmount[i + 1],
                    "r" + UUID.randomUUID());
        }
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }
}
