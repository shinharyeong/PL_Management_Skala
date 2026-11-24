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

    public static SubmissionRequest open(AnnualPlan annualPlan, RequestType type, Integer month, String message,
                                         OffsetDateTime dueAt, AppUser requestedBy, OffsetDateTime requestedAt) {
        SubmissionRequest request = new SubmissionRequest();
        request.annualPlan = annualPlan;
        request.type = type;
        request.month = month;
        request.status = RequestStatus.OPEN;
        request.message = message;
        request.dueAt = dueAt;
        request.requestedBy = requestedBy;
        request.requestedAt = requestedAt;
        request.periodKey = periodKey(annualPlan.getYear(), type, month);
        return request;
    }

    /** 기간당 요청 하나를 보장하는 키. 연간 2027-PLAN, 월간 2026-10 */
    public static String periodKey(int year, RequestType type, Integer month) {
        return type == RequestType.ANNUAL_PLAN ? year + "-PLAN" : "%d-%02d".formatted(year, month);
    }

    /** 화면 제목. 예: 2027년 연간 계획, 2026년 10월 월간 시트 */
    public String title() {
        int year = annualPlan.getYear();
        return type == RequestType.ANNUAL_PLAN ? year + "년 연간 계획" : year + "년 " + month + "월 월간 시트";
    }
}
