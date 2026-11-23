package io.skala.backend.domain.review.entity;

import io.skala.backend.domain.financial.value.Scenario;
import io.skala.backend.domain.review.value.GapBasis;
import io.skala.backend.domain.submission.entity.Submission;
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

@Getter
@Entity
@Table(name = "gap_comparison")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GapComparison {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_item_id", nullable = false)
    private ReviewItem reviewItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "basis", nullable = false, length = 20)
    private GapBasis basis;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "baseline_submission_id", nullable = false)
    private Submission baselineSubmission;

    @Enumerated(EnumType.STRING)
    @Column(name = "baseline_scenario", nullable = false, length = 20)
    private Scenario baselineScenario;

    @Column(name = "baseline_amount", nullable = false, precision = 48, scale = 6)
    private BigDecimal baselineAmount;

    @Column(name = "current_amount", nullable = false, precision = 48, scale = 6)
    private BigDecimal currentAmount;

    @Column(name = "gap_amount", nullable = false, precision = 49, scale = 6)
    private BigDecimal gapAmount;

    @Column(name = "gap_rate", precision = 60, scale = 8)
    private BigDecimal gapRate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "threshold_id", nullable = false)
    private GapThreshold threshold;

    @Column(name = "exceeded", nullable = false)
    private Boolean exceeded;
}
