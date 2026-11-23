package io.skala.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.skala.backend.domain.user.service.AuthCodeStore;
import io.skala.backend.global.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AuthCodeStoreTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T00:00:00Z"));
    private final AuthCodeStore store = new AuthCodeStore(clock);

    @Test
    void issuesSixDigitCodeAndAcceptsItOnce() {
        String code = store.issue("Kim.Plan@SK.com").code();
        assertThat(code).matches("\\d{6}");
        assertThatCode(() -> store.verify("kim.plan@sk.com", code)).doesNotThrowAnyException();
        assertReason(() -> store.verify("kim.plan@sk.com", code), "MISMATCH");
    }

    @Test
    void resendIsBlockedForOneMinute() {
        store.issue("a@sk.com");
        clock.advance(Duration.ofSeconds(59));
        assertThatThrownBy(() -> store.issue("a@sk.com")).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CODE_RESEND_WAIT");
        clock.advance(Duration.ofSeconds(1));
        assertThat(store.issue("a@sk.com").code()).matches("\\d{6}");
    }

    @Test
    void codeExpiresAfterThreeMinutes() {
        String code = store.issue("b@sk.com").code();
        clock.advance(Duration.ofMinutes(3));
        assertReason(() -> store.verify("b@sk.com", code), "EXPIRED");
    }

    @Test
    void fiveWrongAttemptsLockTheCode() {
        String code = store.issue("c@sk.com").code();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            assertReason(() -> store.verify("c@sk.com", wrong), "MISMATCH");
        }
        assertReason(() -> store.verify("c@sk.com", code), "TOO_MANY_ATTEMPTS");
    }

    private void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo("INVALID_AUTH_CODE");
            assertThat(e.getErrors()).extracting(BusinessException.FieldError::code).containsExactly(reason);
        });
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
