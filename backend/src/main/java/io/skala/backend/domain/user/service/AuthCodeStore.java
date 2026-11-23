package io.skala.backend.domain.user.service;

import io.skala.backend.domain.user.exception.AuthErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 이메일 인증코드 저장소(메모리, DB 테이블 없음). 코드 원문은 저장하지 않고 해시만 보관한다.
 * 규칙: 6자리, 유효 3분, 재전송은 1분 뒤, 5번 틀리면 다시 받아야 함(FR-AUTH-001~003).
 */
@Component
public class AuthCodeStore {

    static final Duration VALIDITY = Duration.ofMinutes(3);
    static final Duration RESEND_WAIT = Duration.ofMinutes(1);
    static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public AuthCodeStore(Clock clock) {
        this.clock = clock;
    }

    /** 새 코드를 만들어 원문을 돌려준다. 재전송 대기 중이면 CODE_RESEND_WAIT(429). */
    public Issued issue(String email) {
        String key = normalize(email);
        OffsetDateTime now = OffsetDateTime.now(clock);
        Entry previous = entries.get(key);
        if (previous != null && now.isBefore(previous.resendAvailableAt())) {
            throw AuthErrorCode.CODE_RESEND_WAIT.exception();
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Entry entry = new Entry(hash(key, code), now.plus(VALIDITY), now.plus(RESEND_WAIT), 0);
        entries.put(key, entry);
        return new Issued(code, entry.expiresAt(), entry.resendAvailableAt());
    }

    /** 맞으면 코드를 지운다. 틀리면 INVALID_AUTH_CODE(400, errors[0].code=MISMATCH·EXPIRED·TOO_MANY_ATTEMPTS). */
    public void verify(String email, String code) {
        String key = normalize(email);
        Entry entry = entries.get(key);
        if (entry == null) {
            throw AuthErrorCode.invalidCode("MISMATCH", AuthErrorCode.INVALID_AUTH_CODE.getMessage());
        }
        if (!OffsetDateTime.now(clock).isBefore(entry.expiresAt())) {
            entries.remove(key);
            throw AuthErrorCode.invalidCode("EXPIRED", "유효시간이 경과되어, 코드 재전송을 요청해주세요");
        }
        if (entry.attempts() >= MAX_ATTEMPTS) {
            throw AuthErrorCode.invalidCode("TOO_MANY_ATTEMPTS", "인증 코드를 여러 번 틀렸습니다. 코드 재전송을 요청해주세요");
        }
        if (!MessageDigest.isEqual(entry.codeHash().getBytes(StandardCharsets.UTF_8),
                hash(key, code).getBytes(StandardCharsets.UTF_8))) {
            entries.put(key, entry.withAttempt());
            throw AuthErrorCode.invalidCode("MISMATCH", AuthErrorCode.INVALID_AUTH_CODE.getMessage());
        }
        entries.remove(key);
    }

    static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String hash(String email, String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((email + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Issued(String code, OffsetDateTime expiresAt, OffsetDateTime resendAvailableAt) {
    }

    private record Entry(String codeHash, OffsetDateTime expiresAt, OffsetDateTime resendAvailableAt, int attempts) {
        Entry withAttempt() {
            return new Entry(codeHash, expiresAt, resendAvailableAt, attempts + 1);
        }
    }
}
