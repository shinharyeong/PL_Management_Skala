package io.skala.backend.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.Actor;
import io.skala.backend.review.ReviewComparisonService;
import io.skala.backend.review.ReviewService;
import io.skala.backend.security.Organization;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** 실제 서비스·멱등 트랜잭션으로 재제출 제한과 보존된 제출 이력 조회를 확인한다. */
class SubmissionRuleTests {

    private static final OffsetDateTime UPLOADED_AT = OffsetDateTime.parse("2030-12-01T00:00:00Z");
    private static final OffsetDateTime SUBMITTED_AT = OffsetDateTime.parse("2030-12-02T00:00:00+09:00");
    private static final OffsetDateTime REVIEWED_AT = OffsetDateTime.parse("2030-12-03T09:30:00+09:00");
    private EmbeddedDatabase database;
    private JdbcClient jdbc;
    private ObjectMapper mapper;
    private SubmissionService service;
    private Actor planner;
    private Actor manager;
    private String firstRequestId;
    private String secondRequestId;

    /**
     * 실제 V1 스키마와 조직·사용자·연간 요청 두 개를 메모리에 준비한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: DB·JSON fixture 준비가 실패하면 Exception.
     * Side Effects: 메모리 H2만 생성·수정하며 파서 워커·외부 프로세스·파일을 생성하지 않는다.
     */
    @BeforeEach
    void setUp() throws Exception {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("submission_rules_" + UUID.randomUUID() + ";MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH")
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1__submission_parser_foundation.sql"), new ClassPathResource(
                "db/migration/V2__validated_financial_submissions.sql"), new ClassPathResource(
                "db/migration/V3__annual_plan_masters.sql"), new ClassPathResource(
                "db/migration/V4__monthly_masters.sql"), new ClassPathResource(
                "db/migration/V5__submission_reviews_and_gap_thresholds.sql"), new ClassPathResource("db/migration/V6__prd_review_workflow.sql")).execute(database);
        jdbc = JdbcClient.create(database);
        jdbc.sql("""
                INSERT INTO organization (id, code, name, active)
                VALUES (1, 'D100', '테스트 조직', TRUE)
                """).update();
        jdbc.sql("""
                INSERT INTO app_user (id, email, name, role, organization_id, active)
                VALUES (1, 'planner@example.test', '검토 담당자', 'PLANNER', NULL, TRUE),
                       (2, 'uploader@example.test', '업로드 담당자', 'ORG_MANAGER', 1, TRUE),
                       (3, 'submitter@example.test', '최종 제출자', 'ORG_MANAGER', 1, TRUE)
                """).update();
        planner = new Actor("1", "planner@example.test", "검토 담당자", "PLANNER", null);
        manager = new Actor("2", "uploader@example.test", "업로드 담당자", "ORG_MANAGER",
                new Organization("1", "D100", "테스트 조직"));
        mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        CommandExecutor commands = new CommandExecutor(new JdbcTemplate(database),
                new DataSourceTransactionManager(database));
        var comparisons = new ReviewComparisonService(jdbc, mapper);
        var reviews = new ReviewService(jdbc, commands, comparisons);
        service = new SubmissionService(jdbc, mapper, commands,
                new SubmissionValidationService(jdbc, mapper, new FinancialValidationRules(mapper), comparisons, reviews));
        firstRequestId = createAnnualRequest(2030);
        secondRequestId = createAnnualRequest(2031);
    }

    /**
     * 각 테스트가 사용한 메모리 DB를 종료한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: DB 종료 실패는 런타임 예외.
     * Side Effects: 메모리 DB 자원을 해제하며 파일을 삭제하거나 만들지 않는다.
     */
    @AfterEach
    void tearDown() {
        if (database != null) {
            database.shutdown();
        }
    }

    /**
     * 다른 요청의 최신 제출 완료본이 반려돼도 새 기간 작업은 허용한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: SQL 오류는 런타임 예외, 제한·롤백이 잘못되면 AssertionError.
     * Side Effects: 메모리 DB에 과거 반려본과 다른 기간 초안을 저장한다.
     */
    @Test
    void allowsNewPeriodWhileOtherPeriodCorrectionRemainsPending() {
        seedSubmission(firstRequestId, 2030, 1, "APPROVED");
        seedSubmission(firstRequestId, 2030, 2, "REJECTED");

        assertThat(uploadAnnual(secondRequestId,2031).status()).isEqualTo(202);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submission WHERE request_id=?").param(Long.parseLong(secondRequestId)).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submission WHERE request_id=? AND status='REJECTED'").param(Long.parseLong(firstRequestId)).query(Long.class).single()).isEqualTo(1L);
    }

    /**
     * 과거 반려가 있어도 최신 회차가 승인되면 다른 기간 업로드를 허용한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: 서비스·JSON 처리 실패는 Exception, 허용 결과가 다르면 AssertionError.
     * Side Effects: 메모리 DB의 입력 순서를 회차와 반대로 만들어 최대 ID 의존도 검출한다.
     */
    @Test
    void allowsUploadAfterNewerApprovedRoundEvenWithOlderRejectedRow() throws Exception {
        seedSubmission(firstRequestId, 2030, 2, "APPROVED");
        seedSubmission(firstRequestId, 2030, 1, "REJECTED");

        CommandExecutor.Result result = uploadAnnual(secondRequestId, 2031);
        JsonNode body = mapper.readTree(result.body());
        assertThat(result.status()).isEqualTo(202);
        assertThat(body.path("status").asText()).isEqualTo("DRAFT");
        assertThat(body.path("requestId").asText()).isEqualTo(secondRequestId);
        assertThat(body.path("seq").isNull()).isTrue();
        assertThat(body.path("versionLabel").isNull()).isTrue();
        assertThat(body.path("parsing").path("status").asText()).isEqualTo("QUEUED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submission WHERE request_id=?")
                .param(Long.parseLong(firstRequestId)).query(Long.class).single()).isEqualTo(2L);
    }

    /**
     * 반려된 바로 그 요청에는 새 전체 파일 초안을 올릴 수 있는지 확인한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: 서비스·JSON 처리 실패는 Exception, 기존 이력 손상 또는 거부 시 AssertionError.
     * Side Effects: 메모리 DB에 새 DRAFT·원본·QUEUED 작업만 추가한다.
     */
    @Test
    void allowsResubmissionToTheRejectedRequestWithoutOverwritingHistory() throws Exception {
        long rejectedId = seedSubmission(firstRequestId, 2030, 1, "REJECTED");

        CommandExecutor.Result result = uploadAnnual(firstRequestId, 2030);
        JsonNode body = mapper.readTree(result.body());
        assertThat(result.status()).isEqualTo(202);
        assertThat(body.path("status").asText()).isEqualTo("DRAFT");
        assertThat(body.path("id").asText()).isNotEqualTo(Long.toString(rejectedId));
        assertThat(jdbc.sql("SELECT status FROM submission WHERE id=?")
                .param(rejectedId).query(String.class).single()).isEqualTo("REJECTED");
        assertThat(jdbc.sql("SELECT seq FROM submission WHERE id=?")
                .param(rejectedId).query(Integer.class).single()).isEqualTo(1);
    }

    /**
     * 승인본 목록이 저장된 서로 다른 행위자·UTC 시각·연간 버전을 반환하는지 확인한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: DB 조회 실패는 런타임 예외, 이력 DTO가 다르면 AssertionError.
     * Side Effects: 메모리 승인 fixture를 저장한 뒤 목록 API 서비스로 읽는다.
     */
    @Test
    void preservesApprovedSubmissionActorsTimestampsAndVersionLabel() {
        long approvedId = seedSubmission(firstRequestId, 2030, 3, "APPROVED");

        JsonNode page = mapper.valueToTree(service.submissions(planner,
                Map.of("requestId", firstRequestId, "status", "APPROVED")));
        assertThat(page.path("items").size()).isEqualTo(1);
        JsonNode summary = page.path("items").get(0);
        assertThat(summary.path("id").asText()).isEqualTo(Long.toString(approvedId));
        assertThat(summary.path("status").asText()).isEqualTo("APPROVED");
        assertThat(summary.path("seq").asInt()).isEqualTo(3);
        assertThat(summary.path("versionLabel").asText()).isEqualTo("2030-PLAN-03");
        assertThat(summary.path("uploadedBy").path("id").asText()).isEqualTo("2");
        assertThat(summary.path("uploadedBy").path("name").asText()).isEqualTo("업로드 담당자");
        assertThat(summary.path("submittedBy").path("id").asText()).isEqualTo("3");
        assertThat(summary.path("submittedBy").path("name").asText()).isEqualTo("최종 제출자");
        assertThat(summary.path("reviewedBy").path("id").asText()).isEqualTo("1");
        assertThat(summary.path("reviewedBy").path("name").asText()).isEqualTo("검토 담당자");
        assertThat(summary.path("uploadedAt").asText()).isEqualTo("2030-12-01T00:00:00Z");
        assertThat(summary.path("submittedAt").asText()).isEqualTo("2030-12-01T15:00:00Z");
        assertThat(summary.path("reviewedAt").asText()).isEqualTo("2030-12-03T00:30:00Z");
    }

    /**
     * 서비스 명령으로 가상 연도의 전체 조직 연간 요청을 만든다.
     *
     * <p>Args: year는 확정 계획이 필요 없는 연간 요청 연도다.
     * Returns: 생성된 요청의 십진 문자열 ID.
     * Raises: 서비스 또는 JSON 해석 실패는 Exception.
     * Side Effects: 메모리 계획·요청·대상·멱등 기록을 생성한다.
     */
    private String createAnnualRequest(int year) throws Exception {
        CommandExecutor.Result result = service.createRequest(planner, UUID.randomUUID().toString(),
                new SubmissionService.RequestInput("ANNUAL_PLAN", year, null, "테스트 연간 요청", null));
        return mapper.readTree(result.body()).path("id").asText();
    }

    /**
     * 파서를 실행하지 않고 접수 경계에 필요한 최소 바이트를 새 초안으로 올린다.
     *
     * <p>Args: requestId는 조직이 포함된 연간 요청, year는 파일명의 요청 연도다.
     * Returns: 실제 서비스의 접수 성공 결과.
     * Raises: 업무 제한·DB 처리 오류는 런타임 예외.
     * Side Effects: 메모리 DB에 원본과 작업만 접수한다. 바이트는 실제 파서 성공 fixture가 아니다.
     */
    private CommandExecutor.Result uploadAnnual(String requestId, int year) {
        return service.upload(manager, requestId, UUID.randomUUID().toString(),
                "D100_" + year + "-PLAN.xlsx", new byte[] {'P', 'K', 3, 4, 0, 1});
    }

    /**
     * 최종 제출·검토가 끝난 과거 자료를 실제 스키마에 맞춰 준비한다.
     *
     * <p>Args: requestId는 연간 요청, year는 원본 이름의 연도,
     * seq는 양수 회차, status는 이 테스트의 APPROVED 또는 REJECTED다.
     * Returns: 생성한 제출본의 DB ID.
     * Raises: fixture 무결성 또는 DB 접근 오류는 런타임 예외.
     * Side Effects: 메모리 원본·제출·파싱 행을 생성하며 승인 업무 구현을 대신 호출하지 않는다.
     */
    private long seedSubmission(String requestId, int year, int seq, String status) {
        String objectKey = "fixture/" + UUID.randomUUID();
        jdbc.sql("INSERT INTO stored_object(object_key, content_type, payload) VALUES (?, ?, ?)")
                .params(objectKey, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[] {'P', 'K', 3, 4, 0, 1}).update();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO submission(request_id, organization_id, seq, status, original_filename,
                    source_object_key, source_sha256, uploaded_by, uploaded_at,
                    submitted_by, submitted_at, reviewed_by, reviewed_at, reject_reason)
                VALUES (?, 1, ?, ?, ?, ?, ?, 2, ?, 3, ?, 1, ?, ?)
                """).params(Long.parseLong(requestId), seq, status, "D100_" + year + "-PLAN.xlsx",
                        objectKey, "a".repeat(64), UPLOADED_AT, SUBMITTED_AT, REVIEWED_AT,
                        "REJECTED".equals(status) ? "테스트 반려 사유" : null)
                .update(key, "id");
        long submissionId = key.getKey().longValue();
        jdbc.sql("""
                INSERT INTO parsing_job(submission_id, status, engine, attempt_no)
                VALUES (?, 'SUCCEEDED', 'fixture', 1)
                """).param(submissionId).update();
        return submissionId;
    }
}
