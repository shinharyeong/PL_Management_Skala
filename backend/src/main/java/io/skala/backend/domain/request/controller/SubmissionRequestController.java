package io.skala.backend.domain.request.controller;

import io.skala.backend.domain.request.dto.CreateSubmissionRequestRequest;
import io.skala.backend.domain.request.dto.RequestDetailResponse;
import io.skala.backend.domain.request.dto.SubmissionRequestResponse;
import io.skala.backend.domain.request.service.SubmissionRequestService;
import io.skala.backend.domain.request.value.RequestStatus;
import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.response.PageResponse;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
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

/** REQ-02~04 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/submission-requests")
public class SubmissionRequestController {

    private final SubmissionRequestService requestService;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                         @Valid @RequestBody CreateSubmissionRequestRequest request) {
        AuthenticatedUser actor = currentUser.get();
        return idempotency.execute(actor.id(), "POST /api/v1/submission-requests", idempotencyKey, request, () -> {
            SubmissionRequestResponse created = requestService.create(actor, request);
            return ResponseEntity.created(URI.create("/api/v1/submission-requests/" + created.id())).body(created);
        });
    }

    @GetMapping
    public PageResponse<SubmissionRequestResponse> list(@RequestParam(required = false) RequestType type,
                                                        @RequestParam(required = false) Integer year,
                                                        @RequestParam(required = false) RequestStatus status,
                                                        @RequestParam(required = false) String cursor,
                                                        @RequestParam(required = false) Integer limit) {
        return requestService.list(currentUser.get(), type, year, status, cursor, limit);
    }

    @GetMapping("/{requestId}")
    public RequestDetailResponse detail(@PathVariable Long requestId) {
        return requestService.detail(currentUser.get(), requestId);
    }
}
