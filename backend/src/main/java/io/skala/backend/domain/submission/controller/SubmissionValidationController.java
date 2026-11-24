package io.skala.backend.domain.submission.controller;

import io.skala.backend.domain.submission.dto.ValidationRequest;
import io.skala.backend.domain.submission.service.SubmissionValidationService;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/submissions")
public class SubmissionValidationController {

    private final SubmissionValidationService submissionValidationService;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    /** PROOF-01 비교 기준 대비 변경 감지·소명 대상 생성 */
    @PostMapping("/{submissionId}/validation")
    public ResponseEntity<String> validate(@PathVariable Long submissionId,
                                           @RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                           @Valid @RequestBody ValidationRequest request) {
        AuthenticatedUser actor = currentUser.get();
        return idempotency.execute(actor.id(), "POST /api/v1/submissions/" + submissionId + "/validation",
                idempotencyKey, request, () -> ResponseEntity.ok(
                        submissionValidationService.validate(submissionId, request.expectedStateToken(), actor)));
    }
}
