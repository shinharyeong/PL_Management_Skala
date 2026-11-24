package io.skala.backend.domain.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.skala.backend.domain.master.dto.PublishedMaster;
import io.skala.backend.domain.master.service.MasterPublisher;
import io.skala.backend.global.security.SessionTokenStore;
import io.skala.backend.support.PostgresIntegrationTest;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class MasterIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SessionTokenStore sessions;
    @Autowired
    MasterPublisher publisher;
    @Autowired
    PlatformTransactionManager transactionManager;

    long orgA;
    long orgB;
    long orgC;
    long plannerId;
    String planner;
    String manager;
    int year;
    long planId;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        orgA = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '마스터A') RETURNING id", Long.class, "MA" + s);
        orgB = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '마스터B') RETURNING id", Long.class, "MB" + s);
        orgC = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '마스터C') RETURNING id", Long.class, "MC" + s);
        plannerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role) VALUES (?, '기획', 'PLANNER') RETURNING id", Long.class, "mp" + s + "@sk.com");
        long managerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role, organization_id) VALUES (?, '담당', 'ORG_MANAGER', ?) RETURNING id",
                Long.class, "mm" + s + "@sk.com", orgA);
        planner = sessions.issue(plannerId).token();
        manager = sessions.issue(managerId).token();
        do {
            year = ThreadLocalRandom.current().nextInt(2100, 9999);
        } while (jdbc.queryForObject("SELECT count(*) FROM annual_plan WHERE year = ?", Integer.class, year) > 0);
        planId = jdbc.queryForObject("INSERT INTO annual_plan(year, status) VALUES (?, 'COLLECTING') RETURNING id", Long.class, year);
    }

    @Test
    void annualMasterIsPublishedWhenEveryRequestedOrganizationIsApprovedAndConfirmsPlan() throws Exception {
        long request = insertRequest("ANNUAL_PLAN", null);
        long a1 = insertSubmission(request, orgA, "APPROVED", 1, 1);
        assertThat(publish(a1)).isEmpty();
        assertThat(planStatus()).isEqualTo("COLLECTING");

        long b1 = insertSubmission(request, orgB, "APPROVED", 1, 1);
        PublishedMaster first = publish(b1).orElseThrow();
        assertThat(first.label()).isEqualTo("master_plan" + year + ".01");
        assertThat(planStatus()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM annual_master_source WHERE master_id = ?", Integer.class, first.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT content_sha256 FROM annual_master WHERE id = ?", String.class, first.id())).hasSize(64);

        assertThat(publish(b1)).isEmpty();

        // 월간 요청을 보내면 그 시점 최신 연간 master의 승인본이 확정 계획으로 들어간다.
        long january = sendMonthlyRequest(1);
        assertThat(confirmedPlan(january, orgA)).isEqualTo(a1);
        assertThat(confirmedPlan(january, orgB)).isEqualTo(b1);
        assertThat(confirmedPlan(january, orgC)).isNull();

        long a2 = insertSubmission(request, orgA, "APPROVED", 2, 2);
        PublishedMaster second = publish(a2).orElseThrow();
        assertThat(second.label()).isEqualTo("master_plan" + year + ".02");
        assertThat(jdbc.queryForObject("SELECT previous_master_id FROM annual_master WHERE id = ?", Long.class, second.id())).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT submission_id FROM annual_master_source WHERE master_id = ? AND organization_id = ?",
                Long.class, second.id(), orgA)).isEqualTo(a2);

        // 재승인으로 .02가 생기면 그 뒤에 보내는 요청부터 새 계획을 쓰고, 이미 보낸 요청은 그대로 둔다.
        long february = sendMonthlyRequest(2);
        assertThat(confirmedPlan(february, orgA)).isEqualTo(a2);
        assertThat(confirmedPlan(january, orgA)).isEqualTo(a1);

        long pending = insertSubmission(request, orgB, "PENDING", 2, null);
        assertThatThrownBy(() -> publish(pending)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void monthlyMastersAreListedAndDownloadedAsExcel() throws Exception {
        long october = insertRequest("MONTHLY_ACTUAL", 10);
        insertRequest("MONTHLY_ACTUAL", 11);
        long a = insertSubmission(october, orgA, "APPROVED", 1, 1);
        assertThat(publish(a)).isEmpty();
        long item = jdbc.queryForObject("INSERT INTO report_item(code, name, kind, is_leaf) VALUES (?, '매출', 'REVENUE', true) RETURNING id",
                Long.class, "RI" + UUID.randomUUID());
        long line = jdbc.queryForObject("INSERT INTO financial_line(submission_id, report_item_id, source_sheet, source_row) VALUES (?, ?, '손익', 3) RETURNING id",
                Long.class, a, item);
        long line2 = jdbc.queryForObject("INSERT INTO financial_line(submission_id, report_item_id, source_sheet, source_row) VALUES (?, ?, '손익', 4) RETURNING id",
                Long.class, a, item);
        jdbc.update("""
                INSERT INTO financial_value(line_id, year, month, scenario, amount, source_record_id, source_cell)
                VALUES (?, ?, 10, 'ACTUAL', 1200, 'r1', 'N3'), (?, ?, 10, 'ACTUAL', 300, 'r2', 'N4')""", line, year, line2, year);
        // 양식의 전년 실적 열(참고값)은 master 엑셀에 넣지 않는다.
        jdbc.update("""
                INSERT INTO financial_value(line_id, year, month, scenario, amount, source_record_id, source_cell)
                VALUES (?, ?, 10, 'ACTUAL', 777, 'r3', 'B3')""", line, year - 1);

        long b = insertSubmission(october, orgB, "APPROVED", 1, 1);
        PublishedMaster master = publish(b).orElseThrow();
        assertThat(master.label()).isEqualTo("master_ver10.01");

        mvc.perform(auth(get("/api/v1/masters").param("year", String.valueOf(year)), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].month").value(11))
                .andExpect(jsonPath("$.items[0].state").value("WAITING"))
                .andExpect(jsonPath("$.items[0].id").isEmpty())
                .andExpect(jsonPath("$.items[1].month").value(10))
                .andExpect(jsonPath("$.items[1].state").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[1].id").value(String.valueOf(master.id())))
                .andExpect(jsonPath("$.items[1].label").value("master_ver10.01"));

        byte[] file = mvc.perform(auth(get("/api/v1/masters/" + master.id() + "/file"), planner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet values = workbook.getSheet("손익");
            assertThat(values.getLastRowNum()).isEqualTo(1);
            Row row = values.getRow(1);
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("마스터A");
            assertThat(row.getCell(4).getStringCellValue()).isEqualTo("ACTUAL");
            assertThat(row.getCell(5).getNumericCellValue()).isEqualTo(year);
            assertThat(row.getCell(7).getNumericCellValue()).isEqualTo(1500d);
            assertThat(workbook.getSheet("출처").getLastRowNum()).isEqualTo(2);
        }

        mvc.perform(auth(get("/api/v1/masters/999999999/file"), planner)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/masters"), manager)).andExpect(status().isForbidden());
    }

    private long sendMonthlyRequest(int month) throws Exception {
        String body = "{\"type\":\"MONTHLY_ACTUAL\",\"year\":%d,\"month\":%d,\"dueAt\":\"2999-01-01T00:00:00Z\",\"organizationIds\":[\"%d\",\"%d\"]}"
                .formatted(year, month, orgA, orgB);
        String location = mvc.perform(auth(post("/api/v1/submission-requests"), planner)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.replaceAll(".*/", ""));
    }

    private Long confirmedPlan(long requestId, long orgId) {
        return jdbc.queryForObject("SELECT confirmed_plan_submission_id FROM request_target WHERE request_id = ? AND organization_id = ?",
                Long.class, requestId, orgId);
    }

    @Test
    void annualMasterListingAndDownloadUseAnnualSnapshotsAndPlannerPermissions() throws Exception {
        mvc.perform(auth(get("/api/v1/annual-masters").param("year",String.valueOf(year)),planner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NO_REQUEST"));
        long request=insertRequest("ANNUAL_PLAN",null);
        mvc.perform(auth(get("/api/v1/annual-masters").param("year",String.valueOf(year)),planner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("WAITING"));
        long a=insertSubmission(request,orgA,"APPROVED",1,1);
        long b=insertSubmission(request,orgB,"APPROVED",1,1);
        PublishedMaster master=publish(b).orElseThrow();
        mvc.perform(auth(get("/api/v1/annual-masters").param("year",String.valueOf(year)),planner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].label").value(master.label()))
                .andExpect(jsonPath("$.items[0].organizationCount").value(2));
        byte[] data=mvc.perform(auth(get("/api/v1/annual-masters/"+master.id()+"/file"),planner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(XSSFWorkbook workbook=new XSSFWorkbook(new ByteArrayInputStream(data))){
            assertThat(workbook.getSheet("출처").getLastRowNum()).isEqualTo(2);
            assertThat(workbook.getSheet("손익")).isNotNull();
        }
        mvc.perform(auth(get("/api/v1/annual-masters").param("year",String.valueOf(year)),manager)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/annual-masters/"+master.id()+"/file"),manager)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/annual-masters/9223372036854775807/file"),planner)).andExpect(status().isNotFound());
    }

    private Optional<PublishedMaster> publish(long submissionId) {
        return new TransactionTemplate(transactionManager).execute(status -> publisher.publishIfComplete(submissionId));
    }

    private String planStatus() {
        return jdbc.queryForObject("SELECT status FROM annual_plan WHERE id = ?", String.class, planId);
    }

    /** A·B에는 요청을 보내고 C는 보내지 않음(selected=false) */
    private long insertRequest(String type, Integer month) {
        long id = jdbc.queryForObject("""
                INSERT INTO submission_request(annual_plan_id, type, month, status, requested_by, requested_at, period_key)
                VALUES (?, ?, ?, 'OPEN', ?, now(), ?) RETURNING id""", Long.class, planId, type, month, plannerId,
                year + "-" + type + "-" + month);
        jdbc.update("INSERT INTO request_target(request_id, organization_id, selected) VALUES (?, ?, true), (?, ?, true), (?, ?, false)",
                id, orgA, id, orgB, id, orgC);
        return id;
    }

    private long insertSubmission(long requestId, long orgId, String status, int seq, Integer approvedSeq) {
        String key = "uploads/test/" + UUID.randomUUID() + ".xlsx";
        jdbc.update("INSERT INTO stored_object(object_key, content_type, size_bytes, sha256) VALUES (?, 'application/octet-stream', 1, ?)",
                key, "0".repeat(64));
        return jdbc.queryForObject("""
                INSERT INTO submission(request_id, organization_id, seq, status, original_filename, source_object_key,
                    source_sha256, uploaded_by, uploaded_at, submitted_by, submitted_at, reviewed_by, reviewed_at, approved_seq)
                VALUES (?, ?, ?, ?, 'f.xlsx', ?, ?, ?, now(), ?, now(), ?, now(), ?) RETURNING id""", Long.class,
                requestId, orgId, seq, status, key, "a".repeat(64), plannerId, plannerId,
                "APPROVED".equals(status) ? plannerId : null, approvedSeq);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }
}
