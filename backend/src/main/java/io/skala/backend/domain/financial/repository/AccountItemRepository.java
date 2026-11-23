package io.skala.backend.domain.financial.repository;

import io.skala.backend.domain.financial.entity.AccountItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountItemRepository extends JpaRepository<AccountItem, Long> {
}
