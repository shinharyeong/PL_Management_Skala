package io.skala.backend.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * API 요청에 검증된 추적 ID와 재무 응답 캐시 제한을 적용한다.
 * 상태는 요청 속성에만 두며 MDC·파일 로그·영구 저장소를 사용하지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class ApiRequestFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String CACHE_CONTROL = "private, no-store";
    private static final String REQUEST_ID_ATTRIBUTE = ApiRequestFilter.class.getName() + ".requestId";
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /**
     * 버전 API 경로에만 공통 헤더 처리를 적용한다.
     * Args: request는 현재 Servlet 요청이다.
     * Returns: /api/v1 및 그 하위 경로가 아니면 true.
     * Raises: 없음.
     * Side Effects: 없음. 요청 URI만 읽는다.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/api/v1") && !path.startsWith("/api/v1/");
    }

    /**
     * 응답 전 추적·캐시 헤더를 설정하고 다음 필터를 호출한다.
     * Args: request와 response는 HTTP 교환, filterChain은 나머지 처리 경로다.
     * Returns: 없음.
     * Raises: ServletException·IOException — 이후 필터·요청 처리의 오류를 전파한다.
     * Side Effects: 요청 속성과 응답 헤더를 변경하며 파일·로그를 쓰지 않는다.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader(REQUEST_ID_HEADER, requestId(request));
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
        filterChain.doFilter(request, response);
    }

    /**
     * 한 요청에서 동일하게 사용할 검증된 추적 ID를 구한다.
     * Args: request는 추적 속성과 X-Request-ID 헤더를 읽을 요청이다.
     * Returns: 단일 헤더의 1~64자 ASCII 식별자 또는 새 UUID 문자열.
     * Raises: 없음.
     * Side Effects: 최초 호출 때 요청 속성에 ID를 저장한다. 중복·잘못된 헤더는 재사용하지 않는다.
     */
    public static String requestId(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (existing instanceof String value && REQUEST_ID.matcher(value).matches()) {
            return value;
        }
        List<String> supplied = Collections.list(request.getHeaders(REQUEST_ID_HEADER));
        String value = supplied.size() == 1 && REQUEST_ID.matcher(supplied.getFirst()).matches()
                ? supplied.getFirst() : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, value);
        return value;
    }
}
