package io.skala.backend.domain.master.repository;

import io.skala.backend.domain.master.entity.AnnualPlan;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnualPlanRepository extends JpaRepository<AnnualPlan, Long> {

    Optional<AnnualPlan> findByYear(int year);
}
