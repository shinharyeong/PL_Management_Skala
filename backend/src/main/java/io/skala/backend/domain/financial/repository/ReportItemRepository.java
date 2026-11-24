package io.skala.backend.domain.financial.repository;

import io.skala.backend.domain.financial.entity.ReportItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReportItemRepository extends JpaRepository<ReportItem, Long> {

    List<ReportItem> findAllByCodeIn(Collection<String> codes);

    @Query("select coalesce(max(r.displayOrder), 0) from ReportItem r")
    int findMaxDisplayOrder();
}
