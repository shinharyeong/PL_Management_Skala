package io.skala.backend.domain.notification.entity;

import io.skala.backend.domain.notification.value.NotificationType;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.review.entity.ReviewExplanation;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.entity.Organization;
import io.skala.backend.global.entity.BaseCreatedTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "notification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseCreatedTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private AppUser recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private NotificationType type;

    @Column(name = "event_key", nullable = false, length = 180)
    private String eventKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private SubmissionRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id")
    private Submission submission;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_explanation_id")
    private ReviewExplanation reviewExplanation;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    public static Notification create(AppUser recipient, NotificationType type, String eventKey,
                                      SubmissionRequest request, Organization organization, Submission submission,
                                      ReviewExplanation reviewExplanation, String title, OffsetDateTime occurredAt) {
        Notification notification = new Notification();
        notification.recipient = recipient;
        notification.type = type;
        notification.eventKey = eventKey;
        notification.request = request;
        notification.organization = organization;
        notification.submission = submission;
        notification.reviewExplanation = reviewExplanation;
        notification.title = title;
        notification.occurredAt = occurredAt;
        return notification;
    }

    public void markRead(OffsetDateTime at) {
        if (readAt == null) {
            readAt = at;
        }
    }
}
