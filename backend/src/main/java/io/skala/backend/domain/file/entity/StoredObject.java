package io.skala.backend.domain.file.entity;

import io.skala.backend.global.entity.BaseCreatedTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "stored_object")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoredObject extends BaseCreatedTimeEntity {

    @Id
    @Column(name = "object_key", nullable = false, length = 100)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    public static StoredObject create(String objectKey, String contentType, long sizeBytes, String sha256) {
        StoredObject object = new StoredObject();
        object.objectKey = objectKey;
        object.contentType = contentType;
        object.sizeBytes = sizeBytes;
        object.sha256 = sha256;
        return object;
    }
}
