package io.skala.backend.domain.request.repository;

import io.skala.backend.domain.request.entity.RequestTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestTemplateRepository extends JpaRepository<RequestTemplate, Long> {
}
