package io.skala.backend.domain.review.repository;

import io.skala.backend.domain.review.entity.ReviewExplanation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewExplanationRepository extends JpaRepository<ReviewExplanation, Long> {
}
