package io.skala.backend.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.skala.backend.common.ApiException;
import io.skala.backend.security.Actor;
import io.skala.backend.security.Organization;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** 멱등 명령의 재전달·원자성·권한 재확인을 메모리 DB로 검증한다. */
class CommandExecutorTest {

    private static final String ROUTE = "POST /api/v1/submissions";
    private static final String KEY = "request-12345678";
    private static final String FINGERPRINT = "a".repeat(64);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private CommandExecutor commands;
    private Actor planner;

    /**
     * 각 검증에 독립 메모리 DB와 실제 초기 스키마를 준비한다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: DB 스키마나 고정 계정 초기화에 실패하면 데이터 접근 예외.
     * Side Effects: 메모리 DB만 만들며 파일·캐시·로그를 쓰지 않는다.
     */
    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:command-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=50");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1__submission_parser_foundation.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        commands = new CommandExecutor(jdbc, transactionManager, CLOCK);
        jdbc.update("INSERT INTO organization (id, code, name) VALUES (10, 'ORG10', '테스트 조직')");
        jdbc.update("""
                INSERT INTO app_user (id, email, name, role)
                VALUES (1, 'planner1@example.test', '기획 담당자', 'PLANNER'),
                       (2, 'planner2@example.test', '다른 담당자', 'PLANNER')
                """);
        planner = new Actor("1", "planner1@example.test", "기획 담당자", "PLANNER", null);
    }

    /**
     * 검증에서 사용한 메모리 DB를 해제한다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: 메모리 DB 종료 실패 시 데이터 접근 예외.
     * Side Effects: 해당 검증의 DB 연결과 메모리만 해제한다.
     */
    @AfterEach
    void tearDown() {
        if (jdbc != null) {
            jdbc.execute("SET DB_CLOSE_DELAY 0");
        }
    }

    /**
     * 같은 요청의 재시도는 원본 성공 응답을 반환하고 콜백을 다시 실행하지 않는다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: 기대 응답·실행 횟수가 다르면 AssertionError.
     * Side Effects: 메모리 DB에 멱등 성공 기록 한 건을 저장한다.
     */
    @Test
    void replaysExactSuccessWithoutExecutingAgain() {
        AtomicInteger calls = new AtomicInteger();
        CommandExecutor.Result first = commands.execute(planner, ROUTE, KEY, FINGERPRINT, () -> {
            calls.incrementAndGet();
            return success();
        });
        CommandExecutor.Result replay = commands.execute(planner, ROUTE, KEY, FINGERPRINT, () -> {
            calls.incrementAndGet();
            return new CommandExecutor.Result(200, "{}", null);
        });

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.status(), replay.status());
        assertEquals(first.body(), replay.body());
        assertEquals(first.location(), replay.location());
        assertEquals(1, calls.get());
        assertEquals(OffsetDateTime.now(CLOCK).plusHours(24), jdbc.queryForObject(
                "SELECT expires_at FROM idempotency_record", OffsetDateTime.class));
    }

    /**
     * 동일 키의 요청 내용 변경을 업무 콜백 실행 전에 거부한다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: 충돌 코드가 다르거나 콜백이 실행되면 AssertionError.
     * Side Effects: 최초 메모리 성공 기록만 남긴다.
     */
    @Test
    void rejectsDifferentFingerprintForSameKey() {
        commands.execute(planner, ROUTE, KEY, FINGERPRINT, this::success);
        ApiException exception = assertThrows(ApiException.class, () -> commands.execute(
                planner, ROUTE, KEY, "b".repeat(64), () -> {
                    throw new AssertionError("충돌 요청의 콜백이 실행되었습니다.");
                }));
        assertEquals("IDEMPOTENCY_KEY_REUSED", exception.code());
        assertEquals(409, exception.status().value());
    }

    /**
     * 콜백의 업무 저장 뒤 실패하면 업무 값과 멱등 기록을 함께 롤백한다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: 실패가 전파되지 않거나 DB 변경이 남으면 AssertionError.
     * Side Effects: 메모리 DB에 쓰기를 시도한 뒤 전체 롤백한다.
     */
    @Test
    void rollsBackBusinessWritesAndIdempotencyTogether() {
        assertThrows(IllegalStateException.class, () -> commands.execute(
                planner, ROUTE, KEY, FINGERPRINT, () -> {
                    jdbc.update("INSERT INTO organization (code, name) VALUES ('NEW', '새 조직')");
                    throw new IllegalStateException("테스트 업무 실패");
                }));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM organization WHERE code = 'NEW'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_record", Integer.class));
        assertFalse(commands.execute(planner, ROUTE, KEY, FINGERPRINT, this::success).replayed());
    }

    /**
     * 인증 이후 비활성화된 사용자는 저장된 성공 응답도 재전달받지 못한다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: 권한 오류가 아니면 AssertionError.
     * Side Effects: 메모리 사용자 상태와 최초 멱등 기록을 변경한다.
     */
    @Test
    void rechecksActorBeforeReplay() {
        commands.execute(planner, ROUTE, KEY, FINGERPRINT, this::success);
        jdbc.update("UPDATE app_user SET active = FALSE WHERE id = 1");
        ApiException exception = assertThrows(ApiException.class, () -> commands.execute(
                planner, ROUTE, KEY, FINGERPRINT, this::success));
        assertEquals("FORBIDDEN", exception.code());
    }

    /**
     * 조직 비활성화가 인증 이후 발생했어도 명령을 거부한다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: 비활성 조직에 명령이 허용되면 AssertionError.
     * Side Effects: 메모리 담당자·조직 상태만 변경한다.
     */
    @Test
    void rechecksOrganizationActivity() {
        jdbc.update("UPDATE app_user SET role = 'ORG_MANAGER', organization_id = 10 WHERE id = 1");
        Actor manager = new Actor("1", planner.email(), planner.name(), "ORG_MANAGER",
                new Organization("10", "ORG10", "테스트 조직"));
        jdbc.update("UPDATE organization SET active = FALSE WHERE id = 10");
        ApiException exception = assertThrows(ApiException.class, () -> commands.execute(
                manager, ROUTE, KEY, FINGERPRINT, this::success));
        assertEquals("FORBIDDEN", exception.code());
    }

    /**
     * 만료 시 같은 키의 새 실행은 허용하고 다른 키의 기록은 삭제하지 않는다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: 만료 범위 또는 새 실행 여부가 다르면 AssertionError.
     * Side Effects: 메모리 멱등 기록의 시각을 조정하고 한 키만 교체한다.
     */
    @Test
    void replacesOnlyExpiredMatchingKey() {
        commands.execute(planner, ROUTE, KEY, FINGERPRINT, this::success);
        commands.execute(planner, ROUTE, "another-key", FINGERPRINT, this::success);
        jdbc.update("UPDATE idempotency_record SET expires_at = ?", OffsetDateTime.now(CLOCK));

        CommandExecutor.Result result = commands.execute(planner, ROUTE, KEY, "b".repeat(64),
                () -> new CommandExecutor.Result(200, "{\"refreshed\":true}", null));

        assertFalse(result.replayed());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM idempotency_record", Integer.class));
        assertEquals(OffsetDateTime.now(CLOCK), jdbc.queryForObject(
                "SELECT expires_at FROM idempotency_record WHERE idempotency_key = 'another-key'",
                OffsetDateTime.class));
    }

    /**
     * 한 사용자의 실행 중 잠금은 즉시 충돌하며 다른 사용자의 명령은 막지 않는다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: 잠금 범위·충돌 코드가 다르면 AssertionError; 스레드 실패는 테스트에 전파한다.
     * Side Effects: 메모리 DB 행 잠금과 별도 스레드를 만들고 반드시 해제한다.
     */
    @Test
    void locksOnlyTheExecutingActor() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> holding = worker.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(transaction -> {
                    jdbc.queryForObject("SELECT id FROM app_user WHERE id = 1 FOR UPDATE", Long.class);
                    locked.countDown();
                    await(release);
                }));
        try {
            assertTrue(locked.await(2, TimeUnit.SECONDS));
            ApiException exception = assertThrows(ApiException.class, () -> commands.execute(
                    planner, ROUTE, KEY, FINGERPRINT, this::success));
            assertEquals("REQUEST_IN_PROGRESS", exception.code());
            Actor other = new Actor("2", "planner2@example.test", "다른 담당자", "PLANNER", null);
            assertFalse(commands.execute(other, ROUTE, KEY, FINGERPRINT, this::success).replayed());
        } finally {
            release.countDown();
            try {
                holding.get(3, TimeUnit.SECONDS);
            } finally {
                worker.shutdownNow();
            }
        }
    }

    /**
     * 검증에 재사용할 성공 응답을 만든다.
     * Args: 없음.
     * Returns: 새 제출 접수의 201 JSON 결과.
     * Raises: 없음.
     * Side Effects: 메모리 객체만 만들고 DB는 변경하지 않는다.
     */
    private CommandExecutor.Result success() {
        return new CommandExecutor.Result(201, "{\"id\":\"1\"}", "/api/v1/submissions/1");
    }

    /**
     * 잠금 검증의 해제 신호를 한정된 시간 동안 기다린다.
     * Args: latch는 주 스레드가 해제할 신호다.
     * Returns: 없음.
     * Raises: 신호 지연 또는 인터럽트 시 AssertionError.
     * Side Effects: 현재 테스트 스레드를 최대 5초 대기시키고 인터럽트 상태를 보존한다.
     */
    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("잠금 검증 대기가 중단되었습니다.", exception);
        }
    }
}
