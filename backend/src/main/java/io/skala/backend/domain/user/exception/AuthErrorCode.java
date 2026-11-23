package io.skala.backend.domain.user.exception;

import io.skala.backend.global.exception.BusinessException;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 로그인 업무 오류 코드(docs/api/openapi.yaml AUTH-01·02). */
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode {

    UNREGISTERED_EMAIL(HttpStatus.UNPROCESSABLE_ENTITY, "등록되지 않은 계정입니다. 메일 주소를 확인해주세요"),
    CODE_RESEND_WAIT(HttpStatus.TOO_MANY_REQUESTS, "인증 코드는 1분 뒤에 다시 받을 수 있습니다."),
    INVALID_AUTH_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 일치하지 않습니다");

    private final HttpStatus status;
    private final String message;

    public BusinessException exception() {
        return new BusinessException(status, name(), message);
    }

    /** 인증코드 실패 이유를 errors[0].code로 함께 내려준다(MISMATCH·EXPIRED·TOO_MANY_ATTEMPTS). */
    public static BusinessException invalidCode(String reason, String message) {
        return new BusinessException(INVALID_AUTH_CODE.status, INVALID_AUTH_CODE.name(), message,
                List.of(new BusinessException.FieldError("code", reason)));
    }
}
