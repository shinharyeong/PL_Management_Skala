package io.skala.backend.domain.request.repository;

import io.skala.backend.domain.request.entity.SubmissionRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionRequestRepository extends JpaRepository<SubmissionRequest, Long> {
}
