package io.skala.backend.domain.review.repository;

import io.skala.backend.domain.review.entity.ReviewAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewAttachmentRepository extends JpaRepository<ReviewAttachment, Long> {
}
