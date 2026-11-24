package io.skala.backend.domain.report.repository;

import io.skala.backend.domain.report.entity.ExplanationClassification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExplanationClassificationRepository extends JpaRepository<ExplanationClassification, Long> {
}
