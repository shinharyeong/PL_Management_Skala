package io.skala.backend.domain.request.entity;

import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.user.entity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "request_template")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private SubmissionRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "object_key", nullable = false)
    private StoredObject storedObject;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_sha256", nullable = false, length = 64)
    private String sourceSha256;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registered_by", nullable = false)
    private AppUser registeredBy;

    @Column(name = "registered_at", nullable = false)
    private OffsetDateTime registeredAt;
}
