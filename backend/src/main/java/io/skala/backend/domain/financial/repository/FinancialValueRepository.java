package io.skala.backend.domain.financial.repository;

import io.skala.backend.domain.financial.entity.FinancialValue;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinancialValueRepository extends JpaRepository<FinancialValue, Long> {

    /** 제출본의 모든 금액을 행·보고 항목과 함께 읽는다(변경 감지·Gap 계산용). */
    @Query("""
            select v from FinancialValue v
            join fetch v.line l
            join fetch l.reportItem r
            where l.submission.id = :submissionId
            """)
    List<FinancialValue> findAllWithLineBySubmissionId(@Param("submissionId") Long submissionId);

    @Modifying(flushAutomatically = true)
    @Query("delete from FinancialValue v where v.line.id in "
            + "(select l.id from FinancialLine l where l.submission.id = :submissionId)")
    int deleteAllBySubmissionId(@Param("submissionId") Long submissionId);
}
