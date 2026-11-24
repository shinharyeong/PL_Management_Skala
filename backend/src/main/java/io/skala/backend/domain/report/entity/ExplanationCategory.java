package io.skala.backend.domain.report.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "explanation_category")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExplanationCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "table_version", nullable = false)
    private Integer tableVersion;

    @Column(name = "code", nullable = false)
    private Integer code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;
}
