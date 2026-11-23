package io.skala.backend.domain.financial.repository;

import io.skala.backend.domain.financial.entity.AccountItem;
import io.skala.backend.domain.financial.value.AccountSourceType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountItemRepository extends JpaRepository<AccountItem, Long> {

    Optional<AccountItem> findBySourceTypeAndSourceCodeAndSourceName(AccountSourceType sourceType, String sourceCode,
                                                                      String sourceName);
}
