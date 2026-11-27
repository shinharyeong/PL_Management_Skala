package io.skala.backend.domain.request.exception;

import io.skala.backend.global.exception.BusinessException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 제출 요청 업무 오류 코드(docs/api/openapi.yaml REQ·UP-01). */
@Getter
@RequiredArgsConstructor
public enum RequestErrorCode {

    REQUEST_ALREADY_EXISTS(HttpStatus.CONFLICT, "해당 기간의 요청이 이미 있습니다."),
    REQUEST_TARGET_ALREADY_SELECTED(HttpStatus.CONFLICT, "이미 요청을 보낸 조직이 포함되어 있습니다. 현황을 새로 확인해 주세요."),
    ANNUAL_PLAN_NOT_CONFIRMED(HttpStatus.CONFLICT, "연간 계획을 먼저 확정해야 월간 시트를 요청할 수 있습니다."),
    REQUEST_TARGETS_EMPTY(HttpStatus.CONFLICT, "요청할 수 있는 조직이 없습니다."),
    TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND, "제출 양식 파일이 준비되지 않았습니다. 경영기획팀에 문의해 주세요.");

    private final HttpStatus status;
    private final String message;

    public BusinessException exception() {
        return new BusinessException(status, name(), message);
    }
}
