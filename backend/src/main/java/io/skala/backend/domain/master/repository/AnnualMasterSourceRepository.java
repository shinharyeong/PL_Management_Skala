package io.skala.backend.domain.master.repository;

import io.skala.backend.domain.master.entity.AnnualMasterSource;
import io.skala.backend.domain.master.entity.AnnualMasterSourceId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnualMasterSourceRepository extends JpaRepository<AnnualMasterSource, AnnualMasterSourceId> {
}
