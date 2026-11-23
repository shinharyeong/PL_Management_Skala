package io.skala.backend.domain.review.repository;

import io.skala.backend.domain.review.entity.GapComparison;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GapComparisonRepository extends JpaRepository<GapComparison, Long> {
}
