package io.skala.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** 보안 필터 단계의 인증·권한 오류를 비밀값 없는 ProblemDetail로 응답한다. */
@Component
public final class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    /**
     * 공통 JSON 직렬화기를 연결한다.
     *
     * <p>Args: mapper는 Spring의 ProblemDetail 설정을 적용한 ObjectMapper다.
     * Returns: 생성자이므로 반환값이 없다.
     * Raises: 없음.
     * Side Effects: 전용 직렬화기를 메모리에 복사하며 공통 mapper는 변경하지 않는다.
     */
    public SecurityProblemHandler(ObjectMapper mapper) {
        this.mapper = mapper.copy().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
    }

    /**
     * 인증이 없거나 실패한 요청에 고정된 401 오류를 쓴다.
     *
     * <p>Args: request·response는 현재 HTTP 교환, exception은 공개하지 않을 인증 예외다.
     * Returns: 반환값이 없다.
     * Raises: 응답 쓰기가 실패하면 IOException.
     * Side Effects: 응답 상태·헤더·본문을 기록하며 인증값이나 예외 내용을 로깅하지 않는다.
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "인증이 필요합니다.");
    }

    /**
     * 인증된 사용자의 권한 부족을 고정된 403 오류로 쓴다.
     *
     * <p>Args: request·response는 현재 HTTP 교환, exception은 공개하지 않을 권한 예외다.
     * Returns: 반환값이 없다.
     * Raises: 응답 쓰기가 실패하면 IOException.
     * Side Effects: 응답 상태·헤더·본문을 기록한다.
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "요청한 작업을 수행할 권한이 없습니다.");
    }

    /**
     * 보안 오류의 공개 필드만 JSON으로 출력한다.
     *
     * <p>Args: request·response는 HTTP 교환, status·code·detail은 서버의 고정 오류 표현이다.
     * Returns: 이미 응답이 확정됐으면 쓰지 않고 반환한다.
     * Raises: JSON 출력 실패는 IOException.
     * Side Effects: 공통 필터의 안전한 requestId를 재사용하고 응답을 기록한다.
     */
    private void write(HttpServletRequest request, HttpServletResponse response,
            HttpStatus status, String code, String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("requestId", ApiRequestFilter.requestId(request));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", ApiRequestFilter.CACHE_CONTROL);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
