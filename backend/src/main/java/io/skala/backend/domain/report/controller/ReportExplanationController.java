package io.skala.backend.domain.report.controller;

import io.skala.backend.domain.report.dto.ClassificationRequest;
import io.skala.backend.domain.report.dto.ExplanationCategoryResponse;
import io.skala.backend.domain.report.dto.ReportExplanationResponse;
import io.skala.backend.domain.report.service.ReportExplanationService;
import io.skala.backend.domain.review.value.GapComparisonType;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** RPT-12~14 Gap 설명 · 소명 사유 유형 분류(FR-RPT-007). 경영기획팀만 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/report")
public class ReportExplanationController {

    private final ReportExplanationService reportExplanationService;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    @GetMapping("/explanation-categories")
    public ExplanationCategoryResponse categories(@RequestParam(required = false) Integer tableVersion) {
        return reportExplanationService.categories(currentUser.get(), tableVersion);
    }

    @GetMapping("/explanations")
    public ReportExplanationResponse explanations(@RequestParam int year, @RequestParam int month,
                                                  @RequestParam GapComparisonType comparison) {
        return reportExplanationService.explanations(currentUser.get(), year, month, comparison);
    }

    /** categoryId가 null이면 분류에서 뺀다. comparison은 합계를 돌려줄 블록(생략하면 그 항목이 넘은 첫 비교) */
    @PutMapping("/explanations/{explanationId}/classification")
    public ResponseEntity<String> classify(@PathVariable long explanationId,
                                           @RequestParam(required = false) GapComparisonType comparison,
                                           @RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                           @RequestBody ClassificationRequest request) {
        AuthenticatedUser user = currentUser.get();
        return idempotency.execute(user.id(), "PUT /api/v1/report/explanations/" + explanationId + "/classification",
                idempotencyKey, request, () -> ResponseEntity.ok(
                        reportExplanationService.classify(user, explanationId, request.categoryId(), comparison)));
    }
}
