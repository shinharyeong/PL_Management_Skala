package io.skala.backend.domain.financial.repository;

import io.skala.backend.domain.financial.entity.FinancialLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinancialLineRepository extends JpaRepository<FinancialLine, Long> {

    boolean existsBySubmissionId(Long submissionId);

    @Modifying(flushAutomatically = true)
    @Query("delete from FinancialLine l where l.submission.id = :submissionId")
    int deleteAllBySubmissionId(@Param("submissionId") Long submissionId);
}
