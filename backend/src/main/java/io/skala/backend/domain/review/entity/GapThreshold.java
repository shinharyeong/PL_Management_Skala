package io.skala.backend.domain.review.entity;

import io.skala.backend.domain.review.value.GapComparisonType;
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
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "gap_threshold")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GapThreshold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "comparison", nullable = false, length = 30)
    private GapComparisonType comparison;

    @Column(name = "percent", nullable = false, precision = 30, scale = 6)
    private BigDecimal percent;

    @Column(name = "amount", nullable = false, precision = 30, scale = 6)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applied_by", nullable = false)
    private AppUser appliedBy;

    @Column(name = "applied_at", nullable = false)
    private OffsetDateTime appliedAt;

    @Column(name = "percent_enabled", nullable = false)
    private Boolean percentEnabled = true;

    @Column(name = "amount_enabled", nullable = false)
    private Boolean amountEnabled = true;
}
