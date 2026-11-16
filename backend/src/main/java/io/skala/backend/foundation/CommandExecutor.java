package io.skala.backend.foundation;

import io.skala.backend.common.ApiException;
import io.skala.backend.security.Actor;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자별 명령과 성공 응답을 동일 DB 트랜잭션으로 실행·보존한다.
 * 승인되지 않은 파일 쓰기는 수행하지 않으며 호출 시점의 사용자 권한을 재확인한다.
 */
@Component
public final class CommandExecutor {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * 운영 DB와 UTC 시계로 명령 실행기를 구성한다.
     * Args: jdbc는 업무 DB 접근 객체, transactionManager는 같은 DataSource의 관리자다.
     * Returns: 없음. 생성한 실행기는 DB에 접속하거나 스키마를 생성하지 않는다.
     * Raises: IllegalArgumentException — 트랜잭션 관리자 구성이 올바르지 않을 때.
     * Side Effects: 메모리 설정만 생성하며 파일·로그·DB를 쓰지 않는다.
     */
    @Autowired
    public CommandExecutor(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, Clock.systemUTC());
    }

    /**
     * 만료 정책을 검증할 수 있도록 시계를 주입한다.
     * Args: jdbc와 transactionManager는 같은 DB 연결을 사용하고 clock은 현재 시각 제공자다.
     * Returns: 없음.
     * Raises: NullPointerException — jdbc·clock이 null일 때.
     * Side Effects: 메모리 설정만 생성하며 DB 연결을 열지 않는다.
     */
    CommandExecutor(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 현재 권한 확인 후 같은 사용자·경로·키의 명령을 한 번만 실행한다.
     * Args: actor는 인증한 사용자, route는 메서드를 포함한 서버의 정규 경로,
     * key는 8~128자 멱등키, fingerprint는 요청 내용의 SHA-256 소문자 hex,
     * action은 같은 DB에 성공 결과를 만드는 콜백이다. 외부 파일 쓰기를 넣지 않는다.
     * Returns: 새 성공 결과 또는 24시간 내 같은 요청의 성공 결과(replayed=true).
     * Raises: ApiException — 입력·권한·동시 실행·키 충돌·DB 제약 충돌;
     * 런타임 예외 — 콜백 실패는 전체 트랜잭션을 롤백하고 전파한다.
     * Side Effects: 사용자 행 잠금, 해당 키의 만료 기록 삭제, 콜백 및 성공 응답 DB 저장.
     * 실패 시 콜백의 같은 DB 변경도 롤백하며 파일·로그는 쓰지 않는다.
     */
    public Result execute(Actor actor, String route, String key, String fingerprint,
                          Supplier<Result> action) {
        validateInput(actor, route, key, fingerprint, action);
        try {
            return Objects.requireNonNull(transactions.execute(transaction -> {
                long actorId = lockActor(actor);
                OffsetDateTime now = OffsetDateTime.now(clock);
                jdbc.update("""
                        DELETE FROM idempotency_record
                        WHERE actor_id = ? AND route = ? AND idempotency_key = ?
                          AND expires_at <= ?
                        """, actorId, route, key, now);
                List<SavedResult> saved = jdbc.query("""
                        SELECT fingerprint, response_status, response_body, location
                        FROM idempotency_record
                        WHERE actor_id = ? AND route = ? AND idempotency_key = ?
                        """, (row, index) -> new SavedResult(row.getString("fingerprint"),
                        new Result(row.getInt("response_status"), row.getString("response_body"),
                                row.getString("location"), true)), actorId, route, key);
                if (!saved.isEmpty()) {
                    SavedResult previous = saved.getFirst();
                    if (!previous.fingerprint().equals(fingerprint)) {
                        throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                                "같은 멱등키에 다른 요청 내용을 사용할 수 없습니다.");
                    }
                    return previous.result();
                }
                Result result = Objects.requireNonNull(action.get(), "업무 명령 결과가 없습니다.");
                if (result.replayed()) {
                    throw new IllegalStateException("새 업무 명령이 재전달 결과를 반환했습니다.");
                }
                jdbc.update("""
                        INSERT INTO idempotency_record
                        (actor_id, route, idempotency_key, fingerprint, response_status,
                         response_body, location, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, actorId, route, key, fingerprint, result.status(), result.body(),
                        result.location(), now.plusHours(24));
                return result;
            }));
        } catch (PessimisticLockingFailureException | QueryTimeoutException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                    "현재 처리 중인 명령이 있습니다. 잠시 후 다시 시도해 주세요.");
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_CHANGED",
                    "현재 데이터와 충돌합니다. 최신 상태를 확인해 주세요.");
        }
    }

    /**
     * DB 접근 전 인증 객체와 멱등 식별자의 최소 형식을 검증한다.
     * Args: actor는 인증 사용자, route는 서버의 정규 경로, key는 멱등키,
     * fingerprint는 소문자 SHA-256, action은 실행할 콜백이다.
     * Returns: 없음.
     * Raises: ApiException — 미인증은 401, 키·경로·fingerprint·콜백 오류는 400.
     * Side Effects: 없음. 입력값을 오류 메시지로 복사하지 않는다.
     */
    private void validateInput(Actor actor, String route, String key, String fingerprint,
                               Supplier<Result> action) {
        if (actor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "인증이 필요합니다.");
        }
        if (route == null || route.isBlank() || route.length() > 200
                || key == null || !KEY.matcher(key).matches()
                || fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()
                || action == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "명령의 요청 식별자와 멱등키를 확인해 주세요.");
        }
    }

    /**
     * 사용자 행을 대기 없이 잠그고 인증 이후 권한 변경을 확인한다.
     * Args: actor는 이번 요청에서 인증한 사용자와 역할·조직 정보다.
     * Returns: 잠근 사용자의 양수 DB ID.
     * Raises: ApiException — 삭제·비활성·역할/조직 변경으로 권한이 유효하지 않을 때;
     * DB 잠금 예외는 execute에서 안전한 충돌 응답으로 변환한다.
     * Side Effects: 현재 트랜잭션이 끝날 때까지 해당 사용자 행만 잠근다.
     */
    private long lockActor(Actor actor) {
        long actorId;
        try {
            actorId = Long.parseLong(actor.id());
        } catch (NumberFormatException exception) {
            throw forbidden();
        }
        if (actorId <= 0) {
            throw forbidden();
        }
        LockedActor current;
        try {
            current = jdbc.queryForObject("""
                    SELECT role, organization_id, active FROM app_user
                    WHERE id = ? FOR UPDATE NOWAIT
                    """, (row, index) -> new LockedActor(row.getString("role"),
                    row.getString("organization_id"), row.getBoolean("active")), actorId);
        } catch (EmptyResultDataAccessException exception) {
            throw forbidden();
        }
        String organizationId = actor.organization() == null ? null : actor.organization().id();
        if (current == null || !current.active() || !current.role().equals(actor.role())
                || !Objects.equals(current.organizationId(), organizationId)) {
            throw forbidden();
        }
        if (organizationId != null && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT active FROM organization WHERE id = ?", Boolean.class,
                Long.parseLong(organizationId)))) {
            throw forbidden();
        }
        return actorId;
    }

    /**
     * 인증 시점 이후 사용자 상태 변경에 사용할 고정 오류를 만든다.
     * Args: 없음.
     * Returns: 내부 권한값을 노출하지 않는 403 업무 오류.
     * Raises: 없음.
     * Side Effects: 없음.
     */
    private ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "현재 권한으로 이 명령을 실행할 수 없습니다.");
    }

    /** 사용자 행의 필요한 권한만 담으며 자동 접근자는 값을 읽기만 한다. */
    private record LockedActor(String role, String organizationId, boolean active) {
    }

    /** 저장된 요청 해시와 응답을 담으며 자동 접근자는 불변 값을 반환한다. */
    private record SavedResult(String fingerprint, Result result) {
    }

    /**
     * 저장·재전달 가능한 성공 응답이다. body는 직렬화된 JSON이며 location은 없으면 빈 문자열이다.
     * 자동 접근자는 상태·본문·경로·재전달 여부를 읽기만 한다.
     */
    public record Result(int status, String body, String location, boolean replayed) {

        /**
         * 새로운 명령의 성공 결과를 만든다.
         * Args: status는 2xx 상태, body는 JSON 문자열, location은 API 경로나 null이다.
         * Returns: 없음. replayed=false로 생성한다.
         * Raises: IllegalArgumentException — 상태·본문·경로가 허용 범위를 벗어날 때.
         * Side Effects: 없음.
         */
        public Result(int status, String body, String location) {
            this(status, body, location, false);
        }

        /**
         * DB 저장 전 응답의 최소 형태와 헤더 경로를 검증한다.
         * Args: status는 2xx, body는 null이 아닌 JSON, location은 없거나 API 경로,
         * replayed는 기존 성공 응답인지 나타낸다.
         * Returns: 없음. null location은 DB 계약에 따라 빈 문자열로 바꾼다.
         * Raises: IllegalArgumentException — 성공 상태·본문·Location 형식 위반.
         * Side Effects: 없음. JSON 문법의 정확성은 호출자의 직렬화기가 보장한다.
         */
        public Result {
            location = location == null ? "" : location;
            if (status < 200 || status > 299 || body == null || location.length() > 200
                    || (!location.isEmpty() && !location.startsWith("/api/v1/"))
                    || location.contains("\r") || location.contains("\n")) {
                throw new IllegalArgumentException("저장할 성공 응답 형식이 올바르지 않습니다.");
            }
        }
    }
}
