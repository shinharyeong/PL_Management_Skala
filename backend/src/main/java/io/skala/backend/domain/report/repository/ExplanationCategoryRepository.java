package io.skala.backend.domain.report.repository;

import io.skala.backend.domain.report.entity.ExplanationCategory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExplanationCategoryRepository extends JpaRepository<ExplanationCategory, Long> {
}
