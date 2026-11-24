package io.skala.backend.domain.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.global.security.SessionTokenStore;
import io.skala.backend.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AI 보고서용 뷰(report 스키마, #60)가 홈 화면(HOME-03)과 같은 숫자를 내는지 확인한다.
 * 시나리오는 HomePnlIntegrationTest와 같다(조직 A·B, 10월 월간 요청).
 * <pre>
 *            실적(10월)        계획(확정 연간 계획 10월)
 * 제품매출   A 1000 · B 500    A 800 · B 600
 * 판촉비     A 200             A 100
 * 인건비     A 300 · B 100     A 300 · B 100
 * </pre>
 */
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ReportViewsIntegrationTest extends PostgresIntegrationTest {

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
    String codeA;
    long plannerId;
    String planner;
    int year;
    long planId;
    long product;
    long promotion;
    long labor;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        codeA = "RA" + s;
        orgA = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '보고A') RETURNING id", Long.class, codeA);
        orgB = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '보고B') RETURNING id", Long.class, "RB" + s);
        plannerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role) VALUES (?, '기획', 'PLANNER') RETURNING id",
                Long.class, "rv" + s + "@sk.com");
        planner = sessions.issue(plannerId).token();
        do {
            year = ThreadLocalRandom.current().nextInt(2100, 9999);
        } while (jdbc.queryForObject("SELECT count(*) FROM annual_plan WHERE year = ?", Integer.class, year) > 0);
        planId = jdbc.queryForObject("INSERT INTO annual_plan(year, status) VALUES (?, 'CONFIRMED') RETURNING id", Long.class, year);

        long revenueRoot = item("매출", null, "REVENUE", false, 1);
        product = item("제품매출", revenueRoot, "REVENUE", true, 2);
        long costRoot = item("영업비용", null, "COST", false, 3);
        long marketing = item("마케팅비용", costRoot, "COST", false, 4);
        promotion = item("판촉비", marketing, "COST", true, 5);
        labor = item("인건비", costRoot, "COST", true, 6);
    }

    @Test
    void reportViewsMatchHomeAndKeepReportRules() throws Exception {
        long annual = request("ANNUAL_PLAN", null);
        long planA = submission(annual, orgA, "APPROVED", 1);
        values(planA, "PLAN", 10, product, 800, promotion, 100, labor, 300);
        long planB = submission(annual, orgB, "APPROVED", 1);
        values(planB, "PLAN", 10, product, 600, labor, 100);

        long october = request("MONTHLY_ACTUAL", 10);
        jdbc.update("UPDATE request_target SET confirmed_plan_submission_id = ? WHERE request_id = ? AND organization_id = ?", planA, october, orgA);
        jdbc.update("UPDATE request_target SET confirmed_plan_submission_id = ? WHERE request_id = ? AND organization_id = ?", planB, october, orgB);
        long octA = submission(october, orgA, "APPROVED", 1);
        values(octA, "ACTUAL", 10, product, 1000, promotion, 200, labor, 300);
        priorYear(octA, product, 777); // 양식의 전년 실적 열: 참고값이라 뷰에 나오면 안 됨
        long octB = submission(october, orgB, "PENDING", null);
        values(octB, "ACTUAL", 10, product, 500, labor, 100);
        String provisional = "provisional-" + year + "-10";
        String confirmed = "master-" + year + "-10";

        // B 승인 전: A 승인본만으로 잠정 version, 홈 PROVISIONAL과 같은 숫자
        assertThat(sum(provisional, "revenue_won", "ACTUAL")).isEqualByComparingTo(homeKpi(null, 0, "actual"));

        jdbc.update("UPDATE submission SET status = 'APPROVED', approved_seq = 1, reviewed_by = ?, reviewed_at = now() WHERE id = ?", plannerId, octB);
        long master = jdbc.queryForObject("""
                INSERT INTO master_version(request_id, seq, trigger_submission_id, content_sha256, calculation_version,
                    created_by, created_at)
                VALUES (?, 1, ?, ?, 'test', ?, now()) RETURNING id""", Long.class, october, octB, "c".repeat(64), plannerId);
        jdbc.update("INSERT INTO master_source(master_id, organization_id, submission_id) VALUES (?, ?, ?), (?, ?, ?)",
                master, orgA, octA, master, orgB, octB);

        // master가 생기면 잠정 version은 사라지고 확정 version 하나만 남는다
        assertThat(jdbc.queryForList("SELECT version FROM report.dim_version WHERE year = ? AND month = 10", String.class, year))
                .containsExactly(confirmed);

        // 홈(HOME-03)과 같은 숫자: 매출·영업비용·영업이익 실적과 계획
        for (int k = 0; k < 3; k++) {
            String column = List.of("revenue_won", "cost_won", "op_won").get(k);
            assertThat(sum(confirmed, column, "ACTUAL")).as(column + " 실적").isEqualByComparingTo(homeKpi(master, k, "actual"));
            assertThat(sum(confirmed, column, "ACTUAL").subtract(sum(confirmed, column, "PLAN"))).as(column + " 계획 대비")
                    .isEqualByComparingTo(homeKpi(master, k, "vsPlanAmount"));
        }
        assertThat(sum(confirmed, "revenue_won", "ACTUAL")).isEqualByComparingTo("1500");
        assertThat(sum(confirmed, "op_won", "PLAN")).isEqualByComparingTo("900");

        // 요청 연도 값만(전년 열 제외), 항목 계층, 팀명·담당자(#58)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report.fact WHERE version = ? AND year <> ?", Integer.class,
                confirmed, year)).isZero();
        Map<String, Object> promo = jdbc.queryForMap("""
                SELECT cat1, cat2, item, team, manager FROM report.fact
                WHERE version = ? AND org_code = ? AND item = '판촉비' AND scenario = 'ACTUAL'""", confirmed, codeA);
        assertThat(promo).containsEntry("cat1", "마케팅비용").containsEntry("cat2", null).containsEntry("item", "판촉비")
                .containsEntry("team", "팀" + codeA).containsEntry("manager", "담당자" + codeA);

        // Gap: 계획·직전 두 기준을 모두 넘은 판촉비. 소명은 한 건이므로 explanation은 한 행(기준 수만큼 반복하지 않음)
        long reviewItem = jdbc.queryForObject("""
                INSERT INTO review_item(submission_id, report_item_id, year, month, scenario, amount_present, current_amount,
                    change_detected, gap_exceeded, source_fingerprint, context_fingerprint, current_round)
                VALUES (?, ?, ?, 10, 'ACTUAL', true, 200, true, true, ?, ?, 1) RETURNING id""", Long.class,
                octA, promotion, year, "a".repeat(64), "b".repeat(64));
        long threshold = jdbc.queryForObject("""
                INSERT INTO gap_threshold(comparison, percent, amount, applied_by, applied_at)
                VALUES ('ACTUAL_VS_PLAN', 10, 50, ?, now()) RETURNING id""", Long.class, plannerId);
        for (String basis : List.of("PLAN", "PREVIOUS")) {
            jdbc.update("""
                    INSERT INTO gap_comparison(review_item_id, basis, baseline_submission_id, baseline_scenario, baseline_amount,
                        current_amount, gap_amount, gap_rate, threshold_id, exceeded)
                    VALUES (?, ?, ?, 'PLAN', 100, 200, 100, 100, ?, true)""", reviewItem, basis, planA, threshold);
        }
        jdbc.update("INSERT INTO review_explanation(review_item_id, round, answer, verdict) VALUES (?, 1, '판촉 행사', 'ACCEPTED')", reviewItem);
        // Gap을 넘지 않은 변경 소명(검토 중)
        long changed = jdbc.queryForObject("""
                INSERT INTO review_item(submission_id, report_item_id, year, month, scenario, amount_present, current_amount,
                    change_detected, gap_exceeded, source_fingerprint, context_fingerprint, current_round)
                VALUES (?, ?, ?, 10, 'ACTUAL', true, 300, true, false, ?, ?, 1) RETURNING id""", Long.class,
                octA, labor, year, "c".repeat(64), "d".repeat(64));
        jdbc.update("INSERT INTO review_explanation(review_item_id, round, answer, verdict) VALUES (?, 1, '인원 충원', 'PENDING')", changed);

        assertThat(jdbc.queryForList("SELECT gap_id || ':' || status || ':' || rate_percent FROM report.gap WHERE version = ? AND org_code = ? ORDER BY 1",
                String.class, confirmed, codeA))
                .containsExactly("RI-" + reviewItem + "-PLAN:ACCEPTED:100.00", "RI-" + reviewItem + "-PREVIOUS:ACCEPTED:100.00");
        List<Map<String, Object>> explanations = jdbc.queryForList("""
                SELECT gap_id, link_type, status, answer FROM report.explanation WHERE version = ? AND org_code = ? ORDER BY link_type DESC""",
                confirmed, codeA);
        assertThat(explanations).hasSize(2);
        assertThat(explanations.get(0)).containsEntry("gap_id", "RI-" + reviewItem + "-PLAN").containsEntry("link_type", "GAP")
                .containsEntry("status", "수락").containsEntry("answer", "판촉 행사");
        assertThat(explanations.get(1)).containsEntry("gap_id", null).containsEntry("link_type", "CHANGE")
                .containsEntry("status", "검토중").containsEntry("answer", null);

        // 연간 확정 계획(annual master)도 version이 된다. 월간 master가 있으면 기본 기준(is_latest)이 되지 않는다
        long annualMaster = jdbc.queryForObject("""
                INSERT INTO annual_master(request_id, seq, trigger_submission_id, content_sha256, calculation_version, created_by, created_at)
                VALUES (?, 1, ?, ?, 'test', ?, now()) RETURNING id""", Long.class, annual, planB, "e".repeat(64), plannerId);
        jdbc.update("INSERT INTO annual_master_source(master_id, organization_id, submission_id) VALUES (?, ?, ?), (?, ?, ?)",
                annualMaster, orgA, planA, annualMaster, orgB, planB);
        assertThat(sum("annual-" + year, "revenue_won", "PLAN")).isEqualByComparingTo("1400");
        assertThat(jdbc.queryForObject("SELECT is_latest FROM report.dim_version WHERE version = ?", Integer.class, "annual-" + year))
                .isZero();

        // 다른 달이 잠정이어도 확정 월이 있으면 잠정 달은 기본 기준이 아니다
        long november = request("MONTHLY_ACTUAL", 11);
        long novA = submission(november, orgA, "APPROVED", 1);
        values(novA, "ACTUAL", 11, product, 10);
        assertThat(jdbc.queryForObject("SELECT is_latest FROM report.dim_version WHERE version = ?", Integer.class,
                "provisional-" + year + "-11")).isZero();
    }

    /** v_op의 10월 합계 */
    private BigDecimal sum(String version, String column, String scenario) {
        return jdbc.queryForObject("SELECT coalesce(sum(" + column + "), 0) FROM report.v_op WHERE version = ? AND month = 10 AND scenario = ?",
                BigDecimal.class, version, scenario);
    }

    private BigDecimal homeKpi(Long masterId, int index, String field) throws Exception {
        var request = get("/api/v1/home/pnl").param("year", String.valueOf(year)).param("month", "10")
                .header("Authorization", "Bearer " + planner);
        if (masterId != null) {
            request = request.param("masterId", String.valueOf(masterId));
        }
        JsonNode body = objectMapper.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
        return new BigDecimal(body.at("/kpis/" + index + "/" + field).asText());
    }

    private long item(String name, Long parent, String kind, boolean leaf, int order) {
        return jdbc.queryForObject("""
                INSERT INTO report_item(code, name, parent_id, kind, is_leaf, display_order)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                "RV" + UUID.randomUUID(), name, parent, kind, leaf, 2_000_000 + order);
    }

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

    /** (보고 항목, 금액) 쌍마다 줄 하나와 값 하나. 팀명·담당자는 조직 코드로 채운다 */
    private void values(long submissionId, String scenario, int month, long... itemAndAmount) {
        String code = jdbc.queryForObject("SELECT o.code FROM submission s JOIN organization o ON o.id = s.organization_id WHERE s.id = ?",
                String.class, submissionId);
        for (int i = 0; i < itemAndAmount.length; i += 2) {
            long line = jdbc.queryForObject("""
                    INSERT INTO financial_line(submission_id, report_item_id, source_sheet, source_row, team_name, person_in_charge)
                    VALUES (?, ?, '손익', ?, ?, ?) RETURNING id""", Long.class, submissionId, itemAndAmount[i], i + month * 100,
                    "팀" + code, "담당자" + code);
            jdbc.update("""
                    INSERT INTO financial_value(line_id, year, month, scenario, amount, source_record_id, source_cell)
                    VALUES (?, ?, ?, ?, ?, ?, 'N1')""", line, year, month, scenario, itemAndAmount[i + 1], "r" + UUID.randomUUID());
        }
    }

    private void priorYear(long submissionId, long item, long amount) {
        long line = jdbc.queryForObject("""
                INSERT INTO financial_line(submission_id, report_item_id, source_sheet, source_row)
                VALUES (?, ?, '손익', 9999) RETURNING id""", Long.class, submissionId, item);
        jdbc.update("""
                INSERT INTO financial_value(line_id, year, month, scenario, amount, source_record_id, source_cell)
                VALUES (?, ?, 10, 'ACTUAL', ?, ?, 'B1')""", line, year - 1, amount, "r" + UUID.randomUUID());
    }
}
