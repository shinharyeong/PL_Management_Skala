package io.skala.backend.domain.review.service;

import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.file.service.FileStorage;
import io.skala.backend.domain.file.value.FilePurpose;
import io.skala.backend.domain.notification.dto.NotificationEvent;
import io.skala.backend.domain.notification.service.NotificationRecorder;
import io.skala.backend.domain.notification.value.NotificationType;
import io.skala.backend.domain.review.dto.AnswerResponse;
import io.skala.backend.domain.review.dto.AttachmentResponse;
import io.skala.backend.domain.review.dto.OrgReviewListResponse;
import io.skala.backend.domain.review.entity.ReviewAttachment;
import io.skala.backend.domain.review.entity.ReviewExplanation;
import io.skala.backend.domain.review.entity.ReviewItem;
import io.skala.backend.domain.review.exception.ReviewErrorCode;
import io.skala.backend.domain.review.repository.ReviewAttachmentRepository;
import io.skala.backend.domain.review.repository.ReviewExplanationRepository;
import io.skala.backend.domain.review.value.ExplanationVerdict;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.util.StateToken;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PROOF-04 소명 작성·수정, PROOF-05 첨부, PROOF-06 첨부 목록·다운로드.
 *
 * <p>쓸 수 있는 소명 회차:
 * <ul>
 *   <li>제출 전(DRAFT): 1회차 소명 대상(PENDING, 이어받은 것 아님)</li>
 *   <li>검토 대기(PENDING) 중 경영기획팀이 소명을 요청한 회차(2회차 이상, 판단 전): 답하면 '소명 회신' 알림</li>
 * </ul>
 * 그 밖에는 EXPLANATION_LOCKED. 대상이 아니게 바뀌었으면 REVIEW_CONTEXT_CHANGED.
 */
@Service
@RequiredArgsConstructor
public class ExplanationAnswerService {

    static final int MAX_ATTACHMENTS = 10;
    static final long MAX_ATTACHMENT_BYTES = 16L * 1024 * 1024;
    static final Set<String> ATTACHMENT_EXTENSIONS = Set.of("pdf", "xlsx", "png", "jpg", "jpeg");

    private final ReviewItemQueryService reviewItemQueryService;
    private final ReviewExplanationRepository reviewExplanationRepository;
    private final ReviewAttachmentRepository reviewAttachmentRepository;
    private final ExplanationTargetService explanationTargetService;
    private final AppUserRepository appUserRepository;
    private final FileStorage fileStorage;
    private final NotificationRecorder notificationRecorder;
    private final Clock clock;

    @Transactional
    public AnswerResponse answer(Long reviewItemId, AuthenticatedUser user, String text, String expectedStateToken) {
        Writable target = writable(reviewItemId, user, expectedStateToken);
        OffsetDateTime now = OffsetDateTime.now(clock);
        target.explanation().answer(text.strip(), appUserRepository.getReferenceById(user.id()), now);
        if (target.explanation().getRound() > ExplanationTargetService.ORG_ROUND) {
            Submission submission = target.item().getSubmission();
            notificationRecorder.record(new NotificationEvent(NotificationType.REPLY,
                    "reply:" + target.explanation().getId() + ":" + target.explanation().getRound(),
                    submission.getRequestId(), submission.getOrganizationId(), submission.getId(),
                    target.explanation().getId(), target.item().getReportItem().getName() + " 소명 회신", now));
        }
        reviewExplanationRepository.flush();
        ExplanationTargetService.Progress progress = explanationTargetService.progress(target.item().getSubmission().getId());
        return new AnswerResponse("WRITTEN", new OrgReviewListResponse.Progress(progress.answered(), progress.required()),
                ReviewItemTokens.of(target.item(), target.explanation(), target.item().getSubmission()));
    }

