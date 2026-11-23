package io.skala.backend.domain.master.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** master_source 복합 기본키 */
@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class MasterSourceId implements Serializable {

    @Column(name = "master_id", nullable = false)
    private Long masterId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;
}
