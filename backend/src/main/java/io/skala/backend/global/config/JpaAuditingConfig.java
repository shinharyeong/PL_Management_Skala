package io.skala.backend.global.config;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** created_at·updated_at을 UTC OffsetDateTime으로 자동 기록한다. */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "utcDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public DateTimeProvider utcDateTimeProvider() {
        return () -> Optional.of(OffsetDateTime.now(ZoneOffset.UTC));
    }
}
