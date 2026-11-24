package io.skala.backend.domain.report.exception;

import io.skala.backend.global.exception.BusinessException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** AI 보고서(RPT) 오류 코드 */
@Getter
@RequiredArgsConstructor
public enum ReportErrorCode {

    EXPLANATION_NOT_ACCEPTED(HttpStatus.CONFLICT, "수락된 소명만 분류할 수 있습니다. 수락된 뒤 다시 시도해 주세요."),
    CATEGORY_NOT_ASSIGNABLE(HttpStatus.BAD_REQUEST, "상위 유형은 하위 유형 합으로 계산되어 직접 넣을 수 없습니다. 하위 유형을 골라 주세요.");

    private final HttpStatus status;
    private final String message;

    public BusinessException exception() {
        return new BusinessException(status, name(), message);
    }
}
