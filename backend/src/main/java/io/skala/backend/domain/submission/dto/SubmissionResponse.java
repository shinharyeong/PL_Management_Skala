package io.skala.backend.domain.submission.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import java.time.OffsetDateTime;

/** openapi Submission(UP-03 응답, UP-04 조회). 상세 이력 필드(HIST-02)는 이후 PR에서 더한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SubmissionResponse(
        String id,
        SubmissionStatus status,
        String displayStatus,
        String label,
        String approvedLabel,
        String requestId,
        String requestTitle,
        String period,
        OffsetDateTime submittedAt,
        String stateToken,
        RequestType kind,
        String title,
        OrganizationRef organization,
        FileRef file,
        String replyMessage,
        String reviewComment,
        UserRef submittedBy,
        UserRef reviewedBy,
        OffsetDateTime reviewedAt,
        ParsingJobResponse parsingJob,
        Validation validation,
        BaselineResponse baseline,
        SummaryRef previousSubmission,
        SummaryRef nextSubmission
) {

    public record OrganizationRef(String id, String code, String name) {
    }

    public record FileRef(String name, String url) {
    }

    public record UserRef(String id, String name) {
    }

    /**
     * @param canSubmit  파일 검증 통과 + 변경 확인(PROOF-01) 완료 + 소명 모두 작성
     * @param errorCount 검증 오류 건수(UP-05 표의 행 수). 통과면 0, 아직 모르면 null
     */
    public record Validation(boolean canSubmit, Integer errorCount) {
    }

    public record SummaryRef(String id, SubmissionStatus status, String label) {
    }
}
