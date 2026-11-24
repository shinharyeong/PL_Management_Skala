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
    CATEGORY_NOT_ASSIGNABLE(HttpStatus.BAD_REQUEST, "상위 유형은 하위 유형 합으로 계산되어 직접 넣을 수 없습니다. 하위 유형을 골라 주세요."),
    INVALID_TEMPLATE(HttpStatus.UNPROCESSABLE_ENTITY, "자료 틀 형식이 올바르지 않습니다. 표시된 항목을 고쳐 주세요."),
    REPORT_REVISION_CHANGED(HttpStatus.CONFLICT, "다른 사용자가 먼저 새 버전을 저장했습니다. 최신 버전을 불러온 뒤 다시 저장해 주세요."),
    REPORT_AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "보고서 AI에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    REPORT_AI_REJECTED(HttpStatus.BAD_REQUEST, "보고서 AI가 요청을 처리하지 못했습니다. 입력을 확인해 주세요."),
    INVALID_INTERNAL_TOKEN(HttpStatus.UNAUTHORIZED, "내부 토큰이 맞지 않습니다."),
    PDF_JOB_NOT_READY(HttpStatus.CONFLICT, "PDF 분석이 아직 끝나지 않았습니다."),
    METRIC_CODE_EXISTS(HttpStatus.CONFLICT, "같은 코드의 지표가 이미 있습니다. 다른 코드를 써 주세요."),
    SQL_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "보고서용 뷰만 읽는 SELECT 한 문장만 등록할 수 있습니다.");

    private final HttpStatus status;
    private final String message;

    public BusinessException exception() {
        return new BusinessException(status, name(), message);
    }

    public BusinessException exception(String detail) {
        return new BusinessException(status, name(), detail == null || detail.isBlank() ? message : message + " (" + detail + ")");
    }

    public BusinessException exception(java.util.List<BusinessException.FieldError> errors) {
        return new BusinessException(status, name(), message, errors);
    }
}
