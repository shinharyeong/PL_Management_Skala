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

    /** 지정 월 월간 요청을 이 조직에 실제로 보냈는지(selected, 제외 안 됨) */
    @Query("""
            select count(t) > 0 from RequestTarget t, SubmissionRequest r
            where r.id = t.id.requestId
              and r.annualPlan.id = :annualPlanId
              and r.type = :type
              and r.month = :month
              and t.id.organizationId = :organizationId
              and t.excluded = false
              and t.selected = true
            """)
    boolean existsTargetInMonth(@Param("annualPlanId") Long annualPlanId, @Param("type") RequestType type,
                                @Param("month") int month, @Param("organizationId") Long organizationId);

    boolean existsByRequestIdAndOrganizationIdAndStatus(Long requestId, Long organizationId, SubmissionStatus status);

    Optional<Submission> findFirstByPreviousSubmissionIdOrderByIdDesc(Long previousSubmissionId);

    /** 같은 요청·조직에서 이 회차보다 뒤에 제출된 제출본이 있는지(반려본이 대체됐는지) */
    boolean existsByRequestIdAndOrganizationIdAndSeqGreaterThan(Long requestId, Long organizationId, Integer seq);

    Optional<Submission> findFirstByRequestIdAndOrganizationIdAndStatusOrderByApprovedSeqDesc(
            Long requestId, Long organizationId, SubmissionStatus status);

    @Query("select coalesce(max(s.seq), 0) from Submission s where s.requestId = :requestId and s.organizationId = :organizationId")
    int findMaxSeq(@Param("requestId") Long requestId, @Param("organizationId") Long organizationId);

    @Query("select coalesce(max(s.approvedSeq), 0) from Submission s "
            + "where s.requestId = :requestId and s.organizationId = :organizationId")
    int findMaxApprovedSeq(@Param("requestId") Long requestId, @Param("organizationId") Long organizationId);

    /** 조직의 제출본(초안 제외) 목록. 조건이 null이면 거르지 않는다. 최근 제출 순. */
    @Query("""
            select s from Submission s, SubmissionRequest r
            where r.id = s.requestId
              and s.organizationId = :organizationId
              and s.status <> io.skala.backend.domain.submission.value.SubmissionStatus.DRAFT
              and (:requestId is null or s.requestId = :requestId)
              and (:type is null or r.type = :type)
              and (:year is null or r.annualPlan.year = :year)
              and (:month is null or r.month = :month)
            order by s.submittedAt desc nulls last, s.id desc
            """)
    List<Submission> findSubmitted(@Param("organizationId") Long organizationId, @Param("requestId") Long requestId,
                                   @Param("type") RequestType type, @Param("year") Integer year,
                                   @Param("month") Integer month);
}
