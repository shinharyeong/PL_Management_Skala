package io.skala.backend.domain.submission.repository;

import io.skala.backend.domain.submission.entity.ParsingJob;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParsingJobRepository extends JpaRepository<ParsingJob, Long> {

    Optional<ParsingJob> findBySubmissionId(Long submissionId);
}
