package io.skala.backend.domain.notification.repository;

import io.skala.backend.domain.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByRecipientIdAndEventKey(Long recipientId, String eventKey);
}
