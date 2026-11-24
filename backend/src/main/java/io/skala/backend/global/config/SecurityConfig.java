package io.skala.backend.global.config;

import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.exception.ProblemResponseWriter;
import io.skala.backend.global.security.BearerTokenAuthenticationFilter;
import io.skala.backend.global.security.SessionTokenStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 세션 없이 Bearer 토큰으로 인증한다. 로그인 API·health 외에는 로그인이 필요하다.
 * 인증 실패는 401 UNAUTHENTICATED, 권한 부족은 403 FORBIDDEN을 Problem 형식으로 내려준다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SessionTokenStore sessionTokenStore,
                                                   AppUserRepository appUserRepository,
                                                   ProblemResponseWriter problemWriter) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/codes", "/api/v1/auth/sessions").permitAll()
                        // 쿠버네티스 프로브가 health/readiness·liveness를 인증 없이 부른다.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        // report-ai → backend 내부 보고(명세 12절). Ingress에 열지 않고, 컨트롤러가 X-Internal-Token을 확인한다.
                        .requestMatchers(HttpMethod.POST, "/internal/pdf-jobs/*/result").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, e) ->
                                problemWriter.write(request, response, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, e) ->
                                problemWriter.write(request, response, ErrorCode.FORBIDDEN)))
                .addFilterBefore(new BearerTokenAuthenticationFilter(sessionTokenStore, appUserRepository),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
