package io.skala.backend.domain.submission.exception;

import io.skala.backend.global.exception.BusinessException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 제출·소명 파이프라인 업무 오류 코드(docs/api/openapi.yaml 각 API 오류 응답). */
@Getter
@RequiredArgsConstructor
public enum SubmissionErrorCode {

    REQUEST_CLOSED(HttpStatus.CONFLICT, "마감된 요청입니다. 경영기획팀에 문의해 주세요."),
    REQUEST_TARGET_EXCLUDED(HttpStatus.CONFLICT, "이 요청의 제출 대상 조직이 아닙니다."),
    PENDING_ALREADY_EXISTS(HttpStatus.CONFLICT, "검토 대기 중인 제출본이 있습니다. 검토가 끝난 뒤 다시 제출해 주세요."),
    INVALID_FILENAME(HttpStatus.UNPROCESSABLE_ENTITY, "파일 이름과 확장자명을 다시 확인해주세요."),
    ORGANIZATION_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "조직명을 다시 확인해주세요."),
    PARSING_RETRY_NOT_ALLOWED(HttpStatus.CONFLICT, "파일 확인에 실패한 경우에만 다시 시도할 수 있습니다."),
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
