package io.skala.backend.domain.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.skala.backend.domain.user.controller.AuthController;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.service.AuthCodeStore;
import io.skala.backend.domain.user.service.AuthMailSender;
import io.skala.backend.domain.user.service.AuthService;
import io.skala.backend.domain.user.service.MeService;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.BearerTokenAuthenticationFilter;
import io.skala.backend.global.security.CurrentUser;
import io.skala.backend.global.security.SessionTokenStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 실제 메모리 세션을 사용해 로그아웃이 DB나 멱등 기록 없이 동작하는지 검증한다. */
class AuthLogoutTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AuthCodeStore codes = mock(AuthCodeStore.class);
    private final AuthMailSender mail = mock(AuthMailSender.class);
    private final MeService profiles = mock(MeService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final SessionTokenStore sessions = new SessionTokenStore(
            Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC), Duration.ofHours(1));
    private final AuthService auth = new AuthService(users, codes, mail, sessions, profiles, "sk.com");
    private final AuthController controller = new AuthController(auth, currentUser);

    /** 목적: 멱등 키 없는 기존 DELETE 경로가 현재 토큰만 실제 폐기하고 DB를 사용하지 않음을 검증한다. Args: 없음. Returns: 없음. Raises: HTTP·검증 오류. Side Effects: 테스트 메모리 세션만 생성·삭제. */
    @Test
    void logoutWithoutIdempotencyKeyRevokesOnlyCurrentSessionWithoutDatabaseAccess() throws Exception {
        when(currentUser.get()).thenReturn(new AuthenticatedUser(7L, UserRole.PLANNER, null));
        String token = sessions.issue(7L).token();
        String another = sessions.issue(7L).token();
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(delete("/api/v1/auth/session").requestAttr(BearerTokenAuthenticationFilter.TOKEN_ATTRIBUTE, token))
                .andExpect(status().isNoContent());
        assertThat(sessions.resolve(token)).isEmpty();
        assertThat(sessions.resolve(another)).contains(7L);
        verify(currentUser).get();
        verifyNoInteractions(users, codes, mail, profiles);
    }

    /** 목적: 기존 클라이언트의 멱등 헤더를 해석·저장하지 않고 호환함을 검증한다. Args: 없음. Returns: 없음. Raises: HTTP·검증 오류. Side Effects: 테스트 메모리 세션만 생성·삭제. */
    @Test
    void logoutIgnoresLegacyIdempotencyHeaderWithoutDatabaseAccess() throws Exception {
        when(currentUser.get()).thenReturn(new AuthenticatedUser(7L, UserRole.PLANNER, null));
        String token = sessions.issue(7L).token();
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(delete("/api/v1/auth/session").header("Idempotency-Key", "legacy")
                        .requestAttr(BearerTokenAuthenticationFilter.TOKEN_ATTRIBUTE, token))
                .andExpect(status().isNoContent());
        assertThat(sessions.resolve(token)).isEmpty();
        verifyNoInteractions(users, codes, mail, profiles);
    }

    /** 목적: 인증 확인에 실패한 요청은 토큰 폐기에 도달하지 못함을 검증한다. Args: 없음. Returns: 없음. Raises: 검증 오류. Side Effects: 테스트 메모리 세션 생성만. */
    @Test
    void unauthenticatedRequestCannotRevokeSession() {
        when(currentUser.get()).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));
        String token = sessions.issue(7L).token();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenAuthenticationFilter.TOKEN_ATTRIBUTE, token);
        assertThatThrownBy(() -> controller.logout(request)).isInstanceOf(BusinessException.class);
        assertThat(sessions.resolve(token)).contains(7L);
        verifyNoInteractions(users, codes, mail, profiles);
    }
}