    @Transactional
    public AttachmentResponse attach(Long reviewItemId, AuthenticatedUser user, String filename, String contentType,
                                     byte[] content, String expectedStateToken) {
        Writable target = writable(reviewItemId, user, expectedStateToken);
        if (content.length == 0) {
            throw ReviewErrorCode.INVALID_ATTACHMENT.exception("빈 파일은 첨부할 수 없습니다.");
        }
        if (content.length > MAX_ATTACHMENT_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        if (filename == null || filename.isBlank() || filename.length() > 255
                || !ATTACHMENT_EXTENSIONS.contains(extension(filename))) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        if (reviewAttachmentRepository.countByExplanationId(target.explanation().getId()) >= MAX_ATTACHMENTS) {
            throw ReviewErrorCode.INVALID_ATTACHMENT.exception("첨부는 소명 한 건에 " + MAX_ATTACHMENTS + "개까지 올릴 수 있습니다.");
        }
        StoredObject stored = fileStorage.save(FilePurpose.EXPLANATION_ATTACHMENT, filename,
                contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType,
                new ByteArrayInputStream(content));
        ReviewAttachment attachment = reviewAttachmentRepository.save(ReviewAttachment.create(target.explanation(),
                filename, stored, appUserRepository.getReferenceById(user.id()), OffsetDateTime.now(clock)));
        target.explanation().touchAttachments();
        return AttachmentResponse.of(reviewItemId, attachment);
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> attachments(Long reviewItemId, AuthenticatedUser user) {
        ReviewItem item = reviewItemQueryService.load(reviewItemId, user);
        List<Long> explanationIds = reviewExplanationRepository.findAllByReviewItemIdOrderByRoundAsc(item.getId()).stream()
                .map(ReviewExplanation::getId).toList();
        if (explanationIds.isEmpty()) {
            return List.of();
        }
        return reviewAttachmentRepository.findAllByExplanationIdInOrderByIdAsc(explanationIds).stream()
                .map(a -> AttachmentResponse.of(reviewItemId, a)).toList();
    }

    public record AttachmentFile(String filename, StoredObject object) {
    }

    @Transactional(readOnly = true)
    public AttachmentFile file(Long reviewItemId, Long attachmentId, AuthenticatedUser user) {
        ReviewItem item = reviewItemQueryService.load(reviewItemId, user);
        ReviewAttachment attachment = reviewAttachmentRepository.findById(attachmentId)
                .filter(a -> a.getExplanation().getReviewItem().getId().equals(item.getId()))
                .orElseThrow(ReviewErrorCode.ATTACHMENT_NOT_FOUND::exception);
        StoredObject object = attachment.getStoredObject();
        object.getContentType();
        return new AttachmentFile(attachment.getOriginalFilename(), object);
    }

    private record Writable(ReviewItem item, ReviewExplanation explanation) {
    }

    private Writable writable(Long reviewItemId, AuthenticatedUser user, String expectedStateToken) {
        user.requireRole(UserRole.ORG_MANAGER);
        ReviewItem item = reviewItemQueryService.load(reviewItemId, user);
        Submission submission = item.getSubmission();
        ReviewExplanation current = ReviewItemQueryService.currentExplanation(
                reviewExplanationRepository.findAllByReviewItemIdOrderByRoundAsc(item.getId()), item);
        StateToken.verify(expectedStateToken, ReviewItemTokens.of(item, current, submission));

        if (submission.getStatus() == SubmissionStatus.DRAFT) {
            boolean target = Boolean.TRUE.equals(item.getActive()) && current != null
                    && current.getRound() == ExplanationTargetService.ORG_ROUND
                    && current.getVerdict() == ExplanationVerdict.PENDING && !current.isInherited();
            if (!target) {
                throw ReviewErrorCode.REVIEW_CONTEXT_CHANGED.exception();
            }
            return new Writable(item, current);
        }
        boolean reply = submission.getStatus() == SubmissionStatus.PENDING && current != null
                && current.getRound() > ExplanationTargetService.ORG_ROUND
                && current.getVerdict() == ExplanationVerdict.PENDING && current.getRequestedAt() != null;
        if (!reply) {
            throw ReviewErrorCode.EXPLANATION_LOCKED.exception();
        }
        return new Writable(item, current);
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
