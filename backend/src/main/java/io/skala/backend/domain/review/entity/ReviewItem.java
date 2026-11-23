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
}
