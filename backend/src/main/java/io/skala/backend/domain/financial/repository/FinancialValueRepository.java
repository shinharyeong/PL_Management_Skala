package io.skala.backend.domain.financial.repository;

import io.skala.backend.domain.financial.entity.FinancialValue;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinancialValueRepository extends JpaRepository<FinancialValue, Long> {

    /**
     * 제출본의 요청 연도 금액을 행·보고 항목과 함께 읽는다(변경 감지·Gap·버전 비교용).
     * 양식의 전년 실적 등 이전 연도 열은 참고값으로 저장만 하고 검토 대상에서 뺀다(#59).
     */
    @Query("""
            select v from FinancialValue v
            join fetch v.line l
            join fetch l.reportItem r
            where l.submission.id = :submissionId
              and v.year = (select q.annualPlan.year from SubmissionRequest q
                            where q.id = l.submission.requestId)
            """)
    List<FinancialValue> findRequestYearValuesBySubmissionId(@Param("submissionId") Long submissionId);

    @Modifying(flushAutomatically = true)
    @Query("delete from FinancialValue v where v.line.id in "
            + "(select l.id from FinancialLine l where l.submission.id = :submissionId)")
    int deleteAllBySubmissionId(@Param("submissionId") Long submissionId);
}
