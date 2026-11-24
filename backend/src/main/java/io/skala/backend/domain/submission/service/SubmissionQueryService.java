package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.domain.review.repository.ReviewItemRepository;
import io.skala.backend.domain.review.service.ExplanationTargetService;
import io.skala.backend.domain.review.dto.SubmissionReviewDetail;
import io.skala.backend.domain.review.service.SubmissionReviewDetailService;
import io.skala.backend.domain.submission.dto.BaselineResponse;
import io.skala.backend.domain.submission.dto.SubmissionDetailResponse;
import io.skala.backend.domain.submission.dto.SubmissionResponse;
import io.skala.backend.domain.submission.dto.SubmissionResponse.FileRef;
import io.skala.backend.domain.submission.dto.SubmissionResponse.OrganizationRef;
import io.skala.backend.domain.submission.dto.SubmissionResponse.SummaryRef;
import io.skala.backend.domain.submission.dto.SubmissionResponse.UserRef;
import io.skala.backend.domain.submission.dto.SubmissionResponse.Validation;
import io.skala.backend.domain.submission.dto.ParsingJobResponse;
import io.skala.backend.domain.submission.entity.ParsingJob;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.repository.ParsingJobRepository;
import io.skala.backend.domain.submission.repository.SubmissionRepository;
import io.skala.backend.domain.submission.service.BaselineResolver.Baseline;
import io.skala.backend.domain.submission.support.SubmissionAccess;
import io.skala.backend.domain.submission.value.BaselineKind;
import io.skala.backend.domain.submission.value.ParsingJobStatus;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.entity.Organization;
import io.skala.backend.domain.user.repository.OrganizationRepository;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.util.OffsetCursor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UP-04 제출본 상태 조회와 공통 응답 조립 */
@Service
@RequiredArgsConstructor
public class SubmissionQueryService {

    private final SubmissionAccess submissionAccess;
    private final SubmissionRepository submissionRepository;
    private final ParsingJobRepository parsingJobRepository;
    private final SubmissionRequestRepository submissionRequestRepository;
    private final OrganizationRepository organizationRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final ExplanationTargetService explanationTargetService;
    private final BaselineResolver baselineResolver;
    private final SubmissionDiagnosticsService submissionDiagnosticsService;
    private final SubmissionReviewDetailService submissionReviewDetailService;

    @Transactional(readOnly = true)
    public SubmissionDetailResponse get(Long submissionId, AuthenticatedUser user) {
        Submission submission = submissionAccess.loadForRead(submissionId, user);
        SubmissionReviewDetail review = submissionReviewDetailService.detail(submission);
        return new SubmissionDetailResponse(toResponse(submission), review.explanations(),
                review.inheritedExplanations(), review.rejectedItems(), history(submission));
    }

    /**
     * 같은 요청·조직의 처리 이력(초안 제외, 회차 순): 첫 제출 SUBMITTED, 이후 제출 RESUBMITTED,
     * 반려 RESUBMIT_REQUESTED, 승인 APPROVED.
     */
    private List<SubmissionDetailResponse.HistoryEvent> history(Submission submission) {
        SubmissionRequest request = submissionRequestRepository.findById(submission.getRequestId()).orElseThrow();
        List<SubmissionDetailResponse.HistoryEvent> events = new java.util.ArrayList<>();
        submissionRepository.findSubmitted(submission.getOrganizationId(), submission.getRequestId(), null, null, null)
                .stream()
                .sorted(java.util.Comparator.comparing(Submission::getSeq))
                .forEach(s -> {
                    String label = SubmissionLabels.of(s, request);
                    events.add(new SubmissionDetailResponse.HistoryEvent(s.getSeq() == 1 ? "SUBMITTED" : "RESUBMITTED",
                            user(s.getSubmittedBy()), s.getSubmittedAt(), label));
                    if (s.getStatus() == SubmissionStatus.REJECTED) {
                        events.add(new SubmissionDetailResponse.HistoryEvent("RESUBMIT_REQUESTED",
                                user(s.getReviewedBy()), s.getReviewedAt(), label));
                    } else if (s.getStatus() == SubmissionStatus.APPROVED) {
                        events.add(new SubmissionDetailResponse.HistoryEvent("APPROVED", user(s.getReviewedBy()),
                                s.getReviewedAt(), SubmissionLabels.approved(s, request)));
                    }
                });
        return events;
    }

