package io.skala.backend.domain.user.service;

import io.skala.backend.domain.user.dto.AuthCodeResponse;
import io.skala.backend.domain.user.dto.LoginResponse;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.exception.AuthErrorCode;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.global.security.SessionTokenStore;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 이메일 인증코드 로그인(AUTH-01·02·03). */
@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final AuthCodeStore codeStore;
    private final AuthMailSender mailSender;
    private final SessionTokenStore sessionTokenStore;
    private final MeService meService;
    private final List<String> allowedDomains;

    public AuthService(AppUserRepository userRepository, AuthCodeStore codeStore, AuthMailSender mailSender,
                       SessionTokenStore sessionTokenStore, MeService meService,
                       @Value("${app.auth.allowed-domains:sk.com,sktelecom.com}") String allowedDomains) {
        this.userRepository = userRepository;
        this.codeStore = codeStore;
        this.mailSender = mailSender;
        this.sessionTokenStore = sessionTokenStore;
        this.meService = meService;
        this.allowedDomains = Arrays.stream(allowedDomains.split(",")).map(String::trim)
                .map(domain -> domain.toLowerCase(Locale.ROOT)).filter(domain -> !domain.isEmpty()).toList();
    }

    /** AUTH-01: 등록된 사내 계정에만 인증코드를 보낸다. */
    @Transactional(readOnly = true)
    public AuthCodeResponse requestCode(String email) {
        String normalized = AuthCodeStore.normalize(email);
        findActiveUser(normalized);
        AuthCodeStore.Issued issued = codeStore.issue(normalized);
        mailSender.sendCode(normalized, issued.code());
        return new AuthCodeResponse(normalized, issued.expiresAt(), issued.resendAvailableAt());
    }

    /** AUTH-02: 코드가 맞으면 세션 토큰을 발급한다. */
    @Transactional(readOnly = true)
    public LoginResponse login(String email, String code) {
        String normalized = AuthCodeStore.normalize(email);
        codeStore.verify(normalized, code);
        AppUser user = findActiveUser(normalized);
        SessionTokenStore.Issued issued = sessionTokenStore.issue(user.getId());
        return new LoginResponse(issued.token(), issued.expiresAt(), meService.me(user.getId()));
    }

    /** AUTH-03: 세션 토큰을 무효로 한다. */
    public void logout(String token) {
        if (token != null) {
            sessionTokenStore.revoke(token);
        }
    }

    private AppUser findActiveUser(String email) {
        int at = email.lastIndexOf('@');
        if (at < 1 || !allowedDomains.contains(email.substring(at + 1))) {
            throw AuthErrorCode.UNREGISTERED_EMAIL.exception();
        }
        return userRepository.findByEmailIgnoreCase(email)
                .filter(user -> Boolean.TRUE.equals(user.getActive()))
                .orElseThrow(AuthErrorCode.UNREGISTERED_EMAIL::exception);
    }
}
