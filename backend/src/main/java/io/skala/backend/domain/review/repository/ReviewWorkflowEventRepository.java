package io.skala.backend.domain.review.repository;

import io.skala.backend.domain.review.entity.ReviewWorkflowEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewWorkflowEventRepository extends JpaRepository<ReviewWorkflowEvent, Long> {
}
