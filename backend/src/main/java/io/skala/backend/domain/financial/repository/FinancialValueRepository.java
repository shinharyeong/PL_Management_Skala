package io.skala.backend.domain.financial.repository;

import io.skala.backend.domain.financial.entity.FinancialValue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialValueRepository extends JpaRepository<FinancialValue, Long> {
}
