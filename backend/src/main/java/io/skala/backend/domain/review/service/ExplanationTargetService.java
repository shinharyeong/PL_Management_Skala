package io.skala.backend.domain.review.service;

import io.skala.backend.domain.financial.repository.FinancialValueRepository;
import io.skala.backend.domain.review.entity.ReviewExplanation;
import io.skala.backend.domain.review.entity.ReviewItem;
import io.skala.backend.domain.review.repository.ReviewAttachmentRepository;
import io.skala.backend.domain.review.repository.ReviewExplanationRepository;
import io.skala.backend.domain.review.repository.ReviewItemRepository;
import io.skala.backend.domain.review.service.ChangeDetector.Aggregate;
import io.skala.backend.domain.review.service.ChangeDetector.Detection;
import io.skala.backend.domain.review.service.ChangeDetector.Key;
import io.skala.backend.domain.review.value.ExplanationVerdict;
import io.skala.backend.domain.submission.entity.Submission;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 변경 감지 결과로 review_item(소명 대상)을 만들고, 1회차 소명 자리를 준비한다(FR-PROOF-001, 007, RESUB-005).
 *
 * <ul>
 *   <li>값이 바뀐 항목 → 1회차 소명 PENDING(조직 담당자가 모두 써야 제출 가능)</li>
 *   <li>재제출이고 지난번에 반려 항목으로 고른 항목 → 값이 같아도 PENDING, 반려 사유를 요청 메모로 옮김</li>
 *   <li>값이 그대로이고 지난 버전 소명이 승인됨 → 그 소명을 이어받음(ACCEPTED, inherited_from)</li>
 * </ul>
 * 다시 실행해도 같은 결과가 되도록, 이미 쓴 소명 답변은 그대로 두고 키가 같은 항목은 갱신한다.
 */
@Service
@RequiredArgsConstructor
public class ExplanationTargetService {

    public static final String POLICY_VERSION = "change-detection/1.0";
    static final int ORG_ROUND = 1;

    private final FinancialValueRepository financialValueRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final ReviewExplanationRepository reviewExplanationRepository;
    private final ReviewAttachmentRepository reviewAttachmentRepository;

    public record Progress(int answered, int required) {

        public boolean complete() {
            return answered == required;
        }
    }

