package io.skala.backend.domain.request.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** request_target 복합 기본키 */
@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RequestTargetId implements Serializable {

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;
}
