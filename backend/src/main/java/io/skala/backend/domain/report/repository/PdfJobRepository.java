package io.skala.backend.domain.report.repository;

import io.skala.backend.domain.report.entity.PdfJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PdfJobRepository extends JpaRepository<PdfJob, Long> {
}
