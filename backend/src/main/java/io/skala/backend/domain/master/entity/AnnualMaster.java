package io.skala.backend.domain.master.entity;

import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.global.entity.BaseCreatedTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "annual_master")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnnualMaster extends BaseCreatedTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private SubmissionRequest request;

    @Column(name = "seq", nullable = false)
    private Integer seq;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_master_id")
    private AnnualMaster previousMaster;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trigger_submission_id", nullable = false)
    private Submission triggerSubmission;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "calculation_version", nullable = false, length = 80)
    private String calculationVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private AppUser createdBy;
}
