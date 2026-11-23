package io.skala.backend.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 공통 오류 코드(docs/api/api-spec.md 0-3절). 도메인 업무 오류 코드는 각 도메인에서 이 enum에 추가하거나
 * BusinessException에 직접 코드를 넘긴다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식과 입력값을 확인해 주세요."),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "목록 조회 위치가 올바르지 않습니다. 처음부터 다시 조회해 주세요."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "현재 권한으로 접근할 수 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 자료를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 메서드입니다."),
    RESOURCE_CHANGED(HttpStatus.CONFLICT, "다른 사용자가 먼저 변경했습니다. 다시 조회해 주세요."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "같은 요청 키를 다른 내용에 사용할 수 없습니다."),
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "같은 요청을 처리하고 있습니다. 잠시 후 다시 시도해 주세요."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "업로드 가능한 파일 크기를 초과했습니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 본문 형식입니다."),
    UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "파일 이름과 확장자명을 다시 확인해주세요."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청 처리 중 오류가 발생했습니다."),
    PROCESSING_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "현재 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;

    public String code() {
        return name();
    }
}
