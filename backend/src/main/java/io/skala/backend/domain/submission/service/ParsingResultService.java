package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.financial.dto.IngestionResult;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData;
import io.skala.backend.domain.financial.service.FinancialIngestionService;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.submission.entity.ParsingJob;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.repository.ParsingJobRepository;
import io.skala.backend.domain.submission.value.ParsingJobStatus;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.domain.user.entity.Organization;
import io.skala.backend.domain.user.repository.OrganizationRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파서 작업 결과를 반영한다. 파서 실행(큐·프로세스)과 결과 파일 저장은 UP-03 단계에서 연결하고,
 * 여기서는 결과 객체를 받아 financial_line·financial_value 적재와 parsing_job 상태 갱신만 맡는다.
 */
@Service
@RequiredArgsConstructor
public class ParsingResultService {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    private final ParsingJobRepository parsingJobRepository;
    private final SubmissionRequestRepository submissionRequestRepository;
    private final OrganizationRepository organizationRepository;
    private final FinancialIngestionService financialIngestionService;
    private final Clock clock = Clock.systemUTC();

    public record ParserVersions(String parser, String contract, String mapping) {
    }

    @Transactional
    public IngestionResult complete(Long parsingJobId, ParserVersions versions, StoredObject resultObject,
                                    ParserSubmissionData data) {
        ParsingJob job = parsingJobRepository.findById(parsingJobId)
                .orElseThrow(() -> new IllegalArgumentException("parsing_job not found: " + parsingJobId));
        if (job.getStatus() == ParsingJobStatus.SUCCEEDED || job.getStatus() == ParsingJobStatus.FAILED) {
            throw new IllegalStateException("이미 끝난 파싱 작업입니다: " + parsingJobId);
        }
        Submission submission = job.getSubmission();
        if (submission.getStatus() != SubmissionStatus.DRAFT) {
            throw new IllegalStateException("DRAFT가 아닌 제출본은 다시 적재하지 않습니다: " + submission.getId());
        }
        SubmissionRequest request = submissionRequestRepository.findById(submission.getRequestId()).orElseThrow();
        Organization organization = organizationRepository.findById(submission.getOrganizationId()).orElseThrow();

        IngestionResult result = financialIngestionService.ingest(submission, organization,
                request.getAnnualPlan().getYear(), data);
        // TODO(B FileStorage): result.diagnostics()를 검증 결과 파일로 저장하고 submission_validation에 기록(UP-05)
        job.succeed(versions.parser(), versions.contract(), versions.mapping(), resultObject,
                result.passed() ? null : VALIDATION_FAILED, OffsetDateTime.now(clock));
        return result;
    }

    @Transactional
    public void fail(Long parsingJobId, String errorCode, String errorMessage) {
        ParsingJob job = parsingJobRepository.findById(parsingJobId)
                .orElseThrow(() -> new IllegalArgumentException("parsing_job not found: " + parsingJobId));
        job.fail(errorCode, errorMessage, OffsetDateTime.now(clock));
    }
}
