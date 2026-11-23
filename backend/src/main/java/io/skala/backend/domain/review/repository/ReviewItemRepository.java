package io.skala.backend.domain.review.repository;

import io.skala.backend.domain.review.entity.ReviewItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, Long> {

    @Query("select i from ReviewItem i join fetch i.reportItem where i.submission.id = :submissionId")
    List<ReviewItem> findAllWithReportItemBySubmissionId(@Param("submissionId") Long submissionId);
}
