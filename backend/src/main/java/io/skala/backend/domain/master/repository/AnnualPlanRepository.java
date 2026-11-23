package io.skala.backend.domain.master.repository;

import io.skala.backend.domain.master.entity.AnnualPlan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnualPlanRepository extends JpaRepository<AnnualPlan, Long> {
}
