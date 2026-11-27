package io.skala.backend.domain.financial.repository;

import io.skala.backend.domain.financial.entity.AccountItem;
import io.skala.backend.domain.financial.value.AccountSourceType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountItemRepository extends JpaRepository<AccountItem, Long> {

    Optional<AccountItem> findBySourceTypeAndSourceCodeAndSourceName(AccountSourceType sourceType, String sourceCode,
                                                                      String sourceName);

    @Modifying
    @Query(value = """
            insert into account_item(source_type, source_code, source_name)
            values (:type, :code, :name)
            on conflict (source_type, source_code, source_name) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(@Param("type") String type, @Param("code") String code, @Param("name") String name);
}
