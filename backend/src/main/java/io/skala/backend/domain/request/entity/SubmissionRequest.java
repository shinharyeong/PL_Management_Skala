package io.skala.backend.domain.request.entity;

import io.skala.backend.domain.master.entity.AnnualPlan;
import io.skala.backend.domain.request.value.RequestStatus;
import io.skala.backend.domain.request.value.RequestType;
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
@Table(name = "submission_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubmissionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "annual_plan_id", nullable = false)
    private AnnualPlan annualPlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private RequestType type;

    @Column(name = "month")
    private Integer month;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private RequestStatus status;

    @Column(name = "message", columnDefinition = "text")
    private String message;

    @Column(name = "due_at")
    private OffsetDateTime dueAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private AppUser requestedBy;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "period_key", nullable = false, length = 30)
    private String periodKey;
}
