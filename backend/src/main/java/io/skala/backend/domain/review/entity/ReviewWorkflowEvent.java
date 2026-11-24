package io.skala.backend.domain.review.entity;

import io.skala.backend.domain.review.value.WorkflowAction;
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
@Table(name = "review_workflow_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewWorkflowEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_item_id", nullable = false)
    private ReviewItem reviewItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private WorkflowAction action;

    @Column(name = "explanation_round", nullable = false)
    private Integer explanationRound;

    @Column(name = "note", length = 4000)
    private String note;

    @Column(name = "due_at")
    private OffsetDateTime dueAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "acted_by", nullable = false)
    private AppUser actedBy;

    @Column(name = "acted_at", nullable = false)
    private OffsetDateTime actedAt;

    public static ReviewWorkflowEvent of(ReviewItem item, WorkflowAction action, int explanationRound, String note,
                                         OffsetDateTime dueAt, AppUser by, OffsetDateTime at) {
        ReviewWorkflowEvent event = new ReviewWorkflowEvent();
        event.reviewItem = item;
        event.action = action;
        event.explanationRound = explanationRound;
        event.note = note;
        event.dueAt = dueAt;
        event.actedBy = by;
        event.actedAt = at;
        return event;
    }
}
