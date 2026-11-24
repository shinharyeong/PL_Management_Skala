package io.skala.backend.domain.submission.value;

/** 소명 비교 기준 버전 종류 (openapi Baseline.kind) */
public enum BaselineKind {

    /** 전월 승인 제출본(같은 요청에 승인본이 있으면 그 승인본) */
    PREV_MONTH_APPROVED,
    /** 확정 연간 계획 제출본(1월 월간 시트, request_target.confirmed_plan_submission_id) */
    ANNUAL_PLAN,
    /** 반려된 제출본(재제출) */
    REJECTED,
    /** 비교 기준 없음(연간 계획 첫 제출) */
    NONE
}
