package io.skala.backend.domain.review.entity;

import io.skala.backend.domain.financial.entity.ReportItem;
import io.skala.backend.domain.financial.value.Scenario;
import io.skala.backend.domain.review.value.WorkflowState;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.global.entity.BaseTimeEntity;
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
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "review_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_item_id", nullable = false)
    private ReportItem reportItem;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Enumerated(EnumType.STRING)
    @Column(name = "scenario", nullable = false, length = 20)
    private Scenario scenario;

    @Column(name = "amount_present", nullable = false)
    private Boolean amountPresent;

    @Column(name = "current_amount", precision = 48, scale = 6)
    private BigDecimal currentAmount;

    @Column(name = "change_detected", nullable = false)
    private Boolean changeDetected;

    @Column(name = "gap_exceeded", nullable = false)
    private Boolean gapExceeded;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_fingerprint", nullable = false, length = 64)
    private String sourceFingerprint;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "context_fingerprint", nullable = false, length = 64)
    private String contextFingerprint;

    @Column(name = "current_round", nullable = false)
    private Integer currentRound;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "review_policy_version", nullable = false, length = 80)
    private String reviewPolicyVersion = "legacy/1.0";

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_state", nullable = false, length = 30)
    private WorkflowState workflowState = WorkflowState.NEEDS_REVIEW;

    @Column(name = "workflow_note", length = 4000)
    private String workflowNote;

    @Column(name = "rejected", nullable = false)
    private Boolean rejected = false;

    public static ReviewItem create(Submission submission, ReportItem reportItem, int year, int month,
                                    Scenario scenario, BigDecimal currentAmount, boolean changeDetected,
                                    String sourceFingerprint, String contextFingerprint, String policyVersion) {
        ReviewItem item = new ReviewItem();
        item.submission = submission;
        item.reportItem = reportItem;
        item.year = year;
        item.month = month;
        item.scenario = scenario;
        item.amountPresent = currentAmount != null;
        item.currentAmount = currentAmount;
        item.changeDetected = changeDetected;
        item.gapExceeded = false;
        item.sourceFingerprint = sourceFingerprint;
        item.contextFingerprint = contextFingerprint;
        item.currentRound = 1;
        item.active = true;
        item.reviewPolicyVersion = policyVersion;
        item.workflowState = WorkflowState.NEEDS_REVIEW;
        item.rejected = false;
        return item;
    }

    /** 재검증 때 같은 키의 항목을 새 계산 결과로 바꾼다. */
    public void refresh(BigDecimal currentAmount, boolean changeDetected, String sourceFingerprint,
                        String contextFingerprint, String policyVersion) {
        this.amountPresent = currentAmount != null;
        this.currentAmount = currentAmount;
        this.changeDetected = changeDetected;
        this.sourceFingerprint = sourceFingerprint;
        this.contextFingerprint = contextFingerprint;
        this.reviewPolicyVersion = policyVersion;
        this.active = true;
    }

    /** 재검증 결과에 더 이상 없는 항목은 지우지 않고 비활성으로 둔다(소명·첨부 이력 보존). */
    public void deactivate() {
        this.active = false;
    }

    /** 재제출 요청 때 경영기획팀이 고른 반려 항목 */
    public void markRejected() {
        this.rejected = true;
    }

    /** 소명을 다시 요청해 새 회차를 연다. */
    public int openNextRound() {
        this.currentRound = currentRound + 1;
        this.workflowState = WorkflowState.REQUESTED;
        return currentRound;
    }

    /** 제출할 때 계산한 Gap 기준 초과 여부 */
    public void markGap(boolean exceeded) {
        this.gapExceeded = exceeded;
    }

    /** 검토 대상으로 고름(GAP-07 SELECT) */
    public void select() {
        this.workflowState = WorkflowState.SELECTED;
    }

    /** 검토에서 제외(GAP-07 EXCLUDE). 사유를 남긴다. */
    public void exclude(String note) {
        this.workflowState = WorkflowState.EXCLUDED;
        this.workflowNote = note;
    }

    /** 소명이 없던 항목에 현재 회차로 소명을 요청(GAP-07 REQUEST_EXPLANATION) */
    public void markRequested() {
        this.workflowState = WorkflowState.REQUESTED;
    }
}
