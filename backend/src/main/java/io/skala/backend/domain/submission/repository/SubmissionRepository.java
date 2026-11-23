package io.skala.backend.domain.submission.repository;

import io.skala.backend.domain.submission.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {
}
