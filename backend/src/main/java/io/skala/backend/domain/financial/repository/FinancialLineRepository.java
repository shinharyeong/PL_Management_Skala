package io.skala.backend.domain.financial.repository;

import io.skala.backend.domain.financial.entity.FinancialLine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialLineRepository extends JpaRepository<FinancialLine, Long> {
}
