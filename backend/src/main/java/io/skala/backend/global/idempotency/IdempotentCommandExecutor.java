package io.skala.backend.global.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 상태 변경 명령(POST·PUT·PATCH·DELETE)을 Idempotency-Key로 한 번만 실행한다(docs/api/api-spec.md 0-1절).
 * 명령과 응답 기록을 같은 트랜잭션에 저장하므로, 명령이 실패하면 아무것도 남지 않는다.
 *
 * <pre>{@code
 * return idempotency.execute(actor.id(), "POST /api/v1/submission-requests", key, request,
 *         () -> ResponseEntity.created(URI.create("/api/v1/submission-requests/" + id)).body(dto));
 * }</pre>
 */
@Component
public class IdempotentCommandExecutor {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Duration RETENTION = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    public IdempotentCommandExecutor(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<String> execute(long actorId, String route, String key, Object requestBody,
                                          Supplier<ResponseEntity<?>> command) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key 헤더를 확인해 주세요.");
        }
        String fingerprint = sha256(route + "\n" + json(requestBody));
        try {
            return transaction.execute(status -> {
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                jdbc.update("DELETE FROM idempotency_record WHERE actor_id=? AND route=? AND idempotency_key=? AND expires_at<?",
                        actorId, route, key, Timestamp.from(now.toInstant()));
                List<Stored> found = jdbc.query("""
                                SELECT fingerprint, response_status, response_body, location FROM idempotency_record
                                WHERE actor_id=? AND route=? AND idempotency_key=? FOR UPDATE""",
                        (rs, i) -> new Stored(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4)),
                        actorId, route, key);
                if (!found.isEmpty()) {
                    Stored stored = found.getFirst();
                    if (!stored.fingerprint().equals(fingerprint)) {
                        throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                    }
                    return response(stored.status(), stored.body(), stored.location(), true);
                }
                ResponseEntity<?> result = command.get();
                String body = result.getBody() == null ? "" : json(result.getBody());
                URI location = result.getHeaders().getLocation();
                String locationText = location == null ? "" : location.toString();
                jdbc.update("""
                                INSERT INTO idempotency_record(actor_id, route, idempotency_key, fingerprint,
                                    response_status, response_body, location, expires_at) VALUES (?,?,?,?,?,?,?,?)""",
                        actorId, route, key, fingerprint, result.getStatusCode().value(), body, locationText,
                        Timestamp.from(now.plus(RETENTION).toInstant()));
                return response(result.getStatusCode().value(), body, locationText, false);
            });
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
        }
    }

    private ResponseEntity<String> response(int status, String body, String location, boolean replayed) {
        HttpHeaders headers = new HttpHeaders();
        if (!body.isEmpty()) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (!location.isEmpty()) {
            headers.setLocation(URI.create(location));
        }
        if (replayed) {
            headers.set(REPLAYED_HEADER, "true");
        }
        return ResponseEntity.status(status).headers(headers).body(body.isEmpty() ? null : body);
    }

    private String json(Object value) {
        try {
            return value == null ? "" : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("응답을 JSON으로 바꿀 수 없습니다.", e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Stored(String fingerprint, int status, String body, String location) {
    }
}
