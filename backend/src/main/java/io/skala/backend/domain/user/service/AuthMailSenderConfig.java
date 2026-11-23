package io.skala.backend.domain.user.service;

import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 인증코드 메일 발송 방식 선택.
 * - spring.mail.host가 있으면 SMTP로 발송
 * - 없고 app.auth.dev-log-code=true(로컬 개발)면 코드를 로그에 출력
 * - 둘 다 아니면 발송하지 않고 503 PROCESSING_UNAVAILABLE
 */
@Slf4j
@Configuration
public class AuthMailSenderConfig {

    @Bean
    @ConditionalOnProperty(prefix = "spring.mail", name = "host")
    public AuthMailSender smtpAuthMailSender(JavaMailSender mailSender,
                                             @Value("${app.auth.mail-from:noreply@skala.local}") String from) {
        return (email, code) -> {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(email);
            message.setSubject("[손익관리 플랫폼] 로그인 인증코드");
            message.setText("인증코드 " + code + "\n3분 안에 입력해 주세요.");
            mailSender.send(message);
        };
    }

    @Bean
    @ConditionalOnMissingBean(AuthMailSender.class)
    public AuthMailSender fallbackAuthMailSender(@Value("${app.auth.dev-log-code:false}") boolean devLogCode) {
        if (devLogCode) {
            return (email, code) -> log.warn("[개발용] 인증코드 email={} code={}", email, code);
        }
        return (email, code) -> {
            throw new BusinessException(ErrorCode.PROCESSING_UNAVAILABLE, "인증 메일을 보낼 수 없습니다. 관리자에게 문의해 주세요.");
        };
    }
}
