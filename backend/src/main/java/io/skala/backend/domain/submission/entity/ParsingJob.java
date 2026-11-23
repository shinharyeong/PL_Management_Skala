package io.skala.backend.domain.submission.entity;

import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.submission.value.ParsingJobStatus;
import io.skala.backend.global.entity.BaseCreatedTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "parsing_job")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParsingJob extends BaseCreatedTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ParsingJobStatus status;

    @Column(name = "engine", nullable = false, length = 80)
    private String engine;

    @Column(name = "parser_version", length = 80)
    private String parserVersion;

    @Column(name = "contract_version", length = 80)
    private String contractVersion;

    @Column(name = "mapping_version", length = 80)
    private String mappingVersion;

    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_object_key")
    private StoredObject resultObject;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    public static ParsingJob queue(Submission submission, String engine, int attemptNo) {
        ParsingJob job = new ParsingJob();
        job.submission = submission;
        job.status = ParsingJobStatus.QUEUED;
        job.engine = engine;
        job.attemptNo = attemptNo;
        return job;
    }

    public void start(OffsetDateTime now) {
        this.status = ParsingJobStatus.RUNNING;
        this.startedAt = now;
        this.finishedAt = null;
        this.errorCode = null;
        this.errorMessage = null;
    }

    /**
     * 파싱 성공. 파일은 읽었지만 검증 오류가 있으면 validationErrorCode에 VALIDATION_FAILED를 남긴다
     * (상태는 통과/수정 필요 두 가지뿐이라 SUCCEEDED + 오류 코드로 '수정 필요'를 표현).
     */
    public void succeed(String parserVersion, String contractVersion, String mappingVersion,
                        StoredObject resultObject, String validationErrorCode, OffsetDateTime now) {
        this.status = ParsingJobStatus.SUCCEEDED;
        this.parserVersion = parserVersion;
        this.contractVersion = contractVersion;
        this.mappingVersion = mappingVersion;
        this.resultObject = resultObject;
        this.errorCode = validationErrorCode;
        this.errorMessage = null;
        this.finishedAt = now;
    }

    public void fail(String errorCode, String errorMessage, OffsetDateTime now) {
        this.status = ParsingJobStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.finishedAt = now;
    }

    /** 파싱이 끝났고 검증 오류도 없는지 */
    public boolean passed() {
        return status == ParsingJobStatus.SUCCEEDED && errorCode == null;
    }
}
