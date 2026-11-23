package io.skala.backend.domain.master.repository;

import io.skala.backend.domain.master.entity.MasterSource;
import io.skala.backend.domain.master.entity.MasterSourceId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MasterSourceRepository extends JpaRepository<MasterSource, MasterSourceId> {
}
