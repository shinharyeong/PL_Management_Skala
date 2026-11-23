package io.skala.backend.domain.submission.controller;

import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.file.service.FileStorage;
import io.skala.backend.domain.submission.dto.DiagnosticsResponse;
import io.skala.backend.domain.submission.dto.RetryRequest;
import io.skala.backend.domain.submission.dto.SubmissionResponse;
import io.skala.backend.domain.submission.service.SubmissionDiagnosticsService;
import io.skala.backend.domain.submission.service.SubmissionFileService;
import io.skala.backend.domain.submission.service.SubmissionQueryService;
import io.skala.backend.domain.submission.service.SubmissionUploadService;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** UP-03 업로드, UP-04 상태 조회, UP-05 검증 결과, UP-06 파싱 재시도, REQ-07 제출 파일 다운로드 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class SubmissionUploadController {

    private final SubmissionUploadService submissionUploadService;
    private final SubmissionQueryService submissionQueryService;
    private final SubmissionFileService submissionFileService;
    private final SubmissionDiagnosticsService submissionDiagnosticsService;
    private final FileStorage fileStorage;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    @PostMapping(path = "/submission-requests/{requestId}/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> upload(@PathVariable Long requestId,
                                         @RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                         @RequestPart("file") MultipartFile file) throws IOException {
        AuthenticatedUser user = currentUser.get();
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "업로드할 파일을 선택해 주세요.");
        }
        byte[] content = file.getBytes();
        String filename = file.getOriginalFilename();
        Map<String, Object> fingerprint = Map.of("filename", String.valueOf(filename), "sha256", sha256(content));
        return idempotency.execute(user.id(), "POST /api/v1/submission-requests/" + requestId + "/submissions",
                idempotencyKey, fingerprint, () -> {
                    SubmissionResponse body = submissionUploadService.upload(requestId, user, filename,
                            file.getContentType(), content);
                    return ResponseEntity.status(HttpStatus.ACCEPTED)
                            .location(URI.create("/api/v1/submissions/" + body.id()))
                            .body(body);
                });
    }

    @GetMapping("/submissions/{submissionId}")
    public SubmissionResponse get(@PathVariable Long submissionId) {
        return submissionQueryService.get(submissionId, currentUser.get());
    }

    @GetMapping("/submissions/{submissionId}/diagnostics")
    public DiagnosticsResponse diagnostics(@PathVariable Long submissionId) {
        return submissionDiagnosticsService.get(submissionId, currentUser.get());
    }

    @PostMapping("/submissions/{submissionId}/parsing-retries")
    public ResponseEntity<String> retry(@PathVariable Long submissionId,
                                        @RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                        @Valid @RequestBody RetryRequest request) {
        AuthenticatedUser user = currentUser.get();
        return idempotency.execute(user.id(), "POST /api/v1/submissions/" + submissionId + "/parsing-retries",
                idempotencyKey, request, () -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(submissionUploadService.retry(submissionId, user, request.expectedStateToken())));
    }

    @GetMapping("/submissions/{submissionId}/file")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long submissionId) {
        SubmissionFileService.FileInfo info = submissionFileService.find(submissionId, currentUser.get());
        StoredObject object = info.object();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(info.filename(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(object.getContentType()))
                .contentLength(object.getSizeBytes())
                .body(new InputStreamResource(fileStorage.open(object)));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
