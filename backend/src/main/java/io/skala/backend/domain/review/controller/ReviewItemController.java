package io.skala.backend.domain.review.controller;

import io.skala.backend.domain.review.dto.AnswerRequest;
import io.skala.backend.domain.review.dto.AttachmentResponse;
import io.skala.backend.domain.review.dto.ExplanationDecisionRequest;
import io.skala.backend.domain.review.dto.ReviewItemDetailResponse;
import io.skala.backend.domain.review.service.ExplanationAnswerService;
import io.skala.backend.domain.review.service.ExplanationDecisionService;
import io.skala.backend.domain.review.service.ReviewTreeService;
import io.skala.backend.domain.review.value.GapComparisonType;
import io.skala.backend.domain.review.service.ReviewItemQueryService;
import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.file.service.FileStorage;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** PROOF-02~06 소명 대상 목록·상세·작성·첨부, GAP-06 Gap 계층형 표, GAP-08 상세, GAP-11 소명 판단 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/review-items")
public class ReviewItemController {

    private final ReviewItemQueryService reviewItemQueryService;
    private final ExplanationAnswerService explanationAnswerService;
    private final ExplanationDecisionService explanationDecisionService;
    private final ReviewTreeService reviewTreeService;
    private final FileStorage fileStorage;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    /** view=ORG: PROOF-02 조직 소명 대상 목록, view=PLANNER: GAP-06 Gap 계층형 표 */
    @GetMapping
    public Object list(@RequestParam String view,
                       @RequestParam(required = false) Long submissionId,
                       @RequestParam(required = false) Long requestId,
                       @RequestParam(required = false) Long organizationId,
                       @RequestParam(required = false) GapComparisonType comparison,
                       @RequestParam(required = false) String tab,
                       @RequestParam(required = false) String sort,
                       @RequestParam(required = false) String order,
                       @RequestParam(required = false) String q) {
        AuthenticatedUser user = currentUser.get();
        if ("PLANNER".equals(view)) {
            return reviewTreeService.tree(user, requestId, organizationId, comparison, tab, sort, order, q);
        }
        if (!"ORG".equals(view)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "view는 ORG 또는 PLANNER입니다.");
        }
        if (submissionId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "submissionId가 필요합니다.");
        }
        return reviewItemQueryService.listForOrg(submissionId, user);
    }

    @GetMapping("/{reviewItemId}")
    public ReviewItemDetailResponse detail(@PathVariable Long reviewItemId) {
        return reviewItemQueryService.detail(reviewItemId, currentUser.get());
    }

    @PutMapping("/{reviewItemId}/answer")
    public ResponseEntity<String> answer(@PathVariable Long reviewItemId,
                                         @RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                         @Valid @RequestBody AnswerRequest request) {
        AuthenticatedUser user = currentUser.get();
        return idempotency.execute(user.id(), "PUT /api/v1/review-items/" + reviewItemId + "/answer", idempotencyKey,
                request, () -> ResponseEntity.ok(explanationAnswerService.answer(reviewItemId, user, request.answer(),
                        request.expectedStateToken())));
    }

    /** GAP-11 소명 수락 / 재요청 */
    @PostMapping("/{reviewItemId}/decisions")
    public ResponseEntity<String> decide(@PathVariable Long reviewItemId,
                                         @RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                         @Valid @RequestBody ExplanationDecisionRequest request) {
        AuthenticatedUser user = currentUser.get();
        return idempotency.execute(user.id(), "POST /api/v1/review-items/" + reviewItemId + "/decisions",
                idempotencyKey, request,
                () -> ResponseEntity.ok(explanationDecisionService.decide(reviewItemId, user, request)));
    }

    @GetMapping("/{reviewItemId}/attachments")
    public Map<String, List<AttachmentResponse>> attachments(@PathVariable Long reviewItemId) {
        return Map.of("items", explanationAnswerService.attachments(reviewItemId, currentUser.get()));
    }

    @PostMapping(path = "/{reviewItemId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> attach(@PathVariable Long reviewItemId,
                                         @RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                         @RequestPart("file") MultipartFile file,
                                         @RequestParam("expectedStateToken") String expectedStateToken) throws IOException {
        AuthenticatedUser user = currentUser.get();
        byte[] content = file.getBytes();
        String filename = file.getOriginalFilename();
        Map<String, Object> fingerprint = Map.of("filename", String.valueOf(filename), "sha256", sha256(content),
                "expectedStateToken", expectedStateToken);
        return idempotency.execute(user.id(), "POST /api/v1/review-items/" + reviewItemId + "/attachments",
                idempotencyKey, fingerprint, () -> ResponseEntity.status(HttpStatus.CREATED).body(
                        explanationAnswerService.attach(reviewItemId, user, filename, file.getContentType(), content,
                                expectedStateToken)));
    }

    @GetMapping("/{reviewItemId}/attachments/{attachmentId}/file")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long reviewItemId, @PathVariable Long attachmentId) {
        ExplanationAnswerService.AttachmentFile info = explanationAnswerService.file(reviewItemId, attachmentId,
                currentUser.get());
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
