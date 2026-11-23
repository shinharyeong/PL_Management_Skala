package io.skala.backend.domain.submission.dto;

import java.time.OffsetDateTime;

/** REQ-06 응답. 이 승인으로 대상 조직이 모두 승인돼 master가 생겼으면 masterPublished가 있다. */
public record DecisionResponse(SubmissionResponse submission, MasterRef masterPublished) {

    /** openapi Master(state=CONFIRMED). 연간 master면 month는 null */
    public record MasterRef(String id, int year, Integer month, Integer seq, String label, String state,
                            OffsetDateTime createdAt) {
    }
}
