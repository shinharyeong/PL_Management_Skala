package io.skala.backend.domain.user.service;

/** 로그인 인증코드 메일 발송 포트. SMTP 설정이 있으면 SMTP, 없으면 설정에 따라 로그 출력 또는 발송 불가. */
public interface AuthMailSender {

    void sendCode(String email, String code);
}
