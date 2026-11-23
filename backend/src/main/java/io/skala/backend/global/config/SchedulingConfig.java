package io.skala.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 예약 작업(@Scheduled) 실행. 작업마다 cron 설정을 "-"로 두면 꺼진다. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
