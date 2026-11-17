package io.skala.backend.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.master.AnnualMasterService;
import io.skala.backend.master.MonthlyMasterService;
import io.skala.backend.security.Actor;
import io.skala.backend.security.Organization;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/** 실제 DB 명령 경계와 검증 서비스 대역으로 제출 잠금·회차·실패 원자성을 검사한다. */
class SubmissionWorkflowTests {
    private EmbeddedDatabase database;
    private JdbcClient jdbc;
    private ObjectMapper mapper;
    private SubmissionService submissions;
    private SubmissionValidationService validation;
    private SubmissionWorkflowService workflow;
    private AnnualMasterService annualMasters;
    private Actor manager;
    private Actor secondManager;

    /** V1+V2+V3와 독립 합성 조직·초안을 메모리 DB에 준비한다.
     * Args: 없음. Returns: 없음. Raises: DB·대역 준비 오류.
     * Side Effects: 테스트 전용 H2 생성·초기화; 원본 Data·파일·운영 DB 접근 없음.
     */
    @BeforeEach
    void prepare() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("workflow_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=YEAR,MONTH;LOCK_TIMEOUT=5000")
                .build();
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__submission_parser_foundation.sql"),
                new ClassPathResource("db/migration/V2__validated_financial_submissions.sql"),
                new ClassPathResource("db/migration/V3__annual_plan_masters.sql"),
                new ClassPathResource("db/migration/V4__monthly_masters.sql"),
                new ClassPathResource("db/migration/V5__submission_reviews_and_gap_thresholds.sql"),
                new ClassPathResource("db/migration/V6__prd_review_workflow.sql")).execute(database);
        jdbc = JdbcClient.create(database);
        mapper = new ObjectMapper();
        jdbc.sql("INSERT INTO organization(id,code,name) VALUES (1,'D100','합성 조직'),(2,'D200','다른 조직')").update();
        jdbc.sql("""
                INSERT INTO app_user(id,email,name,role,organization_id) VALUES
                (1,'planner@example.test','기획','PLANNER',NULL),
                (2,'manager@example.test','담당','ORG_MANAGER',1),
                (3,'second@example.test','같은 조직 담당','ORG_MANAGER',1),
                (4,'other@example.test','타 조직 담당','ORG_MANAGER',2)
                """).update();
        jdbc.sql("INSERT INTO annual_plan(id,year,status) VALUES (1,2031,'COLLECTING')").update();
        jdbc.sql("""
                INSERT INTO submission_request(id,annual_plan_id,type,status,requested_by,requested_at,period_key)
                VALUES (1,1,'ANNUAL_PLAN','OPEN',1,CURRENT_TIMESTAMP,'2031-PLAN')
                """).update();
        jdbc.sql("INSERT INTO request_target(request_id,organization_id) VALUES (1,1),(1,2)").update();
        jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES ('source/test','application/octet-stream',?),('result/test','application/json',?)")
                .params(new byte[]{1}, "{}".getBytes(StandardCharsets.UTF_8)).update();
        seedDraft(10);
        manager = new Actor("2", "manager@example.test", "담당", "ORG_MANAGER", new Organization("1", "D100", "합성 조직"));
        secondManager = new Actor("3", "second@example.test", "같은 조직 담당", "ORG_MANAGER", manager.organization());
        submissions = mock(SubmissionService.class);
        validation = mock(SubmissionValidationService.class);
        annualMasters = mock(AnnualMasterService.class);
        when(annualMasters.publishAfterApproval(any(), anyString())).thenReturn(Map.of("kind", "ANNUAL_PLAN", "action", "WAITING_INITIAL"));
        when(submissions.submission(any(Actor.class), anyString())).thenAnswer(invocation ->
                currentView(invocation.getArgument(0), invocation.getArgument(1)));
        var commands = new CommandExecutor(new JdbcTemplate(database), new DataSourceTransactionManager(database));
        workflow = new SubmissionWorkflowService(jdbc, mapper, commands, submissions, validation, annualMasters, mock(MonthlyMasterService.class));
    }

    /** 각 테스트의 메모리 DB 연결을 종료한다.
     * Args: 없음. Returns: 없음. Raises: DB 종료 오류.
     * Side Effects: 메모리 자원 해제; 외부 파일·DB 삭제 없음.
     */
    @AfterEach
    void close() { if (database != null) database.shutdown(); }

    /** 검증 명령은 회차를 만들지 않고 검증 결과와 빈 변경 배열을 반환한다.
     * Args: 없음. Returns: 없음. Raises: JSON·검증 오류.
     * Side Effects: 메모리 DB에 멱등 응답만 저장; 검증기는 대역이다.
     */
    @Test
    void validationKeepsDraftAndDoesNotConsumeSequence() throws Exception {
        var result = workflow.validate(manager, "10", "validate-first-01", input(manager, "10"));
        var body = mapper.readTree(result.body());
        assertThat(body.path("submission").path("status").asText()).isEqualTo("DRAFT");
        assertThat(body.path("changedReviewItemIds").isEmpty()).isTrue();
        assertThat(body.path("answerRequiredReviewItemIds").isEmpty()).isTrue();
        assertThat(jdbc.sql("SELECT seq FROM submission WHERE id=10").query().singleRow().get("seq")).isNull();
        verify(validation).validateLocked(manager, "10");
        verify(validation, never()).assertReadyForSubmit(any(), anyString());
    }

    /** 최종 제출만 회차를 만들고 같은 명령 재전달은 검증·회차를 반복하지 않는다.
     * Args: 없음. Returns: 없음. Raises: JSON·검증 오류.
     * Side Effects: 메모리 DB의 합성 초안·멱등 응답 갱신.
     */
    @Test
    void finalSubmissionIsPendingAndIdempotent() throws Exception {
        var before = input(manager, "10");
        var first = workflow.submit(manager, "10", "submit-first-001", before);
        var repeated = workflow.submit(manager, "10", "submit-first-001", before);
        assertThat(first.status()).isEqualTo(200);
        assertThat(repeated.replayed()).isTrue();
        assertThat(repeated.body()).isEqualTo(first.body());
        var stored = jdbc.sql("SELECT status,seq,submitted_by,submitted_at FROM submission WHERE id=10").query().singleRow();
        assertThat(stored.get("status")).isEqualTo("PENDING");
        assertThat(((Number) stored.get("seq")).intValue()).isEqualTo(1);
        assertThat(((Number) stored.get("submitted_by")).longValue()).isEqualTo(2);
        assertThat(stored.get("submitted_at")).isNotNull();
        verify(validation, times(1)).assertReadyForSubmit(manager, "10");
        assertThatThrownBy(() -> workflow.submit(manager, "10", "submit-second-01", input(manager, "10")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_SUBMISSION_STATE");
        assertThatThrownBy(() -> workflow.validate(manager, "10", "validate-after-01", input(manager, "10")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_SUBMISSION_STATE");
    }

    /** 토큰·파싱·검증 근거가 준비되지 않았을 때 상태와 회차를 보존한다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류.
     * Side Effects: 메모리 파싱 상태와 대역 오류 조건만 변경.
     */
    @Test
    void staleUnparsedAndUnvalidatedDraftCannotSubmit() {
        assertThatThrownBy(() -> workflow.submit(manager, "10", "submit-stale-01", new SubmissionWorkflowService.StateInput("stale")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RESOURCE_CHANGED");
        jdbc.sql("UPDATE parsing_job SET status='RUNNING' WHERE submission_id=10").update();
        assertThatThrownBy(() -> workflow.validate(manager, "10", "validate-running", input(manager, "10")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PARSING_NOT_READY");
        jdbc.sql("UPDATE parsing_job SET status='SUCCEEDED' WHERE submission_id=10").update();
        doThrow(new ApiException(HttpStatus.CONFLICT, "VALIDATION_REQUIRED", "검증 필요"))
                .when(validation).assertReadyForSubmit(manager, "10");
        assertThatThrownBy(() -> workflow.submit(manager, "10", "submit-unvalidated", input(manager, "10")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("VALIDATION_REQUIRED");
        assertDraft(10);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM idempotency_record").query(Long.class).single()).isZero();
    }

    /** 반려된 이전 제출과 초안 업로드 수를 덮어쓰지 않고 완료 회차만 증가시킨다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류.
     * Side Effects: 메모리 DB의 이전 반려본·추가 초안을 준비하고 한 초안만 제출한다.
     */
    @Test
    void sequenceFollowsSubmittedHistoryAndPreservesRejectedSource() {
        seedDraft(11);
        seedDraft(12);
        jdbc.sql("UPDATE submission SET status='REJECTED',seq=3,submitted_by=3,submitted_at=CURRENT_TIMESTAMP,reject_reason='합성 반려 근거' WHERE id=11").update();
        workflow.submit(manager, "10", "submit-next-seq-01", input(manager, "10"));
        assertThat(jdbc.sql("SELECT seq FROM submission WHERE id=10").query(Integer.class).single()).isEqualTo(4);
        var previous = jdbc.sql("SELECT status,seq,reject_reason FROM submission WHERE id=11").query().singleRow();
        assertThat(previous.get("status")).isEqualTo("REJECTED");
        assertThat(((Number) previous.get("seq")).intValue()).isEqualTo(3);
        assertThat(previous.get("reject_reason")).isEqualTo("합성 반려 근거");
        assertDraft(12);
    }

    /** 닫힌 요청·확정 계획·기존 검토 대기 제출을 우회하지 못한다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류.
     * Side Effects: 메모리 요청·계획·합성 제출의 상태만 변경.
     */
    @Test
    void closedConfirmedAndPendingOrganizationBlockFinalSubmission() {
        jdbc.sql("UPDATE submission_request SET status='CLOSED' WHERE id=1").update();
        assertThatThrownBy(() -> workflow.submit(manager, "10", "submit-closed-01", input(manager, "10")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("REQUEST_CLOSED");
        jdbc.sql("UPDATE submission_request SET status='OPEN' WHERE id=1").update();
        jdbc.sql("UPDATE annual_plan SET status='CONFIRMED' WHERE id=1").update();
        assertThatThrownBy(() -> workflow.submit(manager, "10", "submit-locked-01", input(manager, "10")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ANNUAL_PLAN_LOCKED");
        jdbc.sql("UPDATE annual_plan SET status='COLLECTING' WHERE id=1").update();
        seedDraft(11);
        jdbc.sql("UPDATE submission SET status='PENDING',seq=1,submitted_by=2,submitted_at=CURRENT_TIMESTAMP WHERE id=11").update();
        assertThatThrownBy(() -> workflow.submit(manager, "10", "submit-pending-01", input(manager, "10")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PENDING_ALREADY_EXISTS");
        assertDraft(10);
        verifyNoInteractions(validation);
    }

    /** 검증 서비스의 부분 쓰기 후 실패도 명령 전체와 함께 롤백한다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류.
     * Side Effects: 메모리 DB 변경을 고의로 시도하고 롤백 결과를 확인.
     */
    @Test
    void validationFailureRollsBackPartialWritesAndCommandRecord() {
        doAnswer(invocation -> {
            jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES ('validation/partial','application/json',?)")
                    .param("{}".getBytes(StandardCharsets.UTF_8)).update();
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "검증 실패");
        }).when(validation).validateLocked(manager, "10");
        assertThatThrownBy(() -> workflow.validate(manager, "10", "validate-rollback", input(manager, "10")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_AMOUNT");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM stored_object WHERE object_key='validation/partial'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM idempotency_record").query(Long.class).single()).isZero();
        assertDraft(10);
    }

    /** 서로 다른 담당자가 동시에 제출해도 같은 조직의 PENDING은 하나만 만든다.
     * Args: 없음. Returns: 없음. Raises: 스레드·검증 오류.
     * Side Effects: 메모리 DB·테스트 스레드만 사용하며 제한 시간 안에 종료한다.
     */
    @Test
    void differentUsersOfOneOrganizationCannotCreateTwoPendingSubmissions() throws Exception {
        seedDraft(11);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("테스트 잠금 대기 초과");
            return null;
        }).when(validation).assertReadyForSubmit(manager, "10");
        var firstInput = input(manager, "10");
        var secondInput = input(secondManager, "11");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> workflow.submit(manager, "10", "submit-concurrent-1", firstInput));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                try {
                    workflow.submit(secondManager, "11", "submit-concurrent-2", secondInput);
                    return "UNEXPECTED_SUCCESS";
                } catch (ApiException error) {
                    return error.code();
                }
            });
            assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("PENDING_ALREADY_EXISTS");
        } finally {
            release.countDown();
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submission WHERE status='PENDING' AND organization_id=1").query(Long.class).single()).isEqualTo(1);
        assertDraft(11);
    }

    /** 다른 조직도 같은 연도의 검증 중에는 공통 카탈로그 최초 생성 구간에 함께 진입하지 않는다.
     * Args: 없음. Returns: 없음. Raises: 스레드·검증 오류.
     * Side Effects: 서로 다른 조직의 합성 초안·테스트 스레드·메모리 DB만 사용한다.
     */
    @Test
    void annualPlanLockSerializesValidationAcrossOrganizations() throws Exception {
        seedDraft(11);
        jdbc.sql("UPDATE submission SET organization_id=2,uploaded_by=4 WHERE id=11").update();
        Actor other = new Actor("4", "other@example.test", "타 조직 담당", "ORG_MANAGER", new Organization("2", "D200", "다른 조직"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("테스트 잠금 대기 초과");
            return null;
        }).when(validation).validateLocked(manager, "10");
        var firstInput = input(manager, "10");
        var secondInput = input(other, "11");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> workflow.validate(manager, "10", "validate-plan-lock-1", firstInput));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> workflow.validate(other, "11", "validate-plan-lock-2", secondInput));
            assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            release.countDown();
        }
        assertDraft(10);
        assertDraft(11);
        verify(validation).validateLocked(other, "11");
    }

    /** 역할과 타 조직 접근을 검증 서비스 실행 전에 차단한다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류.
     * Side Effects: 메모리 조회만 수행.
     */
    @Test
    void plannerAndOtherOrganizationCannotIssueCommands() {
        Actor planner = new Actor("1", "planner@example.test", "기획", "PLANNER", null);
        Actor other = new Actor("4", "other@example.test", "타 조직 담당", "ORG_MANAGER", new Organization("2", "D200", "다른 조직"));
        assertThatThrownBy(() -> workflow.submit(planner, "10", "submit-planner-01", input(manager, "10")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> workflow.validate(other, "10", "validate-other-01", input(manager, "10")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RESOURCE_NOT_FOUND");
        verifyNoInteractions(validation);
        assertDraft(10);
    }

    /** 서로 다른 기획자의 승인·반려 경합에서 먼저 잠근 판단만 한 번 저장한다.
     * Args: 없음. Returns: 없음. Raises: 스레드·검증 오류.
     * Side Effects: 합성 제출·기획 계정·테스트 스레드와 메모리 DB만 사용한다.
     */
    @Test
    void concurrentApprovalAndRejectionPreserveOneDecision() throws Exception {
        Actor planner = planner();
        jdbc.sql("INSERT INTO app_user(id,email,name,role) VALUES (5,'reviewer@example.test','다른 기획','PLANNER')").update();
        Actor reviewer = new Actor("5", "reviewer@example.test", "다른 기획", "PLANNER", null);
        makePending(10);
        var approve = decisionInput(planner, "10", "APPROVE", null);
        var reject = decisionInput(reviewer, "10", "REJECT", "합성 검토 사유");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("테스트 잠금 대기 초과");
            return null;
        }).when(validation).assertReadyForApproval(planner, "10");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> workflow.decide(planner, "10", "approve-race-01", approve));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                try {
                    workflow.decide(reviewer, "10", "reject-race-01", reject);
                    return "UNEXPECTED_SUCCESS";
                } catch (ApiException error) {
                    return error.code();
                }
            });
            assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("INVALID_SUBMISSION_STATE");
        } finally {
            release.countDown();
        }
        var row = jdbc.sql("SELECT status,seq,reviewed_by,reject_reason FROM submission WHERE id=10").query().singleRow();
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(((Number) row.get("seq")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("reviewed_by")).longValue()).isEqualTo(1);
        assertThat(row.get("reject_reason")).isNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM idempotency_record").query(Long.class).single()).isEqualTo(1);
    }

    /** 승인 근거 검사 실패가 부분 쓰기와 검토 기록·멱등 응답을 함께 롤백한다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류.
     * Side Effects: 메모리 DB에 고의 부분 쓰기를 시도하고 롤백 결과만 읽는다.
     */
    @Test
    void approvalFailureRollsBackDecisionAndPartialWrites() {
        Actor planner = planner();
        makePending(10);
        doAnswer(invocation -> {
            jdbc.sql("UPDATE submission SET reviewed_by=1,reviewed_at=CURRENT_TIMESTAMP WHERE id=10").update();
            throw new ApiException(HttpStatus.CONFLICT, "APPROVAL_NOT_READY", "승인 근거 불충분");
        }).when(validation).assertReadyForApproval(planner, "10");
        assertThatThrownBy(() -> workflow.decide(planner, "10", "approve-rollback-01", decisionInput(planner, "10", "APPROVE", null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("APPROVAL_NOT_READY");
        var row = jdbc.sql("SELECT status,reviewed_by,reviewed_at FROM submission WHERE id=10").query().singleRow();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("reviewed_by")).isNull();
        assertThat(row.get("reviewed_at")).isNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM idempotency_record").query(Long.class).single()).isZero();
    }

    /** 최신 완료 회차가 아닌 대기본과 확정된 계획의 승인을 차단한다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류.
     * Side Effects: 메모리 DB에 불일치 회차·확정 계획 조건을 준비하며 업무 명령은 실패한다.
     */
    @Test
    void olderPendingAndConfirmedPlanCannotBeApproved() {
        Actor planner = planner();
        makePending(10);
        seedDraft(11);
        jdbc.sql("UPDATE submission SET status='REJECTED',seq=2,submitted_by=2,submitted_at=CURRENT_TIMESTAMP WHERE id=11").update();
        assertThatThrownBy(() -> workflow.decide(planner, "10", "approve-old-01", decisionInput(planner, "10", "APPROVE", null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_SUBMISSION_STATE");
        jdbc.sql("UPDATE submission SET status='DRAFT',seq=NULL WHERE id=11").update();
        jdbc.sql("UPDATE annual_plan SET status='CONFIRMED' WHERE id=1").update();
        assertThatThrownBy(() -> workflow.decide(planner, "10", "approve-confirmed-01", decisionInput(planner, "10", "APPROVE", null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ANNUAL_PLAN_LOCKED");
        assertThat(jdbc.sql("SELECT status FROM submission WHERE id=10").query(String.class).single()).isEqualTo("PENDING");
        verifyNoInteractions(validation);
    }

    /** 마스터 발행 실패 시 승인 헤더와 부분 발행·멱등 기록이 함께 롤백되는지 검사한다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류.
     * Side Effects: H2 트랜잭션에서 승인·합성 마스터 쓰기를 시도한 뒤 롤백한다.
     */
    @Test
    void publicationFailureRollsBackApprovalAndMaster() {
        makePending(10);
        Actor planner = planner();
        when(annualMasters.publishAfterApproval(planner, "10")).thenAnswer(invocation -> {
            assertThat(jdbc.sql("SELECT status FROM submission WHERE id=10").query(String.class).single()).isEqualTo("APPROVED");
            jdbc.sql("""
                    INSERT INTO annual_master(request_id,seq,trigger_submission_id,content_sha256,calculation_version,created_by,created_at)
                    VALUES (1,1,10,?,'test',1,CURRENT_TIMESTAMP)
                    """).param("a".repeat(64)).update();
            throw new IllegalStateException("합성 발행 실패");
        });
        assertThatThrownBy(() -> workflow.decide(planner, "10", "publish-rollback-01", decisionInput(planner, "10", "APPROVE", null)))
                .isInstanceOf(IllegalStateException.class).hasMessage("합성 발행 실패");
        var row = jdbc.sql("SELECT status,reviewed_by,reviewed_at FROM submission WHERE id=10").query().singleRow();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("reviewed_by")).isNull();
        assertThat(row.get("reviewed_at")).isNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM annual_master").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM idempotency_record").query(Long.class).single()).isZero();
    }

    /** 제외된 조직의 기존 초안·대기본을 보존하면서 모든 제출 판단 명령을 막는다.
     * Args: 없음. Returns: 없음. Raises: 검증 오류.
     * Side Effects: H2 대상 제외 표시와 합성 PENDING 준비만 수행한다.
     */
    @Test
    void excludedTargetCannotValidateSubmitOrReceiveDecision() {
        jdbc.sql("UPDATE request_target SET excluded=TRUE WHERE request_id=1 AND organization_id=1").update();
        assertThatThrownBy(() -> workflow.validate(manager, "10", "excluded-validation", input(manager, "10")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("REQUEST_TARGET_EXCLUDED");
        assertThatThrownBy(() -> workflow.submit(manager, "10", "excluded-submit", input(manager, "10")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("REQUEST_TARGET_EXCLUDED");
        makePending(10);
        Actor planner = planner();
        assertThatThrownBy(() -> workflow.decide(planner, "10", "excluded-approve", decisionInput(planner, "10", "APPROVE", null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("REQUEST_TARGET_EXCLUDED");
        assertThatThrownBy(() -> workflow.decide(planner, "10", "excluded-reject", decisionInput(planner, "10", "REJECT", "검토")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("REQUEST_TARGET_EXCLUDED");
        verifyNoInteractions(validation, annualMasters);
        assertThat(jdbc.sql("SELECT status FROM submission WHERE id=10").query(String.class).single()).isEqualTo("PENDING");
    }

    /** 독립 기획 계정의 인증 문맥을 만든다.
     * Args: 없음. Returns: 준비한 DB 기획자와 일치하는 Actor. Raises: 없음. Side Effects: 없음.
     */
    private Actor planner() { return new Actor("1", "planner@example.test", "기획", "PLANNER", null); }

    /** 초안을 완료 회차 하나의 합성 검토 대기본으로 준비한다.
     * Args: submissionId 준비할 제출 ID. Returns: 없음. Raises: DB 오류. Side Effects: 메모리 제출 행 변경.
     */
    private void makePending(long submissionId) {
        jdbc.sql("UPDATE submission SET status='PENDING',seq=1,submitted_by=2,submitted_at=CURRENT_TIMESTAMP WHERE id=?")
                .param(submissionId).update();
    }

    /** 최신 토큰과 행위별 허용 필드만 가진 판단 입력을 만든다.
     * Args: actor 기획자, submissionId 제출, action APPROVE/REJECT, note 반려 사유 또는 null.
     * Returns: 서비스 판단 입력. Raises: 입력 변환 오류. Side Effects: 메모리 DB 읽기.
     */
    private SubmissionWorkflowService.DecisionInput decisionInput(Actor actor, String submissionId, String action, String note) {
        return new SubmissionWorkflowService.DecisionInput(currentView(actor, submissionId).get("stateToken").toString(), action, note);
    }

    /** 독립 원본·성공 파싱을 참조하는 합성 초안을 준비한다.
     * Args: submissionId 합성 ID. Returns: 없음. Raises: DB 오류.
     * Side Effects: 메모리 DB의 제출·파싱 행 INSERT.
     */
    private void seedDraft(long submissionId) {
        jdbc.sql("""
                INSERT INTO submission(id,request_id,organization_id,status,original_filename,source_object_key,source_sha256,uploaded_by,uploaded_at)
                VALUES (?,1,1,'DRAFT','D100_2031-PLAN.xlsx','source/test',?,2,CURRENT_TIMESTAMP)
                """).params(submissionId, "0".repeat(64)).update();
        jdbc.sql("INSERT INTO parsing_job(submission_id,status,engine,attempt_no,result_object_key) VALUES (?,'SUCCEEDED','test',1,'result/test')")
                .param(submissionId).update();
    }

    /** 조회 서비스 대역에 실제 제출 상태와 파싱 시도를 제공한다.
     * Args: actor 접근 사용자, submissionId 조회 ID. Returns: 최소 제출 DTO와 상태 토큰.
     * Raises: ApiException 권한 밖/없는 ID. Side Effects: 메모리 DB 읽기.
     */
    private Map<String, Object> currentView(Actor actor, String submissionId) {
        var rows = jdbc.sql("SELECT s.id,s.organization_id,s.status,s.seq,j.attempt_no,j.status AS parsing_status FROM submission s JOIN parsing_job j ON j.submission_id=s.id WHERE s.id=?")
                .param(ApiData.id(submissionId)).query().listOfRows();
        if (rows.isEmpty() || !actor.canAccessOrganization(rows.getFirst().get("organization_id").toString())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "자료 없음");
        }
        var row = rows.getFirst();
        String token = ApiData.hash((row.get("id") + ":" + row.get("status") + ":" + row.get("seq") + ":"
                + row.get("attempt_no") + ":" + row.get("parsing_status")).getBytes(StandardCharsets.UTF_8));
        return ApiData.object("id", submissionId, "organizationId", row.get("organization_id").toString(), "status", row.get("status"), "seq", row.get("seq"), "stateToken", token);
    }

    /** 현재 합성 상태에서 명령 입력을 만든다.
     * Args: actor 사용자, submissionId 초안 ID. Returns: 기대 토큰 입력.
     * Raises: ApiException 조회 실패. Side Effects: 메모리 DB 읽기.
     */
    private SubmissionWorkflowService.StateInput input(Actor actor, String submissionId) {
        return new SubmissionWorkflowService.StateInput(currentView(actor, submissionId).get("stateToken").toString());
    }

    /** 실패한 명령이 초안과 미발급 회차·제출자를 보존했는지 확인한다.
     * Args: submissionId 검사 ID. Returns: 없음. Raises: AssertionError 불변식 위반.
     * Side Effects: 메모리 DB 읽기.
     */
    private void assertDraft(long submissionId) {
        var row = jdbc.sql("SELECT status,seq,submitted_by,submitted_at FROM submission WHERE id=?").param(submissionId).query().singleRow();
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("seq")).isNull();
        assertThat(row.get("submitted_by")).isNull();
        assertThat(row.get("submitted_at")).isNull();
    }
}
