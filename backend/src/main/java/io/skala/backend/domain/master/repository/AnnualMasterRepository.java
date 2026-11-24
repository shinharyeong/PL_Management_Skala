package io.skala.backend.domain.master.repository;

import io.skala.backend.domain.master.entity.AnnualMaster;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnualMasterRepository extends JpaRepository<AnnualMaster, Long> {
}
