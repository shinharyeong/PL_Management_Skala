package io.skala.backend.domain.review.repository;

import io.skala.backend.domain.review.entity.GapThreshold;
import io.skala.backend.domain.review.value.GapComparisonType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GapThresholdRepository extends JpaRepository<GapThreshold, Long> {

    /** 비교 종류의 현재 기준(가장 최근에 적용한 행) */
    Optional<GapThreshold> findFirstByComparisonOrderByIdDesc(GapComparisonType comparison);

    /** 기준 적용: 다른 적용·제출과 겹치지 않게 잠근다. */
    @Query(value = "SELECT id FROM gap_threshold_guard WHERE id = 1 FOR UPDATE", nativeQuery = true)
    Integer lockGuardForApply();

    /** 제출(Gap 계산): 기준 적용과만 겹치지 않게 공유 잠금. 제출끼리는 동시에 진행된다. */
    @Query(value = "SELECT id FROM gap_threshold_guard WHERE id = 1 FOR SHARE", nativeQuery = true)
    Integer lockGuardForSubmit();
}
