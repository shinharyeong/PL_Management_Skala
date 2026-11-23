package io.skala.backend.domain.submission.entity;

import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.user.entity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "submission_validation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubmissionValidation {

    @Id
    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id")
    private Submission submission;

    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_sha256", nullable = false, length = 64)
    private String sourceSha256;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parser_result_object_key", nullable = false)
    private StoredObject parserResultObject;

    @Column(name = "policy_version", nullable = false, length = 80)
    private String policyVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "validation_result_object_key", nullable = false)
    private StoredObject validationResultObject;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "validated_by", nullable = false)
    private AppUser validatedBy;

    @Column(name = "validated_at", nullable = false)
    private OffsetDateTime validatedAt;
}
