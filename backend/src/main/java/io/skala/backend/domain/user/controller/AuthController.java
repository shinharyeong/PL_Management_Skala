package io.skala.backend.domain.user.controller;

import io.skala.backend.domain.user.dto.AuthCodeRequest;
import io.skala.backend.domain.user.dto.AuthCodeResponse;
import io.skala.backend.domain.user.dto.LoginRequest;
import io.skala.backend.domain.user.dto.LoginResponse;
import io.skala.backend.domain.user.service.AuthService;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.BearerTokenAuthenticationFilter;
import io.skala.backend.global.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AUTH-01~03. 로그인 전 요청(인증코드·로그인)은 사용자를 알 수 없어 Idempotency-Key를 받지 않는다.
 * 같은 요청을 다시 보내도 재전송 대기(429)·코드 1회 사용으로 중복 효과가 없다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    @PostMapping("/codes")
    public ResponseEntity<AuthCodeResponse> requestCode(@Valid @RequestBody AuthCodeRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(authService.requestCode(request.email()));
    }

    @PostMapping("/sessions")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.login(request.email(), request.code()));
    }

    @DeleteMapping("/session")
    public ResponseEntity<String> logout(@RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                         HttpServletRequest request) {
        AuthenticatedUser user = currentUser.get();
        String token = (String) request.getAttribute(BearerTokenAuthenticationFilter.TOKEN_ATTRIBUTE);
        return idempotency.execute(user.id(), "DELETE /api/v1/auth/session", idempotencyKey, null, () -> {
            authService.logout(token);
            return ResponseEntity.noContent().build();
        });
    }
}
