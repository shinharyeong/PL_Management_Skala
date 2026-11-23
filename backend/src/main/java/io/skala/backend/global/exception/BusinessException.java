package io.skala.backend.global.exception;

import java.util.List;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 화면에 보여 줄 수 있는 업무 오류. code로 프론트가 분기한다. */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<FieldError> errors;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode.getStatus(), errorCode.code(), errorCode.getMessage(), List.of());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode.getStatus(), errorCode.code(), message, List.of());
    }

    /** 도메인 업무 오류 코드(예: PENDING_ALREADY_EXISTS)를 직접 지정한다. */
    public BusinessException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public BusinessException(HttpStatus status, String code, String message, List<FieldError> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = List.copyOf(errors);
    }

    public record FieldError(String field, String code) {
    }
}
