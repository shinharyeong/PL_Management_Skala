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

    /** 상위 유형 코드. 상위 유형은 하위 유형 합으로 계산하고 카드를 직접 넣지 않는다 */
    @Column(name = "parent_code")
    private Integer parentCode;

    /** 분류하지 않은 소명이 자동으로 가는 '기타' */
    @Column(name = "catch_all", nullable = false)
    private Boolean catchAll = false;

    /** 규칙 제안 단어(쉼표 구분) */
    @Column(name = "suggest_keywords", length = 500)
    private String suggestKeywords;
}
