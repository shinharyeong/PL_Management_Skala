package io.skala.backend.domain.review.service;

import io.skala.backend.domain.review.dto.SubmissionReviewDetail;
import io.skala.backend.domain.review.entity.ReviewExplanation;
import io.skala.backend.domain.review.entity.ReviewItem;
import io.skala.backend.domain.review.repository.ReviewExplanationRepository;
import io.skala.backend.domain.review.repository.ReviewItemRepository;
import io.skala.backend.domain.submission.entity.Submission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** HIST-02: 제출 소명, 이어받은 소명, 반려 항목 */
@Service
@RequiredArgsConstructor
public class SubmissionReviewDetailService {

    private final ReviewItemRepository reviewItemRepository;
    private final ReviewExplanationRepository reviewExplanationRepository;
    private final ReviewItemQueryService reviewItemQueryService;
    private final ReviewComparison reviewComparison;

    @Transactional(readOnly = true)
    public SubmissionReviewDetail detail(Submission submission) {
        List<ReviewItem> items = reviewItemRepository.findAllWithReportItemBySubmissionId(submission.getId()).stream()
                .sorted(Comparator.comparing((ReviewItem i) -> i.getReportItem().getDisplayOrder())
                        .thenComparing(ReviewItem::getMonth).thenComparing(ReviewItem::getScenario))
                .toList();
        Map<Long, List<ReviewExplanation>> byItem = reviewExplanationRepository.findAllBySubmissionId(submission.getId())
                .stream().sorted(Comparator.comparing(ReviewExplanation::getRound))
                .collect(Collectors.groupingBy(e -> e.getReviewItem().getId()));

        List<SubmissionReviewDetail.Written> written = new ArrayList<>();
        List<io.skala.backend.domain.review.dto.ExplanationResponse> inherited = new ArrayList<>();
        List<SubmissionReviewDetail.Rejected> rejected = new ArrayList<>();
        ReviewComparison.View view = null;
        for (ReviewItem item : items) {
            List<ReviewExplanation> explanations = byItem.getOrDefault(item.getId(), List.of());
            ReviewExplanation round1 = explanations.stream()
                    .filter(e -> e.getRound() == ExplanationTargetService.ORG_ROUND).findFirst().orElse(null);
            if (Boolean.TRUE.equals(item.getActive()) && round1 != null) {
                if (round1.isInherited()) {
                    inherited.add(reviewItemQueryService.toResponse(round1));
                } else if (round1.isAnswered()) {
                    written.add(new SubmissionReviewDetail.Written(String.valueOf(item.getId()),
                            item.getReportItem().getName(), item.getMonth(), item.getScenario(), round1.getAnswer()));
                }
            }
            if (Boolean.TRUE.equals(item.getRejected())) {
                if (view == null) {
                    view = reviewComparison.build(submission);
                }
                ReviewExplanation last = explanations.isEmpty() ? null : explanations.get(explanations.size() - 1);
                rejected.add(new SubmissionReviewDetail.Rejected(String.valueOf(item.getId()),
                        item.getReportItem().getName() + " · " + ReviewTreeService.leafName(item.getMonth(), item.getScenario()),
                        firstCell(view, item), submission.getRejectReason(),
                        last == null ? null : reviewItemQueryService.toResponse(last)));
            }
        }
        return new SubmissionReviewDetail(written, inherited, rejected);
    }

    private static String firstCell(ReviewComparison.View view, ReviewItem item) {
        ChangeDetector.Aggregate a = view.current().get(ReviewItemQueryService.key(item));
        if (a == null || a.cells().isEmpty()) {
            return null;
        }
        return a.cells().stream().map(c -> c.sheet() + "!" + c.cell()).sorted().findFirst().orElse(null);
    }
}
