package io.skala.backend.domain.submission.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 파싱 작업 정리(ParsingJobDispatcher#sweep)를 위한 예약 실행.
 * 다른 도메인도 예약 작업이 필요해지면 global 설정으로 옮긴다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.parser.sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class ParsingScheduleConfig {
}
