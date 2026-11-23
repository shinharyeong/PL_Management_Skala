package io.skala.backend.domain.submission.entity;

import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.domain.user.entity.AppUser;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "submission")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    // FK: request_id, organization_id → request_target
    @Column(name = "request_id", nullable = false)
    private Long requestId;

    // FK: request_id, organization_id → request_target
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "seq")
    private Integer seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SubmissionStatus status;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_object_key", nullable = false)
    private StoredObject sourceObject;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_sha256", nullable = false, length = 64)
    private String sourceSha256;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private AppUser uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private OffsetDateTime uploadedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by")
    private AppUser submittedBy;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private AppUser reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "reject_reason", columnDefinition = "text")
    private String rejectReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_submission_id")
    private Submission previousSubmission;

    @Column(name = "reply_message", columnDefinition = "text")
    private String replyMessage;

    @Column(name = "approved_seq")
    private Integer approvedSeq;

    /** 업로드한 파일로 제출 초안(DRAFT)을 만든다. 회차(seq)는 제출할 때 정한다. */
    public static Submission draft(Long requestId, Long organizationId, String originalFilename,
                                   StoredObject sourceObject, AppUser uploadedBy, OffsetDateTime uploadedAt) {
        Submission submission = new Submission();
        submission.requestId = requestId;
        submission.organizationId = organizationId;
        submission.status = SubmissionStatus.DRAFT;
        submission.originalFilename = originalFilename;
        submission.sourceObject = sourceObject;
        submission.sourceSha256 = sourceObject.getSha256();
        submission.uploadedBy = uploadedBy;
        submission.uploadedAt = uploadedAt;
        return submission;
    }
}
