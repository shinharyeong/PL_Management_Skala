package io.skala.backend.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.support.PostgresIntegrationTest;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class IdempotentCommandExecutorTest extends PostgresIntegrationTest {

    @Autowired
    IdempotentCommandExecutor executor;

    @Test
    void sameKeyRunsOnceAndReplaysFirstResponse() {
        String key = UUID.randomUUID().toString();
        AtomicInteger runs = new AtomicInteger();
        var first = executor.execute(1L, "POST /test", key, Map.of("a", 1), () -> {
            runs.incrementAndGet();
            return ResponseEntity.created(URI.create("/api/v1/test/7")).body(Map.of("id", "7"));
        });
        var second = executor.execute(1L, "POST /test", key, Map.of("a", 1), () -> {
            runs.incrementAndGet();
            return ResponseEntity.ok(Map.of("id", "8"));
        });
        assertThat(runs).hasValue(1);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getBody()).isEqualTo(first.getBody()).contains("\"7\"");
        assertThat(second.getHeaders().getFirst(IdempotentCommandExecutor.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(second.getHeaders().getLocation()).hasToString("/api/v1/test/7");
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        String key = UUID.randomUUID().toString();
        executor.execute(1L, "POST /test", key, Map.of("a", 1), () -> ResponseEntity.ok(Map.of()));
        assertThatThrownBy(() -> executor.execute(1L, "POST /test", key, Map.of("a", 2), () -> ResponseEntity.ok(Map.of())))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void failedCommandLeavesNoRecord() {
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> executor.execute(1L, "POST /test", key, Map.of(), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        var retry = executor.execute(1L, "POST /test", key, Map.of(), () -> ResponseEntity.ok(Map.of("ok", true)));
        assertThat(retry.getHeaders().getFirst(IdempotentCommandExecutor.REPLAYED_HEADER)).isNull();
    }

    @Test
    void invalidKeyIsRejected() {
        assertThatThrownBy(() -> executor.execute(1L, "POST /test", "x", Map.of(), () -> ResponseEntity.ok().build()))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("INVALID_REQUEST");
    }
}