    /**
     * @param submission    검증할 DRAFT 제출본
     * @param baseline      비교 기준 제출본. 없으면 null(모든 항목 change_detected=false)
     * @param rejectedBase  기준이 반려된 제출본(재제출)이면 true
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Progress prepare(Submission submission, Submission baseline, boolean rejectedBase) {
        Map<Key, Aggregate> current = ChangeDetector.aggregate(
                financialValueRepository.findAllWithLineBySubmissionId(submission.getId()));
        Map<Key, Aggregate> before = baseline == null ? null : ChangeDetector.aggregate(
                financialValueRepository.findAllWithLineBySubmissionId(baseline.getId()));
        List<Detection> detections = ChangeDetector.detect(current, before);

        Map<Key, ReviewItem> existing = byKey(reviewItemRepository.findAllWithReportItemBySubmissionId(submission.getId()));
        Map<Long, ReviewExplanation> existingRound1 = reviewExplanationRepository.findAllBySubmissionId(submission.getId())
                .stream().filter(e -> e.getRound() == ORG_ROUND)
                .collect(Collectors.toMap(e -> e.getReviewItem().getId(), Function.identity()));

        Map<Key, ReviewItem> baselineItems = baseline == null ? Map.of()
                : byKey(reviewItemRepository.findAllWithReportItemBySubmissionId(baseline.getId()));
        Map<Long, ReviewExplanation> baselineLatest = baseline == null ? Map.of()
                : reviewExplanationRepository.findAllBySubmissionId(baseline.getId()).stream()
                .collect(Collectors.toMap(e -> e.getReviewItem().getId(), Function.identity(),
                        (a, b) -> a.getRound() >= b.getRound() ? a : b));
        String rejectNote = rejectedBase ? baseline.getRejectReason() : null;

        Set<Long> touched = new HashSet<>();
        for (Detection d : detections) {
            String context = ChangeDetector.contextFingerprint(d.reportItem().getCode(), d.key(),
                    baseline == null ? null : baseline.getId(), d.baseline());
            ReviewItem item = existing.get(d.key());
            if (item == null) {
                item = reviewItemRepository.save(ReviewItem.create(submission, d.reportItem(), d.key().year(),
                        d.key().month(), d.key().scenario(), d.current(), d.changed(), d.sourceFingerprint(),
                        context, POLICY_VERSION));
            } else {
                item.refresh(d.current(), d.changed(), d.sourceFingerprint(), context, POLICY_VERSION);
            }
            touched.add(item.getId());

            ReviewItem baseItem = baselineItems.get(d.key());
            boolean rejectedItem = rejectedBase && baseItem != null && Boolean.TRUE.equals(baseItem.getRejected());
            ReviewExplanation round1 = existingRound1.get(item.getId());
            if (d.changed() || rejectedItem) {
                String note = rejectedItem ? rejectNote : null;
                if (round1 == null) {
                    existingRound1.put(item.getId(),
                            reviewExplanationRepository.save(ReviewExplanation.pending(item, ORG_ROUND, note)));
                } else if (round1.isInherited() || round1.getVerdict() != ExplanationVerdict.PENDING) {
                    round1.resetToPending(note);
                }
                continue;
            }
            ReviewExplanation accepted = baseItem == null ? null : baselineLatest.get(baseItem.getId());
            boolean inheritable = accepted != null && accepted.getVerdict() == ExplanationVerdict.ACCEPTED
                    && accepted.isAnswered();
            if (round1 == null) {
                if (inheritable) {
                    existingRound1.put(item.getId(),
                            reviewExplanationRepository.save(ReviewExplanation.inherit(item, ORG_ROUND, accepted)));
                }
            } else if (round1.getVerdict() == ExplanationVerdict.PENDING && !round1.isAnswered()
                    && !reviewAttachmentRepository.existsByExplanationId(round1.getId())) {
                // 더 이상 소명 대상이 아니고 아무것도 쓰지 않은 자리는 지운다.
                reviewExplanationRepository.delete(round1);
                existingRound1.remove(item.getId());
                if (inheritable) {
                    existingRound1.put(item.getId(),
                            reviewExplanationRepository.save(ReviewExplanation.inherit(item, ORG_ROUND, accepted)));
                }
            }
        }
        existing.values().stream().filter(i -> !touched.contains(i.getId())).forEach(ReviewItem::deactivate);
        return progress(touched, existingRound1);
    }

    /** 조직 담당자 소명 진행 상황: 활성 항목 중 PENDING 1회차 소명 수와 그중 답을 쓴 수. */
    @Transactional(readOnly = true)
    public Progress progress(Long submissionId) {
        Set<Long> active = reviewItemRepository.findAllWithReportItemBySubmissionId(submissionId).stream()
                .filter(i -> Boolean.TRUE.equals(i.getActive())).map(ReviewItem::getId).collect(Collectors.toSet());
        Map<Long, ReviewExplanation> round1 = reviewExplanationRepository.findAllBySubmissionId(submissionId).stream()
                .filter(e -> e.getRound() == ORG_ROUND)
                .collect(Collectors.toMap(e -> e.getReviewItem().getId(), Function.identity()));
        return progress(active, round1);
    }

    private static Progress progress(Set<Long> activeItemIds, Map<Long, ReviewExplanation> round1) {
        int required = 0;
        int answered = 0;
        for (Long id : activeItemIds) {
            ReviewExplanation e = round1.get(id);
            if (e != null && e.getVerdict() == ExplanationVerdict.PENDING) {
                required++;
                if (e.isAnswered()) {
                    answered++;
                }
            }
        }
        return new Progress(answered, required);
    }

    private static Map<Key, ReviewItem> byKey(List<ReviewItem> items) {
        Map<Key, ReviewItem> map = new HashMap<>();
        items.stream().sorted(Comparator.comparing(ReviewItem::getId)).forEach(i -> map.put(
                new Key(i.getReportItem().getId(), i.getYear(), i.getMonth(), i.getScenario()), i));
        return map;
    }
}
