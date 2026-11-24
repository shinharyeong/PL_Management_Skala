package io.skala.backend.domain.review.repository;

import io.skala.backend.domain.review.entity.ReviewAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewAttachmentRepository extends JpaRepository<ReviewAttachment, Long> {

    boolean existsByExplanationId(Long explanationId);

    long countByExplanationId(Long explanationId);

    java.util.List<ReviewAttachment> findAllByExplanationIdInOrderByIdAsc(java.util.Collection<Long> explanationIds);

    /** 제출본의 모든 소명 첨부(제출할 때 보존 위치로 옮김) */
    @org.springframework.data.jpa.repository.Query("""
            select a from ReviewAttachment a join a.explanation e join e.reviewItem i
            where i.submission.id = :submissionId
            """)
    java.util.List<ReviewAttachment> findAllBySubmissionId(
            @org.springframework.data.repository.query.Param("submissionId") Long submissionId);
}
