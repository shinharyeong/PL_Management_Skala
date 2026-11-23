package io.skala.backend.domain.request.entity;

import io.skala.backend.domain.submission.entity.Submission;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "request_target")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestTarget {

    @EmbeddedId
    private RequestTargetId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_plan_submission_id")
    private Submission confirmedPlanSubmission;

    @Column(name = "excluded", nullable = false)
    private Boolean excluded = false;

    @Column(name = "selected", nullable = false)
    private Boolean selected = true;
}
