package io.skala.backend.domain.submission.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.file.service.FileStorage;
import io.skala.backend.domain.submission.dto.DiagnosticsResponse;
import io.skala.backend.domain.submission.dto.SubmissionResponse;
import io.skala.backend.domain.submission.entity.ParsingJob;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.exception.SubmissionErrorCode;
import io.skala.backend.domain.submission.repository.ParsingJobRepository;
import io.skala.backend.domain.submission.repository.SubmissionValidationRepository;
import io.skala.backend.domain.submission.support.SubmissionAccess;
import io.skala.backend.domain.submission.value.ParsingJobStatus;
import io.skala.backend.global.security.AuthenticatedUser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** UP-05 검증 결과 · 수정 필요 표 */
@Service
@RequiredArgsConstructor
public class SubmissionDiagnosticsService {

    /** 파일을 읽지 못한 경우(파싱 FAILED)의 안내 */
    private static final Map<String, String> FAILURE_GUIDES = Map.of(
            "PARSER_TIMEOUT", "파일 확인 시간이 초과됐습니다. 잠시 후 다시 시도해 주세요.",
            "PARSER_UNAVAILABLE", "파일 확인 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");

    private final SubmissionAccess submissionAccess;
    private final ParsingJobRepository parsingJobRepository;
    private final SubmissionValidationRepository submissionValidationRepository;
    private final FileStorage fileStorage;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public DiagnosticsResponse get(Long submissionId, AuthenticatedUser user) {
        Submission submission = submissionAccess.loadForRead(submissionId, user);
        ParsingJob job = parsingJobRepository.findBySubmissionId(submissionId)
                .orElseThrow(SubmissionErrorCode.PARSING_NOT_READY::exception);
        SubmissionResponse.FileRef file = new SubmissionResponse.FileRef(submission.getOriginalFilename(),
                "/api/v1/submissions/" + submissionId + "/file");
        if (job.getStatus() == ParsingJobStatus.FAILED) {
            String guide = FAILURE_GUIDES.getOrDefault(job.getErrorCode(),
                    "파일을 읽을 수 없습니다. .xlsx·.xlsb 양식 파일인지 확인해 다시 시도하거나 파일을 다시 선택해 주세요.");
            return new DiagnosticsResponse(file, 1, List.of(new DiagnosticsResponse.Item(null, null, "파일 읽기", guide)));
        }
        if (job.getStatus() != ParsingJobStatus.SUCCEEDED) {
            throw SubmissionErrorCode.PARSING_NOT_READY.exception();
        }
        return report(submissionId, job)
                .map(r -> new DiagnosticsResponse(file, r.errorCount(), r.toItems()))
                .orElseGet(() -> new DiagnosticsResponse(file, 0, List.of()));
    }

    /** 지금 파싱 시도에 해당하는 검증 결과. 없거나 이전 시도 것이면 비어 있다. */
    Optional<ValidationReport> report(Long submissionId, ParsingJob job) {
        return submissionValidationRepository.findById(submissionId)
                .filter(v -> v.getAttemptNo().equals(job.getAttemptNo()))
                .map(v -> {
                    try (InputStream in = fileStorage.open(v.getValidationResultObject())) {
                        return objectMapper.readValue(in, ValidationReport.class);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }
}
