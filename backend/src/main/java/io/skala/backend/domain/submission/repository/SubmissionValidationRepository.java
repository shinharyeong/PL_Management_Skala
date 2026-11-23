package io.skala.backend.domain.submission.repository;

import io.skala.backend.domain.submission.entity.SubmissionValidation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionValidationRepository extends JpaRepository<SubmissionValidation, Long> {
}
