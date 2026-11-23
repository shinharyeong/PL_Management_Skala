package io.skala.backend.global.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 로그인 세션 토큰 저장소(메모리). 토큰 원문은 저장하지 않고 SHA-256 해시만 보관한다.
 * backend 파드 1개 기준(인프라 문서)이며, 재시작하면 다시 로그인해야 한다. 파드를 늘리면 공유 저장소로 바꾼다.
 */
@Component
public class SessionTokenStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public SessionTokenStore(Clock clock, @Value("${app.auth.session-ttl:PT12H}") Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    public Issued issue(Long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(ttl);
        sessions.put(hash(token), new Session(userId, expiresAt));
        return new Issued(token, expiresAt);
    }

    public Optional<Long> resolve(String token) {
        String key = hash(token);
        Session session = sessions.get(key);
        if (session == null) {
            return Optional.empty();
        }
        if (!session.expiresAt().isAfter(OffsetDateTime.now(clock))) {
            sessions.remove(key);
            return Optional.empty();
        }
        return Optional.of(session.userId());
    }

    public void revoke(String token) {
        sessions.remove(hash(token));
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Issued(String token, OffsetDateTime expiresAt) {
    }

    private record Session(Long userId, OffsetDateTime expiresAt) {
    }
}
