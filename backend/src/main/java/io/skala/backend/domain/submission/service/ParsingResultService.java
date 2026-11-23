package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.financial.dto.IngestionDiagnostic;
import io.skala.backend.domain.financial.dto.IngestionResult;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData;
import io.skala.backend.domain.financial.service.FinancialIngestionService;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.submission.entity.ParsingJob;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.parser.ParserBridgeResponse;
import io.skala.backend.domain.submission.repository.ParsingJobRepository;
import io.skala.backend.domain.submission.value.ParsingJobStatus;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.domain.user.entity.Organization;
import io.skala.backend.domain.user.repository.OrganizationRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파서 결과를 반영한다: financial_line·financial_value 적재와 parsing_job 상태 갱신.
 *
 * <ul>
 *   <li>FAILED(파일을 읽지 못함) → parsing_job FAILED. UP-06으로 다시 시도할 수 있다</li>
 *   <li>SUCCEEDED·NEEDS_REVIEW → 적재. 검증 오류가 있으면 SUCCEEDED + error_code=VALIDATION_FAILED('수정 필요')</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ParsingResultService {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    private final ParsingJobRepository parsingJobRepository;
    private final SubmissionRequestRepository submissionRequestRepository;
    private final OrganizationRepository organizationRepository;
    private final FinancialIngestionService financialIngestionService;
    private final Clock clock;

    /**
     * @param attemptNo 실행할 때의 시도 번호. 그 사이 재시도로 번호가 바뀌었으면 결과를 버린다
     * @return 적재 결과. 파싱 실패나 이미 끝난 작업이면 비어 있다
     */
    @Transactional
    public Optional<IngestionResult> apply(Long parsingJobId, int attemptNo, ParserBridgeResponse response) {
        ParsingJob job = parsingJobRepository.findById(parsingJobId)
                .orElseThrow(() -> new IllegalArgumentException("parsing_job not found: " + parsingJobId));
        if (job.getStatus() != ParsingJobStatus.RUNNING || job.getAttemptNo() != attemptNo) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        Submission submission = job.getSubmission();
        if (submission.getStatus() != SubmissionStatus.DRAFT) {
            job.fail("SUBMISSION_NOT_DRAFT", null, now);
            return Optional.empty();
        }
        ParserSubmissionData data = response.submissionData();
        if ("FAILED".equals(response.status()) || data == null) {
            job.fail(response.errorCode() == null ? "PARSER_OUTPUT_INVALID" : response.errorCode(), null, now);
            return Optional.empty();
        }

        SubmissionRequest request = submissionRequestRepository.findById(submission.getRequestId()).orElseThrow();
        Organization organization = organizationRepository.findById(submission.getOrganizationId()).orElseThrow();
        IngestionResult ingested = financialIngestionService.ingest(submission, organization,
                request.getAnnualPlan().getYear(), data);

        List<IngestionDiagnostic> diagnostics = new ArrayList<>(ingested.diagnostics());
        if ("NEEDS_REVIEW".equals(response.status())) {
            // 파일은 읽었지만 파서가 확인이 필요하다고 본 항목. 적재 검사에서 이미 잡히지 않은 것만 더한다.
            for (ParserSubmissionData.Diagnostic d : response.diagnostics()) {
                String cell = d.source() == null ? null : d.source().sheet() + "!" + d.source().cell();
                IngestionDiagnostic converted = new IngestionDiagnostic(cell, null, d.code(), d.message());
                if (!diagnostics.contains(converted)) {
                    diagnostics.add(converted);
                }
            }
            if (diagnostics.isEmpty()) {
                diagnostics.add(new IngestionDiagnostic(null, null, response.errorCode(),
                        "파일 내용 중 확인이 필요한 항목이 있습니다. 양식을 확인해 다시 올려 주세요."));
            }
        }
        IngestionResult result = new IngestionResult(ingested.lineCount(), ingested.valueCount(), diagnostics);
        // TODO(B FileStorage): 진단 목록을 검증 결과 파일로 저장하고 submission_validation에 기록(UP-05)
        job.succeed(response.parserVersion(), response.contractVersion(),
                data.adapterVersion(), null, result.passed() ? null : VALIDATION_FAILED, now);
        return Optional.of(result);
    }

    /** 작업 실행 중 예외·시간 초과 */
    @Transactional
    public void fail(Long parsingJobId, int attemptNo, String errorCode) {
        parsingJobRepository.findById(parsingJobId)
                .filter(job -> job.getStatus() == ParsingJobStatus.RUNNING && job.getAttemptNo() == attemptNo)
                .ifPresent(job -> job.fail(errorCode, null, OffsetDateTime.now(clock)));
    }
}
