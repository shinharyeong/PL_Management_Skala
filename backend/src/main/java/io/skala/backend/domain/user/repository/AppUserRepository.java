package io.skala.backend.domain.user.repository;

import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.value.UserRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    List<AppUser> findAllByRoleAndActiveTrue(UserRole role);

    Optional<AppUser> findByEmailIgnoreCase(String email);
}
