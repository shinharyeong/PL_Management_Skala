package io.skala.backend.domain.notification.repository;

import io.skala.backend.domain.notification.entity.Notification;
import java.time.OffsetDateTime;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByRecipientIdAndEventKey(Long recipientId, String eventKey);

    long countByRecipientIdAndReadAtIsNull(Long recipientId);

    @Modifying
    @Query("update Notification n set n.readAt = :at where n.recipient.id = :recipientId and n.readAt is null and n.id in :ids")
    int markRead(@Param("recipientId") Long recipientId, @Param("ids") Collection<Long> ids, @Param("at") OffsetDateTime at);

    @Modifying
    @Query("update Notification n set n.readAt = :at where n.recipient.id = :recipientId and n.readAt is null")
    int markAllRead(@Param("recipientId") Long recipientId, @Param("at") OffsetDateTime at);
}
