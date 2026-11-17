package io.skala.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.demo.DemoAccounts;
import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 가상 환경과 메모리 HTTP로 토큰·역할·인증 오류의 경계를 확인한다. */
class LocalBearerAuthenticationFilterTest {

    /**
     * 각 검증이 설정한 인증 문맥을 해제한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: 없음.
     * Side Effects: 현재 스레드의 테스트 인증 문맥을 비운다.
     */
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 토큰 설정 이름과 관계없이 DB 역할만 사용하고 비밀값을 저장하지 않는지 확인한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: 필터 처리 실패는 Exception, 계약 불일치는 AssertionError.
     * Side Effects: 메모리 인증 문맥·HTTP 객체만 변경한다.
     */
    @Test
    void authenticatesEmailWithDatabaseRoleWithoutRetainingToken() throws Exception {
        String token = UUID.randomUUID().toString();
        ActorResolver actors = mock(ActorResolver.class);
        when(actors.resolveEmail("manager@example.test")).thenReturn(new Actor("2", "manager@example.test",
                "테스트 담당", "ORG_MANAGER", new Organization("1", "TEST_A", "테스트 조직")));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.local-auth.planner-email", "manager@example.test")
                .withProperty("app.local-auth.planner-token", token);
        var filter = new LocalBearerAuthenticationFilter(environment, actors,
                new SecurityProblemHandler(new ObjectMapper()));
        var request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", "Bearer " + token);
        AtomicBoolean continued = new AtomicBoolean();
        FilterChain chain = (incoming, outgoing) -> continued.set(true);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(continued).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo("manager@example.test");
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_ORG_MANAGER");
    }

