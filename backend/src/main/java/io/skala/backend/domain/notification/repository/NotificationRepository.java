package io.skala.backend.domain.notification.repository;

import io.skala.backend.domain.notification.entity.Notification;
import java.time.OffsetDateTime;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 처리한 연간 제출 알림은 관리 화면 이력에 남기고, 알림 목록·배지 집계에서는 함께 제외한다. */
    String VISIBLE_SQL = """
            not exists (
              select 1 from submission s where s.request_id=n.request_id and s.organization_id=n.organization_id
                and s.status='APPROVED' and not exists (
                  select 1 from submission newer where newer.request_id=s.request_id
                    and newer.organization_id=s.organization_id and newer.status <> 'DRAFT' and newer.seq>s.seq)
            ) and not exists (
              select 1 from review_explanation e where e.id=n.review_explanation_id and e.verdict='ACCEPTED'
            ) and not exists (
              select 1 from submission s join submission_request r on r.id=s.request_id
              where s.id=n.submission_id and r.type='ANNUAL_PLAN' and n.type='SUBMISSION' and s.status='REJECTED'
            )
            """;

    boolean existsByRecipientIdAndEventKey(Long recipientId, String eventKey);

    @Query(value = "select count(*) from notification n where n.recipient_id = :recipientId and n.read_at is null and "
            + VISIBLE_SQL, nativeQuery = true)
    long countVisibleUnread(@Param("recipientId") Long recipientId);

    @Modifying
    @Query("update Notification n set n.readAt = :at where n.recipient.id = :recipientId and n.readAt is null and n.id in :ids")
    int markRead(@Param("recipientId") Long recipientId, @Param("ids") Collection<Long> ids, @Param("at") OffsetDateTime at);

    @Modifying
    @Query("update Notification n set n.readAt = :at where n.recipient.id = :recipientId and n.readAt is null")
    int markAllRead(@Param("recipientId") Long recipientId, @Param("at") OffsetDateTime at);
}
