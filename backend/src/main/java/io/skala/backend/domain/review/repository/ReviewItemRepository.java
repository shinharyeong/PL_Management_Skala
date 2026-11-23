package io.skala.backend.domain.review.repository;

import io.skala.backend.domain.review.entity.ReviewItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, Long> {
}
