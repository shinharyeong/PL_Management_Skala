package io.skala.backend.domain.review.entity;

import io.skala.backend.domain.review.value.ExplanationVerdict;
import io.skala.backend.domain.user.entity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "review_explanation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewExplanation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_item_id", nullable = false)
    private ReviewItem reviewItem;

    @Column(name = "round", nullable = false)
    private Integer round;

    @Column(name = "request_note", length = 4000)
    private String requestNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private AppUser requestedBy;

    @Column(name = "requested_at")
    private OffsetDateTime requestedAt;

    @Column(name = "answer", length = 4000)
    private String answer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answered_by")
    private AppUser answeredBy;

    @Column(name = "answered_at")
    private OffsetDateTime answeredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 20)
    private ExplanationVerdict verdict;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private AppUser reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "review_note", length = 4000)
    private String reviewNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inherited_from_id")
    private ReviewExplanation inheritedFrom;

    @Column(name = "due_at")
    private OffsetDateTime dueAt;

    @Column(name = "attachment_revision", nullable = false)
    private Long attachmentRevision = 0L;

    /** 조직 담당자가 제출 전에 써야 하는 소명 자리. 재제출이면 반려 사유를 요청 메모로 옮겨 둔다. */
    public static ReviewExplanation pending(ReviewItem reviewItem, int round, String requestNote) {
        ReviewExplanation explanation = new ReviewExplanation();
        explanation.reviewItem = reviewItem;
        explanation.round = round;
        explanation.requestNote = requestNote;
        explanation.verdict = ExplanationVerdict.PENDING;
        explanation.attachmentRevision = 0L;
        return explanation;
    }

    /** 값이 그대로인 항목은 지난 승인 소명을 그대로 이어받는다(FR-PROOF-007). */
    public static ReviewExplanation inherit(ReviewItem reviewItem, int round, ReviewExplanation accepted) {
        ReviewExplanation explanation = new ReviewExplanation();
        explanation.reviewItem = reviewItem;
        explanation.round = round;
        explanation.answer = accepted.getAnswer();
        explanation.answeredBy = accepted.getAnsweredBy();
        explanation.answeredAt = accepted.getAnsweredAt();
        explanation.verdict = ExplanationVerdict.ACCEPTED;
        explanation.reviewedBy = accepted.getReviewedBy();
        explanation.reviewedAt = accepted.getReviewedAt();
        explanation.inheritedFrom = accepted.getInheritedFrom() != null ? accepted.getInheritedFrom() : accepted;
        explanation.attachmentRevision = 0L;
        return explanation;
    }

    public boolean isAnswered() {
        return answer != null && !answer.isBlank();
    }

    /** 이어받았던 소명이 더 이상 맞지 않으면(값이 바뀜) 새로 써야 하는 자리로 되돌린다. */
    public void resetToPending(String requestNote) {
        this.requestNote = requestNote;
        this.answer = null;
        this.answeredBy = null;
        this.answeredAt = null;
        this.verdict = ExplanationVerdict.PENDING;
        this.reviewedBy = null;
        this.reviewedAt = null;
        this.reviewNote = null;
        this.inheritedFrom = null;
    }

    public boolean isInherited() {
        return inheritedFrom != null;
    }

    /** 소명 답변 작성·수정 */
    public void answer(String text, AppUser by, OffsetDateTime at) {
        this.answer = text;
        this.answeredBy = by;
        this.answeredAt = at;
    }

    /** 첨부가 바뀔 때마다 올려 상태 토큰이 달라지게 한다. */
    public void touchAttachments() {
        this.attachmentRevision = attachmentRevision + 1;
    }

    /** 경영기획팀이 소명을 요청한 새 회차(GAP-07·11) */
    public static ReviewExplanation requested(ReviewItem reviewItem, int round, String note, AppUser by,
                                              OffsetDateTime at, OffsetDateTime dueAt) {
        ReviewExplanation explanation = pending(reviewItem, round, note);
        explanation.requestedBy = by;
        explanation.requestedAt = at;
        explanation.dueAt = dueAt;
        return explanation;
    }

    /** 소명 판단(GAP-11): 수락이면 ACCEPTED, 재요청이면 REJECTED */
    public void decide(ExplanationVerdict verdict, String note, AppUser by, OffsetDateTime at) {
        this.verdict = verdict;
        this.reviewNote = note;
        this.reviewedBy = by;
        this.reviewedAt = at;
    }
}