    /** demo 환경 토큰은 이전 설정 이메일을 조회하지 않고 고정 F10 담당자만 인증한다.
     * Args: 없음. Returns: 없음. Raises: 필터 처리 실패는 Exception, 계약 불일치는 AssertionError.
     * Side Effects: 메모리 인증 문맥·HTTP 객체만 변경한다.
     */
    @Test
    void demoOrganizationTokenUsesFixedBusinessActorInsteadOfLegacyEmail() throws Exception {
        String token = UUID.randomUUID().toString();
        ActorResolver actors = mock(ActorResolver.class);
        DemoAccounts demo = mock(DemoAccounts.class);
        Actor fixed = new Actor("10", "demo-f10@example.test", "사업부1 담당자 (데모)", "ORG_MANAGER",
                new Organization("10", "F10", "사업부1"));
        when(demo.actorForOrganizationToken(token)).thenReturn(fixed);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.local-auth.organization-email", "legacy@example.test")
                .withProperty("app.local-auth.organization-token", token);
        var filter = new LocalBearerAuthenticationFilter(environment, actors,
                new SecurityProblemHandler(new ObjectMapper()), demo);
        var request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {});
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(fixed);
        verifyNoInteractions(actors);
    }

    /** demo 계정 검증에 없는 토큰을 legacy 이메일 인증으로 우회시키지 않는다.
     * Args: 없음. Returns: 없음. Raises: 필터 처리 실패는 Exception, 인증 우회는 AssertionError.
     * Side Effects: 메모리 HTTP 응답과 인증 문맥만 변경한다.
     */
    @Test
    void demoTokenWithoutFixedActorCannotFallBackToConfiguredEmail() throws Exception {
        String token = UUID.randomUUID().toString();
        ActorResolver actors = mock(ActorResolver.class);
        DemoAccounts demo = mock(DemoAccounts.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.local-auth.organization-email", "legacy@example.test")
                .withProperty("app.local-auth.organization-token", token);
        var filter = new LocalBearerAuthenticationFilter(environment, actors,
                new SecurityProblemHandler(new ObjectMapper()), demo);
        var request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("고정 사업부 계정이 없는 토큰이 인증됐습니다.");
        });
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(actors);
    }

    /**
     * 알 수 없는 토큰을 DB 조회 없이 안전한 401로 거부하는지 확인한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: 응답 처리 실패는 Exception, 정보 노출이나 계약 불일치는 AssertionError.
     * Side Effects: 메모리 HTTP 응답만 기록한다.
     */
    @Test
    void rejectsUnknownTokenWithSanitizedProblemDetail() throws Exception {
        String token = UUID.randomUUID().toString();
        ActorResolver actors = mock(ActorResolver.class);
        var filter = new LocalBearerAuthenticationFilter(new MockEnvironment(), actors,
                new SecurityProblemHandler(new ObjectMapper()));
        var request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();
        filter.doFilter(request, response, (incoming, outgoing) -> continued.set(true));
        var body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(continued).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(body.path("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.path("requestId").asText()).isNotBlank();
        assertThat(response.getContentAsString()).doesNotContain(token);
        verifyNoInteractions(actors);
    }

    /**
     * 올바른 토큰이어도 DB의 비활성 계정 판단을 우회하지 못하는지 확인한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: 필터 처리 실패는 Exception, 인증 우회는 AssertionError.
     * Side Effects: 메모리 HTTP 응답과 인증 문맥만 변경한다.
     */
    @Test
    void rejectsDatabaseAccountFailureAndClearsAuthentication() throws Exception {
        String token = UUID.randomUUID().toString();
        ActorResolver actors = mock(ActorResolver.class);
        when(actors.resolveEmail("disabled@example.test")).thenThrow(new BadCredentialsException("내부 진단"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.local-auth.organization-email", "disabled@example.test")
                .withProperty("app.local-auth.organization-token", token);
        var filter = new LocalBearerAuthenticationFilter(environment, actors,
                new SecurityProblemHandler(new ObjectMapper()));
        var request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("거부된 요청이 뒤 필터로 전달됐습니다.");
        });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain("내부 진단");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /**
     * 약한 토큰과 역할별로 재사용한 같은 토큰을 시작 시 거부하는지 확인한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: 설정이 허용되면 AssertionError.
     * Side Effects: 메모리 환경값만 사용하고 실제 환경을 수정하지 않는다.
     */
    @Test
    void rejectsWeakOrDuplicateConfiguredTokens() {
        String token = UUID.randomUUID().toString();
        ActorResolver actors = mock(ActorResolver.class);
        SecurityProblemHandler problems = new SecurityProblemHandler(new ObjectMapper());
        MockEnvironment weak = new MockEnvironment().withProperty("app.local-auth.planner-token", token.substring(0, 8));
        assertThatThrownBy(() -> new LocalBearerAuthenticationFilter(weak, actors, problems))
                .isInstanceOf(IllegalArgumentException.class);
        MockEnvironment duplicate = new MockEnvironment()
                .withProperty("app.local-auth.planner-token", token)
                .withProperty("app.local-auth.organization-token", token);
        assertThatThrownBy(() -> new LocalBearerAuthenticationFilter(duplicate, actors, problems))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(token);
    }

    /**
     * 여러 Authorization 헤더를 임의 선택하지 않고 거부하는지 확인한다.
     *
     * <p>Args: 없음.
     * Returns: 없음.
     * Raises: 필터 처리 실패는 Exception, 헤더가 허용되면 AssertionError.
     * Side Effects: 메모리 HTTP 객체만 변경한다.
     */
    @Test
    void rejectsMultipleAuthorizationHeaders() throws Exception {
        ActorResolver actors = mock(ActorResolver.class);
        var filter = new LocalBearerAuthenticationFilter(new MockEnvironment(), actors,
                new SecurityProblemHandler(new ObjectMapper()));
        var request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", "Bearer " + UUID.randomUUID());
        request.addHeader("Authorization", "Bearer " + UUID.randomUUID());
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("중복 인증 헤더가 허용됐습니다.");
        });
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(actors);
    }
}
