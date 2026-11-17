package io.skala.backend.common;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 업무·Spring MVC·예상 밖 오류를 공개용 ProblemDetail로 통합한다.
 * 프레임워크·예상 밖 예외의 원문 메시지와 내부 스택은 응답이나 파일 로그로 복사하지 않는다.
 */
@RestControllerAdvice
public final class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * 검증된 업무 오류의 상태·코드·공개 설명을 응답한다.
     * Args: exception은 공개용 ApiException, request는 현재 요청이다.
     * Returns: application/problem+json 오류 응답.
     * Raises: 없음.
     * Side Effects: 필요하면 요청 추적 속성을 만들며 파일·로그를 쓰지 않는다.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException exception,
                                                    HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        if ("REQUEST_IN_PROGRESS".equals(exception.code())) {
            headers.set(HttpHeaders.RETRY_AFTER, "1");
        }
        return problem(exception.status(), exception.code(), exception.getMessage(),
                exception.errors(), headers, request);
    }

    /**
     * 컨트롤러·서비스에서 발생한 인증·권한 오류를 고정 설명으로 응답한다.
     * Args: exception은 Spring Security 예외, request는 현재 요청이다.
     * Returns: 인증 오류는 401, 권한 오류는 403의 안전한 ProblemDetail.
     * Raises: 없음.
     * Side Effects: 추적 속성 외 없음. 자격 증명과 원본 예외 메시지를 기록하지 않는다.
     */
    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    public ResponseEntity<Object> handleSecurityException(Exception exception,
                                                         HttpServletRequest request) {
        boolean authenticationFailure = exception instanceof AuthenticationException;
        return problem(authenticationFailure ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN,
                authenticationFailure ? "UNAUTHENTICATED" : "FORBIDDEN",
                authenticationFailure ? "인증이 필요합니다." : "현재 권한으로 접근할 수 없습니다.",
                List.of(), new HttpHeaders(), request);
    }

    /**
     * 해석 불가능한 multipart 요청을 입력 형식 오류로 반환한다.
     * Args: exception은 파일 경로 등을 포함할 수 있는 원본 예외, request는 현재 요청이다.
     * Returns: 원문을 포함하지 않는 400 오류 응답. 크기 초과는 MVC의 413 처리 경로를 따른다.
     * Raises: 없음.
     * Side Effects: 추적 속성 외 없음. 예외 원문을 기록하지 않는다.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Object> handleMultipartException(MultipartException exception,
                                                          HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "파일 업로드 요청 형식을 확인해 주세요.", List.of(), new HttpHeaders(), request);
    }

    /**
     * 예상 밖 서버 오류의 내부 내용을 제거한다.
     * Args: exception은 공개하지 않을 원본 예외, request는 현재 요청이다.
     * Returns: 일반 설명과 추적 ID만 포함하는 500 오류 응답.
     * Raises: 없음.
     * Side Effects: 추적 속성 외 없음. 스택·원본 경로·파일 로그를 만들지 않는다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception,
                                                           HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "요청 처리 중 오류가 발생했습니다.", List.of(), new HttpHeaders(), request);
    }

    /**
     * Spring MVC의 입력·라우팅·업로드 오류를 안전한 공통 응답으로 바꾼다.
     * Args: exception은 MVC 예외, body는 사용하지 않을 기본 본문, headers는 프로토콜 헤더,
     * statusCode는 MVC가 정한 HTTP 상태, request는 ServletWebRequest다.
     * Returns: 안전한 오류 응답. 응답이 이미 전송됐으면 null.
     * Raises: 없음. 기본 예외 메시지는 응답에 사용하지 않는다.
     * Side Effects: 요청 추적 속성을 만들 수 있으며 파일·로그를 쓰지 않는다.
     */
    @Override
    @Nullable
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception,
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode,
            WebRequest request) {
        ServletWebRequest servletRequest = (ServletWebRequest) request;
        if (servletRequest.getResponse() != null && servletRequest.getResponse().isCommitted()) {
            return null;
        }
        List<ApiException.FieldError> errors = exception instanceof BindException binding
                ? binding.getFieldErrors().stream().limit(100)
                        .map(error -> new ApiException.FieldError(error.getField(), "INVALID_VALUE"))
                        .distinct().toList()
                : List.of();
        return problem(statusCode, defaultCode(statusCode), defaultMessage(statusCode),
                errors, headers, servletRequest.getRequest());
    }

    /**
     * ProblemDetail와 추적·캐시 헤더를 일관되게 구성한다.
     * Args: status는 상태, code는 공개 코드, message는 공개 설명, errors는 필드 오류,
     * sourceHeaders는 유지할 MVC 헤더, request는 현재 요청이다.
     * Returns: 확장 필드 code·requestId·errors를 가진 오류 응답.
     * Raises: 없음. 요청 URI 문법이 잘못되면 일반 API 경로를 사용한다.
     * Side Effects: 요청 추적 속성 외 없음. 입력 헤더와 오류 목록은 변경하지 않는다.
     */
    private ResponseEntity<Object> problem(HttpStatusCode status, String code, String message,
            List<ApiException.FieldError> errors, HttpHeaders sourceHeaders,
            HttpServletRequest request) {
        String requestId = ApiRequestFilter.requestId(request);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setInstance(instance(request));
        detail.setProperty("code", code);
        detail.setProperty("requestId", requestId);
        detail.setProperty("errors", errors);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(sourceHeaders);
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.set(ApiRequestFilter.REQUEST_ID_HEADER, requestId);
        headers.set(HttpHeaders.CACHE_CONTROL, ApiRequestFilter.CACHE_CONTROL);
        return new ResponseEntity<>(detail, headers, status);
    }

    /**
     * 쿼리 문자열을 제외한 요청 경로를 오류 발생 위치로 변환한다.
     * Args: request는 URI를 읽을 Servlet 요청이다.
     * Returns: 요청 경로 URI 또는 문법 오류 시 /api/v1.
     * Raises: 없음. IllegalArgumentException은 일반 경로로 대체한다.
     * Side Effects: 없음.
     */
    private URI instance(HttpServletRequest request) {
        try {
            return URI.create(request.getRequestURI());
        } catch (IllegalArgumentException exception) {
            return URI.create("/api/v1");
        }
    }

    /**
     * 프레임워크 상태를 입력값이 없는 고정 오류 코드로 연결한다.
     * Args: status는 Spring MVC가 결정한 HTTP 상태다.
     * Returns: 해당 상태의 공개 코드. 세부 업무 코드는 ApiException에서 지정한다.
     * Raises: 없음.
     * Side Effects: 없음.
     */
    private String defaultCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 409 -> "CONFLICT";
            case 413 -> "FILE_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 422 -> "INVALID_REQUEST";
            case 429 -> "RATE_LIMITED";
            case 503 -> "PROCESSING_UNAVAILABLE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "INVALID_REQUEST";
        };
    }

    /**
     * 프레임워크의 원문을 사용하지 않고 상태별 공개 설명을 고른다.
     * Args: status는 응답 HTTP 상태다.
     * Returns: 값·파일 경로·스택을 포함하지 않는 고정 한국어 설명.
     * Raises: 없음.
     * Side Effects: 없음.
     */
    private String defaultMessage(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "요청 형식과 입력값을 확인해 주세요.";
            case 404 -> "요청한 자료를 찾을 수 없습니다.";
            case 405 -> "지원하지 않는 요청 메서드입니다.";
            case 406 -> "지원하지 않는 응답 형식입니다.";
            case 413 -> "업로드 가능한 파일 크기를 초과했습니다.";
            case 415 -> "지원하지 않는 요청 본문 형식입니다.";
            case 503 -> "현재 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.";
            default -> status.is5xxServerError()
                    ? "요청 처리 중 오류가 발생했습니다." : "요청을 처리할 수 없습니다.";
        };
    }
}
