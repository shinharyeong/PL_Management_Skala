package io.skala.backend.global.exception;

import io.skala.backend.global.config.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 오류를 RFC 9457 Problem Details + code·requestId·errors 형식으로 내려준다(docs/api/api-spec.md 0-3절).
 * 예상 밖 예외의 원문 메시지·스택은 응답에 넣지 않는다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusiness(BusinessException e, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        if (ErrorCode.REQUEST_IN_PROGRESS.code().equals(e.getCode())) {
            headers.set(HttpHeaders.RETRY_AFTER, "1");
        }
        return problem(e.getStatus(), e.getCode(), e.getMessage(), e.getErrors(), headers, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception e, HttpServletRequest request) {
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return problem(code.getStatus(), code.code(), code.getMessage(), List.of(), new HttpHeaders(), request);
    }

    @Override
    @Nullable
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        List<BusinessException.FieldError> errors = e instanceof MethodArgumentNotValidException invalid
                ? invalid.getBindingResult().getFieldErrors().stream().limit(100)
                        .map(error -> new BusinessException.FieldError(error.getField(), "INVALID_VALUE")).toList()
                : List.of();
        ErrorCode code = fromStatus(status);
        return problem(status, code.code(), code.getMessage(), errors, headers, servletRequest);
    }

    private ErrorCode fromStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 413 -> ErrorCode.FILE_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> ErrorCode.RATE_LIMITED;
            case 503 -> ErrorCode.PROCESSING_UNAVAILABLE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
        };
    }

    private ResponseEntity<Object> problem(HttpStatusCode status, String code, String message,
                                           List<BusinessException.FieldError> errors, HttpHeaders sourceHeaders,
                                           HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setInstance(URI.create(request.getRequestURI()));
        detail.setProperty("code", code);
        detail.setProperty("requestId", RequestIdFilter.requestId(request));
        detail.setProperty("errors", errors);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(sourceHeaders);
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(detail, headers, status);
    }
}
