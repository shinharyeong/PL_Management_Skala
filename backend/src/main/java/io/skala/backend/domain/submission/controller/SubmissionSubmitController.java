package io.skala.backend.domain.submission.controller;

import io.skala.backend.domain.submission.dto.SubmitPreviewResponse;
import io.skala.backend.domain.submission.dto.SubmitRequest;
import io.skala.backend.domain.submission.service.SubmissionSubmitService;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** PROOF-07 제출 내용 확인, PROOF-08 제출하기 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/submissions")
public class SubmissionSubmitController {

    private final SubmissionSubmitService submissionSubmitService;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    @GetMapping("/{submissionId}/submit-preview")
    public SubmitPreviewResponse preview(@PathVariable Long submissionId) {
        return submissionSubmitService.preview(submissionId, currentUser.get());
    }

    @PostMapping("/{submissionId}/submit")
    public ResponseEntity<String> submit(@PathVariable Long submissionId,
                                         @RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                         @Valid @RequestBody SubmitRequest request) {
        AuthenticatedUser user = currentUser.get();
        return idempotency.execute(user.id(), "POST /api/v1/submissions/" + submissionId + "/submit", idempotencyKey,
                request, () -> ResponseEntity.ok(submissionSubmitService.submit(submissionId, user,
                        request.replyMessage(), request.expectedStateToken())));
    }
}
