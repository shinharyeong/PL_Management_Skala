package io.skala.backend.domain.submission.controller;

import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.domain.submission.dto.DecisionRequest;
import io.skala.backend.domain.submission.service.SubmissionDecisionService;
import io.skala.backend.domain.submission.service.SubmissionQueryService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HIST-01 · REQ-05 제출본 목록, REQ-06 회신 수락·재제출 요청 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/submissions")
public class SubmissionDecisionController {

    private final SubmissionQueryService submissionQueryService;
    private final SubmissionDecisionService submissionDecisionService;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    @GetMapping
    public SubmissionQueryService.SubmissionPage list(@RequestParam(required = false) RequestType type,
                                                      @RequestParam(required = false) Integer year,
                                                      @RequestParam(required = false) Integer month,
                                                      @RequestParam(required = false) String displayStatus,
                                                      @RequestParam(required = false) Long requestId,
                                                      @RequestParam(required = false) Long organizationId,
                                                      @RequestParam(required = false) String cursor,
                                                      @RequestParam(required = false) Integer limit) {
        return submissionQueryService.list(currentUser.get(), type, year, month, displayStatus, requestId,
                organizationId, cursor, limit);
    }

    @PostMapping("/{submissionId}/decisions")
    public ResponseEntity<String> decide(@PathVariable Long submissionId,
                                         @RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                         @Valid @RequestBody DecisionRequest request) {
        AuthenticatedUser user = currentUser.get();
        return idempotency.execute(user.id(), "POST /api/v1/submissions/" + submissionId + "/decisions",
                idempotencyKey, request,
                () -> ResponseEntity.ok(submissionDecisionService.decide(submissionId, user, request)));
    }
}
