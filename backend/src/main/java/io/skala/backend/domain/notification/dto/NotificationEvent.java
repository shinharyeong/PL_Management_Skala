package io.skala.backend.domain.notification.dto;

import io.skala.backend.domain.notification.value.NotificationType;
import java.time.OffsetDateTime;

/**
 * 경영기획팀에게 보낼 알림 한 건. eventKey가 같으면 받는 사람마다 한 번만 저장한다.
 * 예: SUBMISSION → "submission:{submissionId}", REPLY → "reply:{explanationId}:{round}", OVERDUE → "overdue:{requestId}:{organizationId}"
 */
public record NotificationEvent(
        NotificationType type,
        String eventKey,
        Long requestId,
        Long organizationId,
        Long submissionId,
        Long reviewExplanationId,
        String title,
        OffsetDateTime occurredAt
) {
}
