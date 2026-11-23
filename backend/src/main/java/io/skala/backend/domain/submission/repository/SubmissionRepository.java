package io.skala.backend.domain.submission.repository;

import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    /** 같은 요청·조직에서 마지막으로 제출된(DRAFT 제외) 제출본 */
    Optional<Submission> findFirstByRequestIdAndOrganizationIdAndStatusNotOrderBySeqDesc(
            Long requestId, Long organizationId, SubmissionStatus status);

    /** 같은 연간 계획의 지정 월 월간 요청에서 조직의 최신 승인본 */
    @Query("""
            select s from Submission s, SubmissionRequest r
            where r.id = s.requestId
              and r.annualPlan.id = :annualPlanId
              and r.type = :type
              and r.month = :month
              and s.organizationId = :organizationId
              and s.status = io.skala.backend.domain.submission.value.SubmissionStatus.APPROVED
            order by s.approvedSeq desc
            """)
    List<Submission> findApprovedInMonth(@Param("annualPlanId") Long annualPlanId, @Param("type") RequestType type,
                                         @Param("month") int month, @Param("organizationId") Long organizationId,
                                         Limit limit);

    /** 지정 월 월간 요청이 이 조직을 대상으로 했는지 */
    @Query("""
            select count(t) > 0 from RequestTarget t, SubmissionRequest r
            where r.id = t.id.requestId
              and r.annualPlan.id = :annualPlanId
              and r.type = :type
              and r.month = :month
              and t.id.organizationId = :organizationId
              and t.excluded = false
            """)
    boolean existsTargetInMonth(@Param("annualPlanId") Long annualPlanId, @Param("type") RequestType type,
                                @Param("month") int month, @Param("organizationId") Long organizationId);
}
