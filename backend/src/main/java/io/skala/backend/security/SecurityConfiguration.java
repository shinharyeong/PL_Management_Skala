package io.skala.backend.security;

import io.skala.backend.demo.DemoAccounts;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/** 로그인 제공자 확정 전 기본 거부와 local 전용 개발 인증을 분리한다. */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    /**
     * 설정되지 않은 로그인 방식과 자동 생성 기본 계정의 사용을 막는다.
     *
     * <p>Args: 없음.
     * Returns: 항상 인증을 거부하는 AuthenticationManager.
     * Raises: 반환된 관리자를 호출하면 BadCredentialsException.
     * Side Effects: 기본 사용자 자동 구성을 대체하며 토큰·비밀번호를 생성하지 않는다.
     */
    @Bean
    AuthenticationManager authenticationManager() {
        return authentication -> {
            throw new BadCredentialsException("지원하지 않는 인증 방식입니다.");
        };
    }

    /**
     * local 이외 환경에서는 상태 확인을 제외한 요청을 모두 차단한다.
     *
     * <p>Args: http는 Spring 보안 빌더, problems는 보안 오류 출력기다.
     * Returns: 익명 /actuator/health만 허용하는 보안 체인.
     * Raises: 보안 체인 구성 실패는 Exception.
     * Side Effects: HTTP 보안 규칙을 등록하며 파일·DB를 변경하지 않는다.
     */
    @Bean
    @Profile("!local")
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http,
            SecurityProblemHandler problems) throws Exception {
        configureStatelessBoundary(http, problems);
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }

    /**
     * local 프로필에서만 환경에 설정된 Bearer 인증을 활성화한다.
     *
     * <p>Args: http는 보안 빌더, environment는 개발 토큰 설정,
     * actors는 DB 사용자 조회, problems는 보안 오류 출력기, demoAccounts는 local+demo 전용 추가 자격 공급자다.
     * Returns: 상태 확인과 demo를 함께 켠 경우의 POST /api/dev/session만 익명 허용하고,
     * 나머지는 개발 Bearer를 요구하는 보안 체인.
     * Raises: 잘못된 토큰 설정 또는 체인 구성 실패는 Exception.
     * Side Effects: 필터를 보안 체인에만 등록해 Servlet 필터로 중복 실행하지 않는다.
     */
    @Bean
    @Profile("local")
    SecurityFilterChain localSecurityFilterChain(HttpSecurity http, Environment environment,
            ActorResolver actors, SecurityProblemHandler problems, ObjectProvider<DemoAccounts> demoAccounts,
            ObjectProvider<EmailCodeSessions> emailSessions) throws Exception {
        configureStatelessBoundary(http, problems);
        http.addFilterBefore(new LocalBearerAuthenticationFilter(environment, actors, problems, demoAccounts.getIfAvailable(), emailSessions.getIfAvailable()),
                AnonymousAuthenticationFilter.class);
        http.authorizeHttpRequests(authorize -> {
            authorize.requestMatchers("/actuator/health").permitAll();
            if (environment.acceptsProfiles(Profiles.of("demo"))) {
                authorize.requestMatchers(HttpMethod.POST, "/api/dev/session").permitAll();
                authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/codes", "/api/v1/auth/sessions").permitAll();
            }
            authorize.anyRequest().authenticated();
        });
        return http.build();
    }

    /**
     * 세션·폼·Basic 인증을 끄고 보안 오류 계약을 공통 적용한다.
     *
     * <p>Args: http는 현재 체인의 빌더, problems는 고정 ProblemDetail 출력기다.
     * Returns: 반환값이 없다.
     * Raises: 빌더 설정 실패는 Exception.
     * Side Effects: 인증 상태를 세션에 저장하지 않도록 구성한다.
     * 자동 전송 쿠키 인증을 사용하지 않는 Bearer 전용 경계이므로 CSRF를 비활성화한다.
     * 향후 쿠키 로그인을 도입할 때 이 선택을 재검토해야 한다.
     */
    private void configureStatelessBoundary(HttpSecurity http,
            SecurityProblemHandler problems) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.requireExplicitSave(true))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
    }
}
