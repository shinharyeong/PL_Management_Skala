package io.skala.backend.domain.review.repository;

import io.skala.backend.domain.review.entity.ReviewItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, Long> {

    @Query("select i from ReviewItem i join fetch i.reportItem where i.submission.id = :submissionId")
    List<ReviewItem> findAllWithReportItemBySubmissionId(@Param("submissionId") Long submissionId);

    /** 항목이 속한 제출본 ID(항목을 읽기 전에 제출본 행을 먼저 잠글 때 쓴다) */
    @Query("select i.submission.id from ReviewItem i where i.id = :id")
    java.util.Optional<Long> findSubmissionId(@Param("id") Long id);
}
