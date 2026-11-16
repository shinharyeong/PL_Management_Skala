package io.skala.backend.master;

import static io.skala.backend.submission.ApiData.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.Actor;
import io.skala.backend.security.Organization;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

/** 연간 확정과 월 마스터의 원자성·불변성·월별 의미 변경을 독립 메모리 DB로 검증한다. */
class AnnualPlanMonthlyMasterTests {
    private EmbeddedDatabase database;
    private JdbcClient jdbc;
    private ObjectMapper mapper;
    private TransactionTemplate transactions;
    private AnnualPlanService plans;
    private MonthlyMasterService masters;
    private Actor planner;

    /** 두 사업조직의 12개월 승인 계획과 V1~V4를 메모리 DB에 준비한다.
     * Args: 없음. Returns: 없음. Raises: DB·JSON 오류. Side Effects: 합성 메모리 DB만 생성한다.
     */
    @BeforeEach
    void prepare() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName("monthly_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=YEAR,MONTH;LOCK_TIMEOUT=10000").build();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__submission_parser_foundation.sql"), new ClassPathResource("db/migration/V2__validated_financial_submissions.sql"),
                new ClassPathResource("db/migration/V3__annual_plan_masters.sql"), new ClassPathResource("db/migration/V4__monthly_masters.sql")).execute(database);
        jdbc = JdbcClient.create(database); mapper = new ObjectMapper(); var manager = new DataSourceTransactionManager(database); transactions = new TransactionTemplate(manager);
        plans = new AnnualPlanService(jdbc, mapper, new CommandExecutor(new JdbcTemplate(database), manager), new AnnualMasterService(jdbc, mapper));
        masters = new MonthlyMasterService(jdbc, mapper, plans); planner = new Actor("1", "planner@example.test", "기획", "PLANNER", null);
        jdbc.sql("INSERT INTO organization(id,code,name) VALUES (1,'B1','사업1'),(2,'B2','사업2')").update();
        jdbc.sql("INSERT INTO app_user(id,email,name,role) VALUES (1,'planner@example.test','기획','PLANNER'),(2,'second@example.test','기획2','PLANNER')").update();
        jdbc.sql("INSERT INTO annual_plan(id,year,status) VALUES (1,2031,'COLLECTING')").update();
        jdbc.sql("INSERT INTO report_item(id,code,name,kind,is_leaf) VALUES (1,'R','매출','REVENUE',TRUE),(2,'C','비용','COST',TRUE),(3,'REVENUE_TOTAL','매출 합계','DERIVED',FALSE),(4,'COST_TOTAL','비용 합계','DERIVED',FALSE),(5,'OP','영업이익','DERIVED',FALSE)").update();
        request(1, 0); submission(1, 1, 1, 1, "APPROVED", "100", "70", Map.of()); submission(2, 2, 1, 1, "APPROVED", "200", "160", Map.of());
    }

    /** 메모리 DB를 종료한다. Args: 없음. Returns: 없음. Raises: DB오류. Side Effects: 메모리 해제만 수행한다. */
    @AfterEach
    void close() { if (database != null) database.shutdown(); }

    /** 확정은 최신 전원 승인·명시 금액·토큰을 요구하며 계획본·금액·요청을 함께 잠근다.
     * Args: 없음. Returns: 없음. Raises: 검증오류. Side Effects: 합성 확정·멱등 재전달만 수행한다.
     */
    @Test
    void confirmationPinsSourcesContingencyAndClosedRequestIdempotently() throws Exception {
        var before = plans.get(planner, "2031", Map.of()); String token = before.get("stateToken").toString();
        assertThatThrownBy(() -> plans.confirm(planner, "plan-null-001", "2031", new AnnualPlanService.ConfirmInput(token, null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> plans.confirm(planner, "plan-neg-001", "2031", new AnnualPlanService.ConfirmInput(token, "-1"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"expectedStateToken\":\"test\",\"contingencyAmount\":1}", AnnualPlanService.ConfirmInput.class)).isInstanceOf(Exception.class);
        var input = new AnnualPlanService.ConfirmInput(token, "11776550000"); var result = plans.confirm(planner, "plan-confirm-001", "2031", input);
        var confirmed = mapper.readTree(result.body()); assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(new BigDecimal(confirmed.path("contingencyAmount").asText())).isEqualByComparingTo("11776550000");
        assertThat(confirmed.path("sources").findValuesAsText("confirmedPlanSubmissionId")).containsExactly("1", "2");
        assertThat(jdbc.sql("SELECT status FROM submission_request WHERE id=1").query(String.class).single()).isEqualTo("CLOSED");
        var replay = plans.confirm(planner, "plan-confirm-001", "2031", input); assertThat(replay.replayed()).isTrue(); assertThat(replay.body()).isEqualTo(result.body());
        assertThatThrownBy(() -> plans.confirm(planner, "plan-confirm-002", "2031", input)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ANNUAL_PLAN_LOCKED");
    }

    /** 최신 대기 회차가 있거나 금액이 손상되면 오래된 승인본으로 확정하지 않는다.
     * Args: 없음. Returns: 없음. Raises: 검증오류. Side Effects: 합성 제출·금액만 변경하고 실패한 확정은 롤백한다.
     */
    @Test
    void confirmationRejectsStaleIncompleteAndDamagedCandidatesWithoutPartialWrites() {
        String oldToken = plans.get(planner, "2031", Map.of()).get("stateToken").toString();
        submission(3, 1, 1, 2, "PENDING", "100", "70", Map.of());
        assertThatThrownBy(() -> plans.confirm(planner, "plan-stale-001", "2031", new AnnualPlanService.ConfirmInput(oldToken, "0"))).isInstanceOf(ApiException.class).extracting("code").isEqualTo("STATE_TOKEN_MISMATCH");
        assertThatThrownBy(this::confirmPlan).isInstanceOf(ApiException.class).extracting("code").isEqualTo("PLAN_APPROVALS_INCOMPLETE");
        jdbc.sql("UPDATE submission SET status='APPROVED' WHERE id=3").update(); jdbc.sql("UPDATE financial_value SET amount=101 WHERE line_id=30 AND month=1").update();
        assertThatThrownBy(this::confirmPlan).isInstanceOf(ApiException.class).extracting("code").isEqualTo("MASTER_SOURCE_INVALID");
        assertThat(jdbc.sql("SELECT status FROM annual_plan").query(String.class).single()).isEqualTo("COLLECTING");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_target WHERE confirmed_plan_submission_id IS NOT NULL").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM submission_request WHERE id=1").query(String.class).single()).isEqualTo("OPEN");
    }

    /** 마지막 승인에서만 월 마스터를 만들고 실제·계획·Gap·별도 Contingency를 정확히 제공한다.
     * Args: 없음. Returns: 없음. Raises: 검증오류. Side Effects: 합성 확정·월 제출·승인·발행.
     */
    @Test
    void firstPublicationWaitsForEveryoneAndKeepsActualPlanAndContingencySeparate() {
        confirmPlan(); request(2, 1); submission(10, 1, 2, 1, "PENDING", "150.000001", "70", Map.of()); submission(20, 2, 2, 1, "PENDING", "200", "160", Map.of());
        assertThat(approve("10").getFirst().get("action")).isEqualTo("WAITING_INITIAL");
        var waiting = mapper.valueToTree(masters.periods(planner, Map.of("year", "2031"))); assertThat(waiting.path("items").get(0).path("totals").isNull()).isTrue();
        var effect = approve("20").getFirst(); assertThat(effect.get("action")).isEqualTo("CREATED");
        var detail = mapper.valueToTree(masters.detail(planner, effect.get("masterId").toString(), Map.of()));
        assertThat(new BigDecimal(detail.path("totals").path("revenue").asText())).isEqualByComparingTo("350.000001");
        assertThat(new BigDecimal(detail.path("totals").path("operatingProfit").asText())).isEqualByComparingTo("120.000001");
        assertThat(new BigDecimal(detail.path("planTotals").path("operatingProfit").asText())).isEqualByComparingTo("70");
        assertThat(new BigDecimal(detail.path("comparisons").path("operatingProfit").path("gapAmount").asText())).isEqualByComparingTo("50.000001");
        assertThat(detail.path("comparisons").path("operatingProfit").path("gapRate").asText()).isEqualTo("71.43");
        assertThat(detail.path("sources").size()).isEqualTo(2); assertThat(detail.path("organizations").size()).isEqualTo(2);
        assertThat(detail.path("organizations").findValuesAsText("planSubmissionId")).containsExactly("1", "2");
        assertThat(new BigDecimal(detail.path("annualContingency").path("amount").asText())).isEqualByComparingTo("1000000");
        var periods = mapper.valueToTree(masters.periods(planner, Map.of("year", "2031")));
        assertThat(periods.path("items").size()).isEqualTo(12); assertThat(periods.path("items").get(1).path("publicationState").asText()).isEqualTo("NOT_REQUESTED");
        var values = mapper.valueToTree(masters.reportValues(planner, effect.get("masterId").toString(), Map.of()));
        assertThat(values.path("items").size()).isEqualTo(5); for (var value : values.path("items")) { assertThat(value.path("scenario").asText()).isEqualTo("ACTUAL"); assertThat(value.path("month").asInt()).isEqualTo(1); }
    }

    /** 후속 달의 과거 실제 변경은 해당 월만 갱신하고 다른 조직 대기본과 ESTIMATE는 섞지 않는다.
     * Args: 없음. Returns: 없음. Raises: 검증오류. Side Effects: 합성 후속 승인·새 버전 생성.
     */
    @Test
    void laterSnapshotUpdatesPastActualOnlyAndPreservesOtherFrozenSourcesAndOldVersion() {
        confirmPlan(); request(2, 1); submission(10, 1, 2, 1, "APPROVED", "150", "70", Map.of()); submission(20, 2, 2, 1, "APPROVED", "200", "160", Map.of());
        String firstId = publish("20").getFirst().get("masterId").toString(); var original = masters.detail(planner, firstId, Map.of());
        submission(21, 2, 2, 2, "PENDING", "999", "160", Map.of()); request(3, 2);
        submission(30, 1, 3, 1, "APPROVED", "9999", "70", Map.of(1, new String[]{"175", "70"}));
        var effects = publish("30"); assertThat(effects).hasSize(2); assertThat(effects.get(0).get("action")).isEqualTo("UPDATED"); assertThat(effects.get(1).get("action")).isEqualTo("WAITING_INITIAL");
        String secondId = effects.get(0).get("masterId").toString(); var updated = mapper.valueToTree(masters.detail(planner, secondId, Map.of()));
        assertThat(new BigDecimal(updated.path("totals").path("revenue").asText())).isEqualByComparingTo("375");
        assertThat(updated.path("sources").findValuesAsText("submissionId")).containsExactly("30", "20");
        assertThat(masters.detail(planner, firstId, Map.of())).isEqualTo(original);
        submission(31, 1, 3, 2, "APPROVED", "8888", "70", Map.of(1, new String[]{"175", "70"}));
        assertThat(publish("31").getFirst().get("action")).isEqualTo("UNCHANGED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM master_version").query(Long.class).single()).isEqualTo(2);
    }

    /** 금융 상세 누락과 고정 출처 손상은 부분 합계를 0으로 표시하지 않고 승인도 롤백한다.
     * Args: 없음. Returns: 없음. Raises: 검증오류. Side Effects: 합성 메모리 자료만 손상시켜 차단을 확인한다.
     */
    @Test
    void missingActualOrBrokenSourceNeverBecomesZeroAndRollsBackApproval() {
        confirmPlan(); request(2, 1); submission(10, 1, 2, 1, "APPROVED", "0", "0", Map.of()); submission(20, 2, 2, 1, "PENDING", "0", "0", Map.of());
        jdbc.sql("DELETE FROM financial_value WHERE line_id=200 AND month=1").update();
        assertThatThrownBy(() -> approve("20")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("MASTER_SOURCE_INVALID");
        assertThat(jdbc.sql("SELECT status FROM submission WHERE id=20").query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM master_version").query(Long.class).single()).isZero();
    }

    /** 같은 월의 동시 마지막 승인과 재호출은 최초 버전 하나만 남긴다.
     * Args: 없음. Returns: 없음. Raises: 스레드·검증오류. Side Effects: 합성 두 승인 트랜잭션.
     */
    @Test
    void concurrentLastApprovalsAndRetryCreateOnlyOneVersion() throws Exception {
        confirmPlan(); request(2, 1); submission(10, 1, 2, 1, "PENDING", "150", "70", Map.of()); submission(20, 2, 2, 1, "PENDING", "200", "160", Map.of());
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> simultaneous("10", ready, start)); var second = executor.submit(() -> simultaneous("20", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(Set.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("WAITING_INITIAL", "CREATED");
        }
        assertThat(publish("20").getFirst().get("action")).isEqualTo("UNCHANGED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM master_version").query(Long.class).single()).isEqualTo(1);
    }

    /** 조회 권한·필터·커서 범위와 명시적 확정 입력의 경계를 검사한다.
     * Args: 없음. Returns: 없음. Raises: 검증오류. Side Effects: 합성 마스터와 조회만 수행한다.
     */
    @Test
    void plannerOnlyQueriesRejectForeignCursorsAndEstimateFilters() {
        var manager = new Actor("3", "org@example.test", "담당", "ORG_MANAGER", new Organization("1", "B1", "사업1"));
        assertThatThrownBy(() -> plans.get(manager, "2031", Map.of())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> masters.periods(manager, Map.of("year", "2031"))).isInstanceOf(AccessDeniedException.class);
        confirmPlan(); request(2, 1); submission(10, 1, 2, 1, "APPROVED", "0", "0", Map.of()); submission(20, 2, 2, 1, "APPROVED", "0", "0", Map.of());
        String id = publish("20").getFirst().get("masterId").toString();
        assertThatThrownBy(() -> masters.reportValues(planner, id, Map.of("scenario", "ESTIMATE"))).isInstanceOf(ApiException.class);
        String cursor = masters.reportValues(planner, id, Map.of("limit", "1")).get("nextCursor").toString();
        assertThatThrownBy(() -> masters.reportValues(planner, id, Map.of("organizationId", "1", "cursor", cursor))).isInstanceOf(ApiException.class);
        var detail = mapper.valueToTree(masters.detail(planner, id, Map.of())); assertThat(detail.path("totals").path("operatingMargin").isNull()).isTrue();
        jdbc.sql("DELETE FROM master_source WHERE organization_id=2").update();
        assertThatThrownBy(() -> masters.detail(planner, id, Map.of())).isInstanceOf(ApiException.class).extracting("code").isEqualTo("MASTER_SOURCE_INVALID");
        assertThatThrownBy(() -> masters.reportValues(planner, id, Map.of())).isInstanceOf(ApiException.class);
    }

    /** 현재 후보와 명시한 합성 Contingency로 계획을 확정한다.
     * Args: 없음. Returns: 없음. Raises: 업무오류. Side Effects: 합성 계획·요청 확정.
     */
    private void confirmPlan() { plans.confirm(planner, "confirm-" + UUID.randomUUID(), "2031", new AnnualPlanService.ConfirmInput(plans.get(planner, "2031", Map.of()).get("stateToken").toString(), "1000000")); }
    /** 승인 후 마스터를 발행한다. Args: id 제출. Returns: 효과목록. Raises: 업무오류. Side Effects: 같은 합성트랜잭션 승인·발행. */
    private List<Map<String, Object>> approve(String id) { return transactions.execute(status -> { jdbc.sql("SELECT id FROM annual_plan WHERE id=1 FOR UPDATE").query(Long.class).single(); jdbc.sql("UPDATE submission SET status='APPROVED' WHERE id=?").param(Long.parseLong(id)).update(); return masters.publishAfterApproval(planner, id); }); }
    /** 승인본 발행을 호출한다. Args: id 제출. Returns: 효과목록. Raises: 업무오류. Side Effects: 합성트랜잭션 발행. */
    private List<Map<String, Object>> publish(String id) { return transactions.execute(status -> masters.publishAfterApproval(planner, id)); }
    /** 동시 시작 래치 후 승인한다. Args: id 제출,ready/start 래치. Returns: 결과문자열. Raises: 중단·업무오류. Side Effects: 합성승인. */
    private String simultaneous(String id, CountDownLatch ready, CountDownLatch start) throws InterruptedException { ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("대기 실패"); return approve(id).getFirst().get("action").toString(); }
    /** 합성 고정 대상 요청을 추가한다. Args: id 요청,month 0이면연간. Returns: 없음. Raises: DB오류. Side Effects: H2 INSERT. */
    private void request(long id, int month) {
        jdbc.sql("INSERT INTO submission_request(id,annual_plan_id,type,month,status,requested_by,requested_at,period_key) VALUES (?,1,?,?,'OPEN',1,CURRENT_TIMESTAMP,?)")
                .params(id, month == 0 ? "ANNUAL_PLAN" : "MONTHLY_ACTUAL", month == 0 ? null : month, month == 0 ? "2031-PLAN" : "2031-" + month).update();
        jdbc.sql("INSERT INTO request_target(request_id,organization_id) VALUES (?,1),(?,2)").params(id, id).update();
    }

    /** 완전한 12개월 상세와 고정 검증 JSON을 합성한다.
     * Args: id 제출,org 조직,request 요청,seq 회차,status 상태,revenue/cost 기본금액,overrides 월별교체금액.
     * Returns: 없음. Raises: DB·JSON오류. Side Effects: H2 자료만 생성하며 실제 원본·파서는 사용하지 않는다.
     */
    private void submission(long id, long org, long request, int seq, String status, String revenue, String cost, Map<Integer, String[]> overrides) {
        var requestRow = jdbc.sql("SELECT * FROM submission_request WHERE id=?").param(request).query().singleRow(); boolean annual = "ANNUAL_PLAN".equals(requestRow.get("type")); int actualMonth = annual ? 0 : ((Number) requestRow.get("month")).intValue();
        String source = "source/" + id, parsed = "parsed/" + id, validated = "validated/" + id, fingerprint = hash((revenue + ':' + cost + ':' + json(overrides)).getBytes(StandardCharsets.UTF_8));
        for (String key : List.of(source, parsed)) jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES (?,'application/json',?)").params(key, "{}".getBytes(StandardCharsets.UTF_8)).update();
        jdbc.sql("INSERT INTO submission(id,request_id,organization_id,seq,status,original_filename,source_object_key,source_sha256,uploaded_by,uploaded_at) VALUES (?,?,?,?,?,'test.xlsx',?,?,1,CURRENT_TIMESTAMP)").params(id, request, org, seq, status, source, fingerprint).update();
        jdbc.sql("INSERT INTO parsing_job(submission_id,status,engine,attempt_no,result_object_key) VALUES (?,'SUCCEEDED','test',1,?)").params(id, parsed).update();
        for (int report = 1; report <= 2; report++) {
            long line = id * 10 + report - 1; jdbc.sql("INSERT INTO financial_line(id,submission_id,report_item_id,detail_id,source_sheet,source_row) VALUES (?,?,?,?, 'sheet',?)").params(line, id, report, "detail-" + org + '-' + report, report).update();
            for (int month = 1; month <= 12; month++) {
                var amounts = overrides.getOrDefault(month, new String[]{revenue, cost});
                jdbc.sql("INSERT INTO financial_value(line_id,year,month,scenario,amount,source_record_id,source_cell) VALUES (?,2031,?,?,?,?, 'A1')")
                        .params(line, month, annual ? "PLAN" : month <= actualMonth ? "ACTUAL" : "ESTIMATE", new BigDecimal(amounts[report - 1]), line + ":" + month).update();
            }
        }
        var reports = new ArrayList<Map<String, Object>>();
        for (int month = 1; month <= 12; month++) for (int report = 1; report <= 5; report++) {
            var amounts = overrides.getOrDefault(month, new String[]{revenue, cost}); BigDecimal r = new BigDecimal(amounts[0]), c = new BigDecimal(amounts[1]);
            reports.add(object("reportItemId", Integer.toString(report), "reportItemName", "항목" + report, "kind", report == 1 ? "REVENUE" : report == 2 ? "COST" : "DERIVED", "year", 2031,
                    "month", month, "scenario", annual ? "PLAN" : month <= actualMonth ? "ACTUAL" : "ESTIMATE", "organizationId", Long.toString(org), "valueStatus", "AVAILABLE",
                    "amount", (report == 1 || report == 3 ? r : report == 2 || report == 4 ? c : r.subtract(c)).toPlainString()));
        }
        String policy = annual ? "annual-plan/1.0" : "monthly-actual/1.0";
        var snapshot = object("policyVersion", policy, "previousSubmissionId", null, "comparisonContext", object("planSubmissionId", annual ? null : Long.toString(org)), "fingerprint", fingerprint,
                "complete", true, "diagnostics", List.of(), "reportValues", reports, "summary", object("complete", true, "lineCount", 2, "valueCount", 24));
        jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES (?,'application/json',?)").params(validated, json(snapshot).getBytes(StandardCharsets.UTF_8)).update();
        jdbc.sql("INSERT INTO submission_validation(submission_id,attempt_no,source_sha256,parser_result_object_key,policy_version,validation_result_object_key,validated_by,validated_at) VALUES (?,1,?,?,?,?,1,CURRENT_TIMESTAMP)").params(id, fingerprint, parsed, policy, validated).update();
    }
    /** 합성 JSON을 만든다. Args: value 값. Returns: JSON. Raises: 직렬화오류. Side Effects: 없음. */
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); } }
}
