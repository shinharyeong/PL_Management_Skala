package io.skala.backend.security;

import io.skala.backend.demo.DemoAccounts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** local 보안 체인에서만 생성되는 개발용 Bearer 인증 필터다. */
final class LocalBearerAuthenticationFilter extends OncePerRequestFilter {

    private final ActorResolver actors;
    private final SecurityProblemHandler problems;
    private final DemoAccounts demoAccounts;
    private final String plannerEmail;
    private final String organizationEmail;
    private final byte[] plannerDigest;
    private final byte[] organizationDigest;

    /**
     * 환경 설정의 개발 인증 정보를 검사하고 토큰 다이제스트만 보관한다.
     *
     * <p>Args: environment는 app.local-auth 설정, actors는 DB 사용자 조회,
     * problems는 공통 인증 오류 출력기다. 이메일이나 토큰이 없으면 해당 자격은 비활성이다.
     * Returns: 생성자이므로 반환값이 없다.
     * Raises: 제공된 토큰이 32자 미만·공백 포함·서로 같으면 IllegalArgumentException.
     * Side Effects: 환경 설정을 읽고 메모리에서 해시를 계산한다. 토큰을 로깅하지 않는다.
     */
    LocalBearerAuthenticationFilter(Environment environment, ActorResolver actors,
            SecurityProblemHandler problems) {
        this(environment, actors, problems, null);
    }

    /** 기존 local 자격과 명시적으로 켠 demo 사업부 자격을 함께 검증하도록 구성한다.
     * Args: environment 개발 인증 설정, actors DB 사용자 조회, problems 오류 처리,
     * demoAccounts local+demo에만 존재하는 추가 자격 또는 null.
     * Returns: 없음. Raises: IllegalArgumentException 잘못된 기존 토큰.
     * Side Effects: 환경 읽기·메모리 다이제스트 생성; DB 변경·토큰 로깅 없음.
     */
    LocalBearerAuthenticationFilter(Environment environment, ActorResolver actors,
            SecurityProblemHandler problems, DemoAccounts demoAccounts) {
        this.actors = actors;
        this.problems = problems;
        this.demoAccounts = demoAccounts;
        plannerEmail = environment.getProperty("app.local-auth.planner-email");
        organizationEmail = environment.getProperty("app.local-auth.organization-email");
        byte[] plannerToken = configuredDigest(environment.getProperty("app.local-auth.planner-token"));
        byte[] organizationToken = configuredDigest(environment.getProperty("app.local-auth.organization-token"));
        if (plannerToken != null && organizationToken != null
                && MessageDigest.isEqual(plannerToken, organizationToken)) {
            throw new IllegalArgumentException("개발 인증 토큰은 서로 달라야 합니다.");
        }
        plannerDigest = plannerEmail == null || plannerEmail.isBlank() ? null : plannerToken;
        organizationDigest = organizationEmail == null || organizationEmail.isBlank() ? null : organizationToken;
    }

    /**
     * Bearer를 검증하고 DB가 확인한 사용자 이메일과 역할로 현재 요청을 인증한다.
     *
     * <p>Args: request·response는 현재 HTTP 교환, chain은 뒤의 보안 필터 체인이다.
     * Returns: 인증 헤더가 없으면 익명 상태로 진행하고 실패하면 401 응답으로 종료한다.
     * Raises: 뒤 필터의 ServletException·IOException 및 DB 장애는 그대로 전파한다.
     * Side Effects: SecurityContext를 설정한다. 추가 사업부는 고정 조직 Actor를 principal로 전달하며 토큰은 보관하지 않는다.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Enumeration<String> headers = request.getHeaders("Authorization");
        if (!headers.hasMoreElements()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            String header = headers.nextElement();
            if (headers.hasMoreElements() || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                throw new BadCredentialsException("인증 정보를 확인할 수 없습니다.");
            }
            String candidate = header.substring(7);
            if (candidate.isBlank() || candidate.chars().anyMatch(Character::isWhitespace)) {
                throw new BadCredentialsException("인증 정보를 확인할 수 없습니다.");
            }
            byte[] digest = digest(candidate);
            String email = null;
            if (plannerDigest != null && MessageDigest.isEqual(plannerDigest, digest)) {
                email = plannerEmail;
            }
            if (organizationDigest != null && MessageDigest.isEqual(organizationDigest, digest)) {
                email = organizationEmail;
            }
            Actor selectedActor = email == null && demoAccounts != null
                    ? demoAccounts.actorForOrganizationToken(candidate) : null;
            if (email == null && selectedActor == null) {
                throw new BadCredentialsException("인증 정보를 확인할 수 없습니다.");
            }
            Actor actor = selectedActor == null ? actors.resolveEmail(email) : selectedActor;
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    selectedActor == null ? actor.email() : selectedActor,
                    null, List.of(new SimpleGrantedAuthority("ROLE_" + actor.role())));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            problems.commence(request, response, exception);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 제공된 개발 토큰의 최소 형식을 검사한다.
     *
     * <p>Args: token은 환경 설정에서 읽은 값이다.
     * Returns: 없거나 빈 값이면 null, 유효하면 SHA-256 다이제스트.
     * Raises: 32자 미만 또는 공백이 섞이면 IllegalArgumentException.
     * Side Effects: 메모리에서만 검사하며 값 자체를 오류에 넣지 않는다.
     */
    private static byte[] configuredDigest(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (token.length() < 32 || token.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("개발 인증 토큰은 공백 없는 32자 이상이어야 합니다.");
        }
        return digest(token);
    }

    /**
     * 토큰 비교에 사용할 일정 길이의 다이제스트를 만든다.
     *
     * <p>Args: token은 비교 대상 문자열이다.
     * Returns: UTF-8 입력의 SHA-256 바이트 배열.
     * Raises: JVM의 필수 SHA-256 구현이 없으면 IllegalStateException.
     * Side Effects: 메모리에서 해시만 계산하고 원본을 보관하지 않는다.
     */
    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("필수 인증 해시를 사용할 수 없습니다.", exception);
        }
    }
}
