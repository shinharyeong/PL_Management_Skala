package io.skala.backend.domain.master.repository;

import io.skala.backend.domain.master.entity.MasterVersion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MasterVersionRepository extends JpaRepository<MasterVersion, Long> {
}
