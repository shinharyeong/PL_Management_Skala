package io.skala.backend.domain.user.repository;

import io.skala.backend.domain.user.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {
}
