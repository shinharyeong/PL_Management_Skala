package io.skala.backend.domain.submission.exception;

import io.skala.backend.global.exception.BusinessException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 제출·소명 파이프라인 업무 오류 코드(docs/api/openapi.yaml 각 API 오류 응답). */
@Getter
@RequiredArgsConstructor
public enum SubmissionErrorCode {

    PARSING_NOT_READY(HttpStatus.CONFLICT, "파일 확인이 아직 끝나지 않았습니다. 잠시 후 다시 시도해 주세요."),
    VALIDATION_NOT_ALLOWED(HttpStatus.CONFLICT, "제출 전 초안에서만 변경 확인을 할 수 있습니다."),
    COMPARISON_SOURCE_UNAVAILABLE(HttpStatus.CONFLICT, "비교할 기준 버전의 데이터가 없습니다. 경영기획팀에 문의해 주세요."),
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "파일 검증에서 수정할 항목이 있습니다. 검증 결과를 확인해 주세요.");

    private final HttpStatus status;
    private final String message;

    public BusinessException exception() {
        return new BusinessException(status, name(), message);
    }
}
