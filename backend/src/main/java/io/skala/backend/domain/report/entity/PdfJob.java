package io.skala.backend.domain.report.entity;

import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.report.value.PdfJobStatus;
import io.skala.backend.domain.user.entity.AppUser;
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
@Table(name = "pdf_job")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PdfJob extends BaseCreatedTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private AppUser requestedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_object_key", nullable = false)
    private StoredObject sourceObject;

    @Column(name = "mask_numbers", nullable = false)
    private Boolean maskNumbers = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PdfJobStatus status;

    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo = 1;

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
}
