package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.file.service.FileStorage;
import io.skala.backend.domain.file.value.FilePurpose;
import io.skala.backend.domain.request.entity.RequestTarget;
import io.skala.backend.domain.request.entity.RequestTargetId;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.repository.RequestTargetRepository;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.request.value.RequestStatus;
import io.skala.backend.domain.submission.dto.ParsingJobResponse;
import io.skala.backend.domain.submission.dto.SubmissionResponse;
import io.skala.backend.domain.submission.entity.ParsingJob;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.exception.SubmissionErrorCode;
import io.skala.backend.domain.submission.repository.ParsingJobRepository;
import io.skala.backend.domain.submission.repository.SubmissionRepository;
import io.skala.backend.domain.submission.support.SubmissionAccess;
import io.skala.backend.domain.submission.value.ParsingJobStatus;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.domain.user.entity.Organization;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.repository.OrganizationRepository;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.util.StateToken;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UP-03 엑셀 업로드 → 제출 초안 + 파싱 작업 등록, UP-06 파싱 재시도 */
@Service
@RequiredArgsConstructor
public class SubmissionUploadService {

    static final String ENGINE = "stdlib-bridge";

    private final SubmissionRequestRepository submissionRequestRepository;
    private final RequestTargetRepository requestTargetRepository;
    private final OrganizationRepository organizationRepository;
    private final AppUserRepository appUserRepository;
    private final SubmissionRepository submissionRepository;
    private final ParsingJobRepository parsingJobRepository;
    private final SubmissionAccess submissionAccess;
    private final SubmissionQueryService submissionQueryService;
    private final FileStorage fileStorage;
    private final ParsingJobDispatcher parsingJobDispatcher;
    private final Clock clock;

    @Transactional
    public SubmissionResponse upload(Long requestId, AuthenticatedUser user, String filename, String contentType,
                                     byte[] content) {
        user.requireRole(UserRole.ORG_MANAGER);
        SubmissionRequest request = submissionRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Organization organization = organizationRepository.findById(user.organizationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        RequestTarget target = requestTargetRepository.findById(new RequestTargetId(requestId, organization.getId()))
                .orElseThrow(SubmissionErrorCode.REQUEST_TARGET_EXCLUDED::exception);
        if (Boolean.TRUE.equals(target.getExcluded()) || !Boolean.TRUE.equals(target.getSelected())) {
            throw SubmissionErrorCode.REQUEST_TARGET_EXCLUDED.exception();
        }
        if (request.getStatus() != RequestStatus.OPEN) {
            throw SubmissionErrorCode.REQUEST_CLOSED.exception();
        }
        if (submissionRepository.existsByRequestIdAndOrganizationIdAndStatus(requestId, organization.getId(),
                SubmissionStatus.PENDING)) {
            throw SubmissionErrorCode.PENDING_ALREADY_EXISTS.exception();
        }
        if (content.length == 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "빈 파일은 올릴 수 없습니다.");
        }
        SubmissionFileNames.check(filename, request, organization.getCode());
        if (!SubmissionFileNames.looksLikeWorkbook(content)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }

        StoredObject stored = fileStorage.save(FilePurpose.SUBMISSION_EXCEL, filename,
                contentType == null || contentType.isBlank()
                        ? SubmissionFileNames.defaultContentType(filename) : contentType,
                new ByteArrayInputStream(content));
        Submission submission = submissionRepository.save(Submission.draft(requestId, organization.getId(), filename,
                stored, appUserRepository.getReferenceById(user.id()), OffsetDateTime.now(clock)));
        ParsingJob job = parsingJobRepository.save(ParsingJob.queue(submission, ENGINE, 1));
        parsingJobRepository.flush();
        parsingJobDispatcher.dispatchAfterCommit(job.getId(), job.getAttemptNo());
        return submissionQueryService.toResponse(submission);
    }

    @Transactional
    public ParsingJobResponse retry(Long submissionId, AuthenticatedUser user, String expectedStateToken) {
        Submission submission = submissionAccess.lockForOrgManager(submissionId, user);
        ParsingJob job = parsingJobRepository.findBySubmissionId(submissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        StateToken.verify(expectedStateToken, SubmissionStateToken.of(submission, job));
        if (submission.getStatus() != SubmissionStatus.DRAFT || job.getStatus() != ParsingJobStatus.FAILED) {
            throw SubmissionErrorCode.PARSING_RETRY_NOT_ALLOWED.exception();
        }
        job.requeue();
        parsingJobRepository.flush();
        parsingJobDispatcher.dispatchAfterCommit(job.getId(), job.getAttemptNo());
        return ParsingJobResponse.of(job);
    }
}