    /**
     * HIST-01 · REQ-05 제출본 목록(초안 제외, 최근 제출 순).
     * 조직 담당자는 자기 조직만(organizationId를 넘기면 자기 조직이어야 함), 경영기획팀은 requestId·organizationId 필수.
     */
    @Transactional(readOnly = true)
    public SubmissionPage list(AuthenticatedUser user, RequestType type, Integer year, Integer month,
                               String displayStatus, Long requestId, Long organizationId, String cursor, Integer limit) {
        Long organization;
        if (user.isPlanner()) {
            if (requestId == null || organizationId == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "requestId와 organizationId가 필요합니다.");
            }
            organization = organizationId;
        } else {
            if (organizationId != null) {
                user.requireOrganizationAccess(organizationId);
            }
            organization = user.organizationId();
        }
        String scope = "subs|" + organization + "|" + requestId + "|" + type + "|" + year + "|" + month + "|" + displayStatus;
        int size = OffsetCursor.limit(limit);
        int offset = OffsetCursor.decode(cursor, scope);
        List<Submission> matched = submissionRepository.findSubmitted(organization, requestId, type, year, month).stream()
                .filter(s -> displayStatus == null || displayStatus.equals(displayStatus(s)))
                .toList();
        List<SubmissionResponse> page = matched.stream().skip(offset).limit(size).map(this::toResponse).toList();
        return new SubmissionPage(matched.size(), page,
                OffsetCursor.next(matched.size() > offset + size, offset, size, scope));
    }

    /** 목록 응답(PageResponse + total) */
    public record SubmissionPage(int total, List<SubmissionResponse> items, String nextCursor) {
    }

    /** 호출한 쪽 트랜잭션 안에서 쓴다. */
    public SubmissionResponse toResponse(Submission submission) {
        SubmissionRequest request = submissionRequestRepository.findById(submission.getRequestId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Organization organization = organizationRepository.findById(submission.getOrganizationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        ParsingJob job = parsingJobRepository.findBySubmissionId(submission.getId()).orElse(null);
        String title = title(request);
        Submission previous = submission.getPreviousSubmission();
        Submission next = submissionRepository.findFirstByPreviousSubmissionIdOrderByIdDesc(submission.getId())
                .orElse(null);

        return new SubmissionResponse(
                String.valueOf(submission.getId()),
                submission.getStatus(),
                displayStatus(submission),
                SubmissionLabels.of(submission, request),
                SubmissionLabels.approved(submission, request),
                String.valueOf(request.getId()),
                title,
                period(request),
                submission.getSubmittedAt(),
                SubmissionStateToken.of(submission, job),
                request.getType(),
                title,
                new OrganizationRef(String.valueOf(organization.getId()), organization.getCode(), organization.getName()),
                new FileRef(submission.getOriginalFilename(), "/api/v1/submissions/" + submission.getId() + "/file"),
                submission.getReplyMessage(),
                submission.getRejectReason(),
                user(submission.getSubmittedBy()),
                user(submission.getReviewedBy()),
                submission.getReviewedAt(),
                job == null ? null : ParsingJobResponse.of(job),
                validation(submission, job),
                baseline(submission, request),
                previous == null ? null : summary(previous),
                next == null ? null : summary(next));
    }

    private Validation validation(Submission submission, ParsingJob job) {
        if (job == null || job.getStatus() != ParsingJobStatus.SUCCEEDED) {
            return new Validation(false, null);
        }
        if (!job.passed()) {
            return new Validation(false, submissionDiagnosticsService.report(submission.getId(), job)
                    .map(ValidationReport::errorCount).orElse(null));
        }
        boolean validated = !reviewItemRepository.findAllWithReportItemBySubmissionId(submission.getId()).isEmpty();
        boolean canSubmit = submission.getStatus() == SubmissionStatus.DRAFT && validated
                && explanationTargetService.progress(submission.getId()).complete();
        return new Validation(canSubmit, 0);
    }

    /** 초안이면 지금 기준으로 고른 비교 기준. 고를 수 없는 상태(검토 대기 중 등)면 null */
    private BaselineResponse baseline(Submission submission, SubmissionRequest request) {
        if (submission.getStatus() != SubmissionStatus.DRAFT) {
            return null;
        }
        try {
            Baseline baseline = baselineResolver.resolve(submission, request);
            if (baseline.submission() == null) {
                return new BaselineResponse(BaselineKind.NONE, null, null);
            }
            Submission base = baseline.submission();
            SubmissionRequest baseRequest = submissionRequestRepository.findById(base.getRequestId()).orElseThrow();
            return new BaselineResponse(baseline.kind(), String.valueOf(base.getId()),
                    SubmissionLabels.of(base, baseRequest));
        } catch (BusinessException e) {
            return null;
        }
    }

    private String displayStatus(Submission submission) {
        if (submission.getStatus() != SubmissionStatus.REJECTED) {
            return submission.getStatus().name();
        }
        boolean superseded = submissionRepository.existsByRequestIdAndOrganizationIdAndSeqGreaterThan(
                submission.getRequestId(), submission.getOrganizationId(), submission.getSeq());
        return superseded ? "SUPERSEDED" : "RESUBMIT_REQUESTED";
    }

    private SummaryRef summary(Submission submission) {
        SubmissionRequest request = submissionRequestRepository.findById(submission.getRequestId()).orElseThrow();
        return new SummaryRef(String.valueOf(submission.getId()), submission.getStatus(),
                SubmissionLabels.of(submission, request));
    }

    static String title(SubmissionRequest request) {
        int year = request.getAnnualPlan().getYear();
        return request.getType() == RequestType.ANNUAL_PLAN
                ? "%d년 연간 계획 제출".formatted(year)
                : "%d년 %d월 월간 실적·추정 제출".formatted(year, request.getMonth());
    }

    static String period(SubmissionRequest request) {
        int year = request.getAnnualPlan().getYear();
        return request.getType() == RequestType.ANNUAL_PLAN ? String.valueOf(year)
                : "%d.%02d".formatted(year, request.getMonth());
    }

    private static UserRef user(AppUser user) {
        return user == null ? null : new UserRef(String.valueOf(user.getId()), user.getName());
    }
}
