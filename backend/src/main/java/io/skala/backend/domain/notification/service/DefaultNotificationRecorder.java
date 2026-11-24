package io.skala.backend.domain.notification.service;

import io.skala.backend.domain.notification.dto.NotificationEvent;
import io.skala.backend.domain.notification.entity.Notification;
import io.skala.backend.domain.notification.repository.NotificationRepository;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.review.entity.ReviewExplanation;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.entity.Organization;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.value.UserRole;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DefaultNotificationRecorder implements NotificationRecorder {

    private final AppUserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(NotificationEvent event) {
        SubmissionRequest request = entityManager.getReference(SubmissionRequest.class, event.requestId());
        Organization organization = entityManager.getReference(Organization.class, event.organizationId());
        Submission submission = event.submissionId() == null ? null
                : entityManager.getReference(Submission.class, event.submissionId());
        ReviewExplanation explanation = event.reviewExplanationId() == null ? null
                : entityManager.getReference(ReviewExplanation.class, event.reviewExplanationId());
        for (AppUser planner : userRepository.findAllByRoleAndActiveTrue(UserRole.PLANNER)) {
            if (notificationRepository.existsByRecipientIdAndEventKey(planner.getId(), event.eventKey())) {
                continue;
            }
            notificationRepository.save(Notification.create(planner, event.type(), event.eventKey(), request,
                    organization, submission, explanation, event.title(), event.occurredAt()));
        }
    }
}
