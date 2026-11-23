package io.skala.backend.domain.financial.repository;

import io.skala.backend.domain.financial.entity.ReportItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportItemRepository extends JpaRepository<ReportItem, Long> {
}
