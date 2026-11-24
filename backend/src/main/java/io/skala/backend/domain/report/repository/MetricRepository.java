package io.skala.backend.domain.report.repository;

import io.skala.backend.domain.report.entity.Metric;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetricRepository extends JpaRepository<Metric, Long> {
}
