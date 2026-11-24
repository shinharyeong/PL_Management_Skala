package io.skala.backend.domain.review.repository;

import io.skala.backend.domain.review.entity.GapComparison;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GapComparisonRepository extends JpaRepository<GapComparison, Long> {

    @Query("""
            select g from GapComparison g join fetch g.reviewItem i join fetch g.threshold
            where i.submission.id = :submissionId
            """)
    List<GapComparison> findAllBySubmissionId(@Param("submissionId") Long submissionId);

    @Modifying(flushAutomatically = true)
    @Query("delete from GapComparison g where g.reviewItem.id in "
            + "(select i.id from ReviewItem i where i.submission.id = :submissionId)")
    int deleteAllBySubmissionId(@Param("submissionId") Long submissionId);

    /** 검토 대기 제출본의 비교 결과(기준 적용 전 영향 건수 미리보기용) */
    @Query("""
            select g from GapComparison g join fetch g.reviewItem i
            where i.submission.status = io.skala.backend.domain.submission.value.SubmissionStatus.PENDING
              and i.active = true
            """)
    List<GapComparison> findAllOfPendingSubmissions();
}
