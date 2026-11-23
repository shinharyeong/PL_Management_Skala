package io.skala.backend.domain.request.dto;

import io.skala.backend.domain.user.dto.OrganizationResponse;
import java.time.OffsetDateTime;
import java.util.List;

/** REQ-04 조직별 진행 현황. displayStatus: SUBMITTED(제출 완료)·OVERDUE(기한 경과)·WAITING(회신 대기) */
public record RequestDetailResponse(SubmissionRequestResponse request, List<Target> targets) {

    public record Target(
            OrganizationResponse organization,
            boolean selected,
            OffsetDateTime requestedAt,
            OffsetDateTime dueAt,
            String displayStatus,
            LatestSubmission latestSubmission
    ) {
    }

    /** displayStatus: PENDING·APPROVED·RESUBMIT_REQUESTED */
    public record LatestSubmission(String id, String status, String displayStatus, String label) {
    }
}
