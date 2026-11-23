package io.skala.backend.domain.submission.dto;

import io.skala.backend.domain.submission.value.BaselineKind;

/** 소명 비교 기준 버전(openapi Baseline). 기준이 없으면 submissionId·label이 null. */
public record BaselineResponse(BaselineKind kind, String submissionId, String label) {
}
