package io.skala.backend.domain.submission.repository;

import io.skala.backend.domain.submission.entity.ParsingJob;
import io.skala.backend.domain.submission.value.ParsingJobStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParsingJobRepository extends JpaRepository<ParsingJob, Long> {

    Optional<ParsingJob> findBySubmissionId(Long submissionId);

    /** 대기 중인 작업을 한 실행자만 가져가도록 조건부로 RUNNING으로 바꾼다. 바뀐 행 수가 1이면 가져간 것. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ParsingJob j set j.status = io.skala.backend.domain.submission.value.ParsingJobStatus.RUNNING,
              j.startedAt = :now, j.finishedAt = null
            where j.id = :id and j.attemptNo = :attemptNo
              and j.status = io.skala.backend.domain.submission.value.ParsingJobStatus.QUEUED
            """)
    int claim(@Param("id") Long id, @Param("attemptNo") int attemptNo, @Param("now") OffsetDateTime now);

    List<ParsingJob> findAllByStatusAndCreatedAtBefore(ParsingJobStatus status, OffsetDateTime before);

    List<ParsingJob> findAllByStatusAndStartedAtBefore(ParsingJobStatus status, OffsetDateTime before);
}
