package io.skala.backend.domain.review.repository;

import io.skala.backend.domain.review.entity.GapThreshold;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GapThresholdRepository extends JpaRepository<GapThreshold, Long> {
}
