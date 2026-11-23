package io.skala.backend.domain.review.exception;

import io.skala.backend.global.exception.BusinessException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 소명·검토 업무 오류 코드(docs/api/openapi.yaml PROOF-04~06) */
@Getter
@RequiredArgsConstructor
public enum ReviewErrorCode {

    EXPLANATION_LOCKED(HttpStatus.CONFLICT, "제출한 뒤에는 소명을 고칠 수 없습니다."),
    REVIEW_CONTEXT_CHANGED(HttpStatus.CONFLICT, "변경 확인 결과가 바뀌었습니다. 다시 조회해 주세요."),
    INVALID_ATTACHMENT(HttpStatus.BAD_REQUEST, "첨부할 수 없는 파일입니다. 파일을 확인해 주세요."),
    ATTACHMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "첨부 파일을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    public BusinessException exception() {
        return new BusinessException(status, name(), message);
    }

    public BusinessException exception(String message) {
        return new BusinessException(status, name(), message);
    }
}
