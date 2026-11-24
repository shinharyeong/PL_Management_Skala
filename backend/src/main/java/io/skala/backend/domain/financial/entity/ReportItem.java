package io.skala.backend.domain.financial.entity;

import io.skala.backend.domain.financial.value.ReportItemKind;
import io.skala.backend.global.entity.BaseTimeEntity;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "report_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "code", nullable = false, length = 160)
    private String code;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private ReportItem parent;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private ReportItemKind kind;

    @Column(name = "is_leaf", nullable = false)
    private Boolean isLeaf;

    @Column(name = "calculation_code", length = 80)
    private String calculationCode;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    public static ReportItem create(String code, String name, ReportItem parent, ReportItemKind kind,
                                    boolean leaf, int displayOrder) {
        ReportItem item = new ReportItem();
        item.code = code;
        item.name = name;
        item.parent = parent;
        item.kind = kind;
        item.isLeaf = leaf;
        item.displayOrder = displayOrder;
        item.active = true;
        return item;
    }

    /** 처음에 상위 항목으로만 만들어진 행이 나중에 말단으로도 쓰이면 말단 표시를 켠다. */
    public void markLeaf() {
        this.isLeaf = true;
    }
}
