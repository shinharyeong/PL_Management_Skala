package io.skala.backend.domain.review.controller;

import io.skala.backend.domain.review.dto.GapThresholdListResponse;
import io.skala.backend.domain.review.dto.GapThresholdRequest;
import io.skala.backend.domain.review.service.GapThresholdService;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GAP-03~05 Gap 기준 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/gap-thresholds")
public class GapThresholdController {

    private final GapThresholdService gapThresholdService;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    @GetMapping("/current")
    public GapThresholdListResponse current() {
        return gapThresholdService.current(currentUser.get());
    }

    /** 조회성 POST(상태를 바꾸지 않음, Idempotency-Key 불필요) */
    @PostMapping("/preview")
    public Map<String, Integer> preview(@Valid @RequestBody GapThresholdRequest request) {
        return Map.of("affectedCount", gapThresholdService.preview(currentUser.get(), request));
    }

    @PostMapping
    public ResponseEntity<String> apply(@RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                        @Valid @RequestBody GapThresholdRequest request) {
        AuthenticatedUser user = currentUser.get();
        if (request.expectedStateToken() == null || request.expectedStateToken().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "expectedStateToken이 필요합니다.");
        }
        return idempotency.execute(user.id(), "POST /api/v1/gap-thresholds", idempotencyKey, request, () -> {
            var body = gapThresholdService.apply(user, request);
            return ResponseEntity.created(URI.create("/api/v1/gap-thresholds/current")).body(body);
        });
    }
}
