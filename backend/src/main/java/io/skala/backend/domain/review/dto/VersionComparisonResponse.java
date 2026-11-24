package io.skala.backend.domain.review.dto;

import io.skala.backend.domain.submission.dto.SubmissionResponse;
import java.util.List;

/** GAP-01 기준 대상(base)과 비교 대상(target) 버전 비교. rows의 plan은 기준 값, actual은 비교 값이다. */
public record VersionComparisonResponse(SubmissionResponse base, SubmissionResponse target,
                                        List<ReviewTreeResponse.Node> rows) {
}
