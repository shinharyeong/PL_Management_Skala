package io.skala.backend.domain.report.repository;

import io.skala.backend.domain.report.entity.ReportTemplateVersion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportTemplateVersionRepository extends JpaRepository<ReportTemplateVersion, Long> {
}
