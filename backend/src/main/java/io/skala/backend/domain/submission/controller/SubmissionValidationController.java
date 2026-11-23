package io.skala.backend.domain.submission.controller;

import io.skala.backend.domain.submission.dto.ValidationRequest;
import io.skala.backend.domain.submission.dto.ValidationResponse;
import io.skala.backend.domain.submission.service.SubmissionValidationService;
import io.skala.backend.domain.submission.support.CurrentActor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/submissions")
public class SubmissionValidationController {

    private final SubmissionValidationService submissionValidationService;
    private final CurrentActor currentActor;

    /**
     * PROOF-01 비교 기준 대비 변경 감지·소명 대상 생성.
     * TODO(global): Idempotency-Key 재전송 처리(idempotency_record)는 공통 필터가 생기면 그쪽에서 맡는다.
     */
    @PostMapping("/{submissionId}/validation")
    public ValidationResponse validate(@PathVariable Long submissionId,
                                       @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
                                       @Valid @RequestBody ValidationRequest request) {
        return submissionValidationService.validate(submissionId, request.expectedStateToken(), currentActor.get());
    }
}
