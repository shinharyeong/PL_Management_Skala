package io.skala.backend.master;

import static io.skala.backend.submission.ApiData.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.security.Actor;
import io.skala.backend.security.Organization;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

/** 합성 메모리 DB에서 최초 발행·부분 교체·근거·잠금·조회 계약을 검증한다. */
class AnnualMasterTests {
    private EmbeddedDatabase database;
    private JdbcClient jdbc;
    private ObjectMapper mapper;
    private AnnualMasterService service;
    private TransactionTemplate transactions;
    private Actor planner;

    /** 마스터까지 포함한 스키마와 최소 공통 보고 항목을 준비한다.
     * Args: 없음. Returns: 없음. Raises: DB 오류. Side Effects: 테스트 전용 메모리 DB 생성, 외부 파일·원본 접근 없음.
     */
    @BeforeEach
    void prepare() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("master_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=YEAR,MONTH;LOCK_TIMEOUT=10000").build();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__submission_parser_foundation.sql"),
                new ClassPathResource("db/migration/V2__validated_financial_submissions.sql"),
                new ClassPathResource("db/migration/V3__annual_plan_masters.sql")).execute(database);
        jdbc = JdbcClient.create(database); mapper = new ObjectMapper(); service = new AnnualMasterService(jdbc, mapper);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        jdbc.sql("INSERT INTO app_user(id,email,name,role) VALUES (1,'planner@example.test','기획','PLANNER')").update();
        jdbc.sql("INSERT INTO annual_plan(id,year,status) VALUES (1,2031,'COLLECTING')").update();
        jdbc.sql("INSERT INTO submission_request(id,annual_plan_id,type,status,requested_by,requested_at,period_key) VALUES (1,1,'ANNUAL_PLAN','OPEN',1,CURRENT_TIMESTAMP,'2031-PLAN')").update();
        jdbc.sql("""
                INSERT INTO report_item(id,code,name,kind,is_leaf) VALUES
                (1,'R','매출','REVENUE',TRUE),(2,'C','비용','COST',TRUE),
                (3,'REVENUE_TOTAL','매출 합계','DERIVED',FALSE),(4,'COST_TOTAL','비용 합계','DERIVED',FALSE),(5,'OP','영업이익','DERIVED',FALSE)
                """).update();
        planner = new Actor("1", "planner@example.test", "기획", "PLANNER", null);
    }

    /** 테스트 메모리 자원을 해제한다.
     * Args: 없음. Returns: 없음. Raises: DB 종료 오류. Side Effects: 메모리 DB 종료, 파일 삭제 없음.
     */
    @AfterEach
    void close() { if (database != null) database.shutdown(); }

    /** 17개 중 16개 승인은 대기하고 마지막 승인은 12개월 전체와 정확한 큰 금액을 발행한다.
     * Args: 없음. Returns: 없음. Raises: DB·검증 오류. Side Effects: 합성 메모리 행 생성·승인·마스터 발행.
     */
    @Test
    void waitsForEveryBusinessOrganizationThenPublishesExactAnnualTotals() {
        for (int org = 1; org <= 17; org++) { organization(org); submission(org, org, 1, org == 17 ? "PENDING" : "APPROVED", "9007199254740.123456", "0.000001"); }
        assertThat(publish("16").get("action")).isEqualTo("WAITING_INITIAL");
        assertThat(count()).isZero();
        var waiting = service.status(planner, "1", Map.of());
        assertThat(waiting.get("approvedOrganizationCount")).isEqualTo(16L);
        assertThat((List<?>) waiting.get("missingOrganizations")).hasSize(1);
        var effect = approve("17"); assertThat(effect.get("action")).isEqualTo("CREATED");
        var detail = service.detail(planner, effect.get("masterId").toString(), Map.of());
        assertThat((List<?>) detail.get("sources")).hasSize(17); assertThat((List<?>) detail.get("months")).hasSize(12);
        assertOrganizationBreakdown(detail);
        assertThat(mapper.valueToTree(detail).path("organizations").get(0).path("totals").path("operatingProfitShare").asText()).isEqualTo("5.88");
        var totals = (Map<?, ?>) detail.get("totals");
        assertThat(new BigDecimal(totals.get("revenue").toString())).isEqualByComparingTo(new BigDecimal("9007199254740.123456").multiply(BigDecimal.valueOf(204)));
        assertThat(new BigDecimal(totals.get("cost").toString())).isEqualByComparingTo("0.000204");
        assertThat(jdbc.sql("SELECT status FROM annual_plan WHERE id=1").query(String.class).single()).isEqualTo("COLLECTING");
        assertThat(jdbc.sql("SELECT contingency_amount FROM annual_plan WHERE id=1").query().singleRow().get("contingency_amount")).isNull();
        assertThat(service.status(planner, "1", Map.of()).get("publicationState")).isEqualTo("PUBLISHED");
    }

    /** 동일 의미의 후속 승인과 재호출은 새 버전 없이 기존 고정 참조를 유지한다.
     * Args: 없음. Returns: 없음. Raises: DB·검증 오류. Side Effects: 합성 제출·마스터 생성.
     */
    @Test
    void semanticNoOpAndRetryDoNotPublishAnotherVersion() {
        organization(1); submission(1, 1, 1, "APPROVED", "10", "3"); var initial = publish("1");
        submission(2, 1, 2, "APPROVED", "10.000000", "3.000000");
        assertThat(publish("2").get("action")).isEqualTo("UNCHANGED");
        assertThat(publish("2").get("masterId")).isEqualTo(initial.get("masterId"));
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT submission_id FROM annual_master_source").query(Long.class).single()).isEqualTo(1);
    }

    /** 한 조직의 갱신 시 다른 조직의 최신 반려본 대신 종전 승인 참조를 보존한다.
     * Args: 없음. Returns: 없음. Raises: DB·검증 오류. Side Effects: 합성 후속 제출과 새 마스터 생성.
     */
    @Test
    void replacementKeepsOtherOrganizationsFrozenAndOldMasterImmutable() {
        organization(1); organization(2); submission(1, 1, 1, "APPROVED", "10", "3"); submission(2, 2, 1, "APPROVED", "20", "4");
        String firstId = publish("2").get("masterId").toString(); var original = service.detail(planner, firstId, Map.of());
        submission(3, 2, 2, "REJECTED", "999", "9"); submission(4, 1, 2, "APPROVED", "30", "5");
        var update = publish("4"); assertThat(update.get("action")).isEqualTo("UPDATED"); assertThat(update.get("previousMasterId")).isEqualTo(firstId);
        var changed = service.detail(planner, update.get("masterId").toString(), Map.of());
        assertThat(new BigDecimal(((Map<?, ?>) changed.get("totals")).get("revenue").toString())).isEqualByComparingTo("600");
        assertThat(jdbc.sql("SELECT submission_id FROM annual_master_source WHERE master_id=? AND organization_id=2").param(id(update.get("masterId").toString())).query(Long.class).single()).isEqualTo(2);
        assertThat(service.detail(planner, firstId, Map.of())).isEqualTo(original);
        assertThat(count()).isEqualTo(2);
    }

    /** 0 분모의 이익률·기여율은 null이며 적자 조직의 부호와 소수 둘째 자리 반올림을 보존한다.
     * Args: 없음. Returns: 없음. Raises: DB·검증 오류. Side Effects: 합성 마스터 생성 후 읽기.
     */
    @Test
    void marginsAndSharesPreserveNegativeAmountsAndHalfUpRounding() {
        organization(1); organization(2);
        submission(1, 1, 1, "APPROVED", "8", "7.1236"); submission(2, 2, 1, "APPROVED", "-8", "-6");
        var detail = service.detail(planner, publish("2").get("masterId").toString(), Map.of()); assertOrganizationBreakdown(detail);
        var json = mapper.valueToTree(detail);
        assertThat(json.path("totals").path("operatingMargin").isNull()).isTrue();
        assertThat(json.path("totals").has("operatingProfitShare")).isFalse();
        assertThat(json.path("months").get(0).path("operatingMargin").isNull()).isTrue();
        var first = json.path("organizations").get(0); var second = json.path("organizations").get(1);
        assertThat(first.path("totals").path("operatingMargin").asText()).isEqualTo("10.96");
        assertThat(first.path("totals").path("operatingProfitShare").asText()).isEqualTo("-78.00");
        assertThat(second.path("totals").path("operatingMargin").asText()).isEqualTo("25.00");
        assertThat(second.path("totals").path("operatingProfitShare").asText()).isEqualTo("178.00");
        assertThat(first.path("months").get(0).path("operatingMargin").asText()).isEqualTo("10.96");
        assertThat(second.path("months").get(0).path("operatingProfitShare").asText()).isEqualTo("178.00");
    }

    /** 전사 영업이익이 0이면 흑자·적자·0원 조직 모두 기여율을 만들지 않는다.
     * Args: 없음. Returns: 없음. Raises: DB·검증 오류. Side Effects: 합성 마스터 생성 후 읽기.
     */
    @Test
    void zeroCompanyProfitLeavesEveryOrganizationShareNull() {
        for (int org = 1; org <= 3; org++) organization(org);
        submission(1, 1, 1, "APPROVED", "10", "7"); submission(2, 2, 1, "APPROVED", "10", "13"); submission(3, 3, 1, "APPROVED", "0", "0");
        var detail = service.detail(planner, publish("3").get("masterId").toString(), Map.of()); assertOrganizationBreakdown(detail);
        var json = mapper.valueToTree(detail);
        assertThat(json.path("totals").path("operatingMargin").asText()).isEqualTo("0.00");
        for (var organization : json.path("organizations")) {
            assertThat(organization.path("totals").path("operatingProfitShare").isNull()).isTrue();
            for (var month : organization.path("months")) assertThat(month.path("operatingProfitShare").isNull()).isTrue();
        }
        assertThat(json.path("organizations").get(1).path("totals").path("operatingMargin").asText()).isEqualTo("-30.00");
        assertThat(json.path("organizations").get(2).path("totals").path("operatingMargin").isNull()).isTrue();
    }

    /** 최신 완료 제출이 반려되었으면 과거 승인만으로 최초 발행하지 않는다.
     * Args: 없음. Returns: 없음. Raises: DB·검증 오류. Side Effects: 합성 제출 생성, 마스터는 생성하지 않음.
     */
    @Test
    void latestRejectedSubmissionBlocksInitialPublication() {
        organization(1); organization(2); submission(1, 1, 1, "APPROVED", "10", "3"); submission(2, 1, 2, "REJECTED", "10", "3");
        submission(3, 2, 1, "APPROVED", "20", "4");
        assertThat(publish("3").get("action")).isEqualTo("WAITING_INITIAL"); assertThat(count()).isZero();
    }

    /** 제외 대상은 최초 발행을 막지 않으며 발행 이후 대상 변경은 충돌시킨다.
     * Args: 없음. Returns: 없음. Raises: DB·검증 오류. Side Effects: 합성 대상 플래그·마스터 생성.
     */
    @Test
    void excludedTargetsAreSkippedAndLaterMembershipChangesConflict() {
        organization(1); organization(2); jdbc.sql("UPDATE request_target SET excluded=TRUE WHERE organization_id=2").update();
        submission(1, 1, 1, "APPROVED", "10", "3"); assertThat(publish("1").get("requiredOrganizationCount")).isEqualTo(1);
        jdbc.sql("UPDATE request_target SET excluded=FALSE WHERE organization_id=2").update();
        assertThatThrownBy(() -> publish("1")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("MASTER_TARGET_MISMATCH");
        assertThat(count()).isEqualTo(1);
    }

    /** 금액 손상으로 발행이 실패하면 같은 트랜잭션의 마지막 승인까지 롤백한다.
     * Args: 없음. Returns: 없음. Raises: DB·검증 오류. Side Effects: 합성 적재값 손상, 실패한 승인·발행 롤백.
     */
    @Test
    void inconsistentFrozenAmountsRollBackApprovalAndPublication() {
        organization(1); submission(1, 1, 1, "PENDING", "10", "3");
        jdbc.sql("UPDATE financial_value SET amount=11 WHERE line_id=10 AND month=1").update();
        assertThatThrownBy(() -> approve("1")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("MASTER_SOURCE_INVALID");
        assertThat(jdbc.sql("SELECT status FROM submission WHERE id=1").query(String.class).single()).isEqualTo("PENDING"); assertThat(count()).isZero();
    }

    /** 저장된 출처 행이 빠진 마스터를 정상 부분 합계로 공개하지 않는다.
     * Args: 없음. Returns: 없음. Raises: DB·검증 오류. Side Effects: 합성 마스터 출처 한 행 삭제 후 읽기 차단 검사.
     */
    @Test
    void missingFrozenSourceBlocksDetailAndReportValues() {
        organization(1); organization(2); submission(1, 1, 1, "APPROVED", "10", "3"); submission(2, 2, 1, "APPROVED", "20", "4");
        String masterId = publish("2").get("masterId").toString();
        jdbc.sql("DELETE FROM annual_master_source WHERE master_id=? AND organization_id=2").param(id(masterId)).update();
        assertThatThrownBy(() -> service.detail(planner, masterId, Map.of())).isInstanceOf(ApiException.class).extracting("code").isEqualTo("MASTER_SOURCE_INVALID");
        assertThatThrownBy(() -> service.reportValues(planner, masterId, Map.of())).isInstanceOf(ApiException.class).extracting("code").isEqualTo("MASTER_SOURCE_INVALID");
    }

    /** 정상 승인 근거라도 발행 이후 임의 교체한 다른 금액 조합은 공개하지 않는다.
     * Args: 없음. Returns: 없음. Raises: DB·검증 오류. Side Effects: 합성 출처 참조 변경 후 읽기 차단 검사.
     */
    @Test
    void replacedFrozenSourceWithDifferentValidContentBlocksReads() {
        organization(1); submission(1, 1, 1, "APPROVED", "10", "3"); String masterId = publish("1").get("masterId").toString();
        submission(2, 1, 2, "APPROVED", "20", "4");
        jdbc.sql("UPDATE annual_master_source SET submission_id=2 WHERE master_id=? AND organization_id=1").param(id(masterId)).update();
        assertThatThrownBy(() -> service.detail(planner, masterId, Map.of())).isInstanceOf(ApiException.class).extracting("code").isEqualTo("MASTER_SOURCE_INVALID");
        assertThatThrownBy(() -> service.reportValues(planner, masterId, Map.of())).isInstanceOf(ApiException.class).extracting("code").isEqualTo("MASTER_SOURCE_INVALID");
        assertThatThrownBy(() -> publish("2")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("MASTER_SOURCE_INVALID");
        assertThat(count()).isEqualTo(1);
    }

    /** 동시 마지막 승인들은 연간 계획 잠금으로 직렬화되어 최초 버전 하나만 만든다.
     * Args: 없음. Returns: 없음. Raises: 스레드·DB·검증 오류. Side Effects: 두 메모리 트랜잭션에서 합성 승인·마스터 생성.
     */
    @Test
    void concurrentFinalApprovalsCreateOneVersion() throws Exception {
        organization(1); organization(2); submission(1, 1, 1, "PENDING", "10", "3"); submission(2, 2, 1, "PENDING", "20", "4");
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> simultaneousApproval("1", ready, start));
            var second = executor.submit(() -> simultaneousApproval("2", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(Set.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("WAITING_INITIAL", "CREATED");
        }
        assertThat(count()).isEqualTo(1); assertThat(jdbc.sql("SELECT COUNT(*) FROM annual_master_source").query(Long.class).single()).isEqualTo(2);
    }

    /** 비기획 권한·잘못된 필터·다른 조직 커서를 거부하고 파생 보고값도 제공한다.
     * Args: 없음. Returns: 없음. Raises: DB·검증 오류. Side Effects: 합성 마스터 생성 후 읽기.
     */
    @Test
    void readContractsValidateRoleFiltersAndCursorScope() {
        organization(1); submission(1, 1, 1, "APPROVED", "10", "3"); String masterId = publish("1").get("masterId").toString();
        var manager = new Actor("2", "manager@example.test", "담당", "ORG_MANAGER", new Organization("1", "B1", "사업1"));
        assertThatThrownBy(() -> service.detail(manager, masterId, Map.of())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.reportValues(planner, masterId, Map.of("month", "13"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.reportValues(planner, masterId, Map.of("organizationId", "999"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list(planner, Map.of("requestId", "1", "unknown", "x"))).isInstanceOf(ApiException.class);
        var first = service.reportValues(planner, masterId, Map.of("limit", "1"));
        assertThatThrownBy(() -> service.reportValues(planner, masterId, Map.of("organizationId", "1", "cursor", first.get("nextCursor").toString()))).isInstanceOf(ApiException.class);
        var items = (List<Map<String, Object>>) service.reportValues(planner, masterId, Map.of("month", "1")).get("items");
        assertThat(items).hasSize(5); assertThat(items).allMatch(row -> row.get("organizationId") == null);
        assertThat(items.stream().map(row -> row.get("reportItemCode"))).contains("REVENUE_TOTAL", "COST_TOTAL", "OP");
    }

    /** 명시적 승인 트랜잭션 없이 직접 발행하면 저장 전에 거부한다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류. Side Effects: 없음.
     */
    @Test
    void publicationRequiresApprovalTransaction() {
        assertThatThrownBy(() -> service.publishAfterApproval(planner, "1")).isInstanceOf(IllegalStateException.class);
    }

    /** 두 승인 스레드를 같은 출발점에서 실행한다.
     * Args: submissionId 제출, ready 준비 래치, start 시작 래치. Returns: 발행 효과 이름.
     * Raises: InterruptedException 대기 중단. Side Effects: 메모리 DB 승인 트랜잭션.
     */
    private String simultaneousApproval(String submissionId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("시작 대기 시간 초과");
        return approve(submissionId).get("action").toString();
    }

    /** 실제 명령 잠금 순서처럼 연간 계획을 잠근 뒤 승인하고 발행한다.
     * Args: submissionId 제출. Returns: 발행 효과. Raises: DB·업무 오류. Side Effects: 메모리 DB 승인과 발행을 원자적으로 반영.
     */
    private Map<String, Object> approve(String submissionId) {
        return transactions.execute(status -> {
            jdbc.sql("SELECT id FROM annual_plan WHERE id=1 FOR UPDATE").query(Long.class).single();
            jdbc.sql("UPDATE submission SET status='APPROVED' WHERE id=?").param(id(submissionId)).update();
            return service.publishAfterApproval(planner, submissionId);
        });
    }

    /** 이미 승인한 제출의 발행을 트랜잭션으로 감싼다.
     * Args: submissionId 제출. Returns: 발행 효과. Raises: DB·업무 오류. Side Effects: 메모리 DB 발행.
     */
    private Map<String, Object> publish(String submissionId) { return transactions.execute(status -> service.publishAfterApproval(planner, submissionId)); }

    /** 현재 발행 수를 읽는다.
     * Args: 없음. Returns: 발행 수. Raises: DB 오류. Side Effects: 메모리 DB 읽기.
     */
    private long count() { return jdbc.sql("SELECT COUNT(*) FROM annual_master").query(Long.class).single(); }

    /** 고정 출처와 조직 상세가 일치하고 조직별 12개월·연간 금액 합계가 전사와 같은지 대조한다.
     * Args: detail 마스터 상세. Returns: 없음. Raises: 출처·조직·월·금액 합계 검증 실패.
     * Side Effects: 메모리 JSON 대조만 수행하며 비율을 합산하거나 DB를 조회하지 않는다.
     */
    private void assertOrganizationBreakdown(Map<String, Object> detail) {
        var json = mapper.valueToTree(detail); var sources = new HashMap<String, JsonNode>();
        for (var source : json.path("sources")) sources.put(source.path("organizationId").asText(), source);
        assertThat(json.path("organizations").size()).isEqualTo(sources.size());
        var sums = new HashMap<String, BigDecimal>(); var seenOrganizations = new HashSet<String>();
        for (var organization : json.path("organizations")) {
            String organizationId = organization.path("organizationId").asText(); assertThat(seenOrganizations.add(organizationId)).isTrue();
            var source = sources.get(organizationId); assertThat(source).isNotNull();
            for (String field : List.of("organizationId", "organizationCode", "organizationName", "submissionId", "versionLabel")) assertThat(organization.path(field)).isEqualTo(source.path(field));
            assertThat(organization.path("months").size()).isEqualTo(12); var seenMonths = new HashSet<Integer>();
            var annual = new HashMap<String, BigDecimal>();
            for (var month : organization.path("months")) {
                int number = month.path("month").asInt(); assertThat(number).isBetween(1, 12); assertThat(seenMonths.add(number)).isTrue();
                for (String field : List.of("revenue", "cost", "operatingProfit")) {
                    assertThat(month.path(field).isTextual()).isTrue(); BigDecimal amount = new BigDecimal(month.path(field).asText());
                    annual.merge(field, amount, BigDecimal::add); sums.merge(number + ":" + field, amount, BigDecimal::add);
                }
            }
            for (String field : List.of("revenue", "cost", "operatingProfit")) {
                assertThat(organization.path("totals").path(field).isTextual()).isTrue(); BigDecimal amount = new BigDecimal(organization.path("totals").path(field).asText());
                assertThat(amount).isEqualByComparingTo(annual.get(field)); sums.merge("annual:" + field, amount, BigDecimal::add);
            }
        }
        assertThat(seenOrganizations).containsExactlyInAnyOrderElementsOf(sources.keySet());
        for (String field : List.of("revenue", "cost", "operatingProfit")) {
            assertThat(new BigDecimal(json.path("totals").path(field).asText())).isEqualByComparingTo(sums.get("annual:" + field));
            for (var month : json.path("months")) assertThat(new BigDecimal(month.path(field).asText())).isEqualByComparingTo(sums.get(month.path("month").asInt() + ":" + field));
        }
    }

    /** 합성 사업조직과 고정 제출 대상을 추가한다.
     * Args: id 조직 ID. Returns: 없음. Raises: DB 오류. Side Effects: 메모리 DB 행 추가.
     */
    private void organization(long id) {
        jdbc.sql("INSERT INTO organization(id,code,name) VALUES (?,?,?)").params(id, "B" + id, "사업" + id).update();
        jdbc.sql("INSERT INTO request_target(request_id,organization_id) VALUES (1,?)").param(id).update();
    }

    /** 12개월 매출·비용 상세와 일치하는 불변 검증 근거를 합성한다.
     * Args: submissionId 제출, organizationId 조직, seq 회차, status 상태, revenue 매출, cost 비용.
     * Returns: 없음. Raises: DB·JSON 오류. Side Effects: 메모리 DB 합성 자료 생성, 실제 파일·파서 호출 없음.
     */
    private void submission(long submissionId, long organizationId, int seq, String status, String revenue, String cost) {
        BigDecimal r = new BigDecimal(revenue), c = new BigDecimal(cost);
        String sourceKey = "source/" + submissionId, parserKey = "parser/" + submissionId, validationKey = "validation/" + submissionId;
        String fingerprint = hash((r.stripTrailingZeros().toPlainString() + ":" + c.stripTrailingZeros().toPlainString()).getBytes(StandardCharsets.UTF_8));
        for (String key : List.of(sourceKey, parserKey)) jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES (?,'application/json',?)")
                .params(key, "{}".getBytes(StandardCharsets.UTF_8)).update();
        jdbc.sql("""
                INSERT INTO submission(id,request_id,organization_id,seq,status,original_filename,source_object_key,source_sha256,uploaded_by,uploaded_at)
                VALUES (?,1,?,?,?,'test.xlsx',?,?,1,CURRENT_TIMESTAMP)
                """).params(submissionId, organizationId, seq, status, sourceKey, fingerprint).update();
        jdbc.sql("INSERT INTO parsing_job(submission_id,status,engine,attempt_no,result_object_key) VALUES (?,'SUCCEEDED','test',1,?)").params(submissionId, parserKey).update();
        for (int reportId = 1; reportId <= 2; reportId++) {
            long lineId = submissionId * 10 + reportId - 1;
            jdbc.sql("INSERT INTO financial_line(id,submission_id,report_item_id,source_sheet,source_row) VALUES (?,?,?,'Sheet1',?)").params(lineId, submissionId, reportId, reportId).update();
            for (int month = 1; month <= 12; month++) jdbc.sql("INSERT INTO financial_value(line_id,year,month,scenario,amount,source_record_id,source_cell) VALUES (?,2031,?,'PLAN',?,?,'A1')")
                    .params(lineId, month, reportId == 1 ? r : c, lineId + ":" + month).update();
        }
        var reports = new ArrayList<Map<String, Object>>();
        for (int reportId = 1; reportId <= 5; reportId++) for (int month = 1; month <= 12; month++) {
            BigDecimal amount = reportId == 1 || reportId == 3 ? r : reportId == 2 || reportId == 4 ? c : r.subtract(c);
            reports.add(object("reportItemId", Integer.toString(reportId), "reportItemName", "항목" + reportId, "kind", reportId == 1 ? "REVENUE" : reportId == 2 ? "COST" : "DERIVED",
                    "year", 2031, "month", month, "scenario", "PLAN", "organizationId", Long.toString(organizationId), "amount", amount.toPlainString(), "valueStatus", "AVAILABLE"));
        }
        var snapshot = object("policyVersion", "annual-plan/1.0", "previousSubmissionId", null, "complete", true, "fingerprint", fingerprint,
                "diagnostics", List.of(), "reportValues", reports, "summary", object("complete", true, "lineCount", 2, "valueCount", 24));
        byte[] payload;
        try { payload = mapper.writeValueAsBytes(snapshot); } catch (Exception exception) { throw new IllegalStateException(exception); }
        jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES (?,'application/json',?)").params(validationKey, payload).update();
        jdbc.sql("""
                INSERT INTO submission_validation(submission_id,attempt_no,source_sha256,parser_result_object_key,policy_version,validation_result_object_key,validated_by,validated_at)
                VALUES (?,1,?,?,'annual-plan/1.0',?,1,CURRENT_TIMESTAMP)
                """).params(submissionId, fingerprint, parserKey, validationKey).update();
    }
}
