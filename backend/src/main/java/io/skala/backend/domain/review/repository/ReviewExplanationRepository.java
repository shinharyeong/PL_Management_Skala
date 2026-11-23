package io.skala.backend.domain.review.repository;

import io.skala.backend.domain.review.entity.ReviewExplanation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewExplanationRepository extends JpaRepository<ReviewExplanation, Long> {

    @Query("select e from ReviewExplanation e join fetch e.reviewItem i where i.submission.id = :submissionId")
    List<ReviewExplanation> findAllBySubmissionId(@Param("submissionId") Long submissionId);
}
