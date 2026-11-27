package io.skala.backend.domain.financial.repository;

import io.skala.backend.domain.financial.entity.ReportItem;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportItemRepository extends JpaRepository<ReportItem, Long> {

    List<ReportItem> findAllByCodeIn(Collection<String> codes);

    Optional<ReportItem> findByCode(String code);

    /** 여러 조직의 첫 제출이 겹쳐도 공통 보고 항목은 한 번만 만든다. */
    @Modifying
    @Query(value = """
            insert into report_item(code, name, parent_id, kind, is_leaf, display_order)
            values (:code, :name, :parentId, :kind, :leaf, :displayOrder)
            on conflict (code) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(@Param("code") String code, @Param("name") String name,
                        @Param("parentId") Long parentId, @Param("kind") String kind,
                        @Param("leaf") boolean leaf, @Param("displayOrder") int displayOrder);

    @Query("select coalesce(max(r.displayOrder), 0) from ReportItem r")
    int findMaxDisplayOrder();
}
