package io.skala.backend.domain.user.repository;

import io.skala.backend.domain.user.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
}
