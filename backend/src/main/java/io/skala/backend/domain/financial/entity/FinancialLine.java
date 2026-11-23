package io.skala.backend.domain.financial.entity;

import io.skala.backend.domain.submission.entity.Submission;
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

@Getter
@Entity
@Table(name = "financial_line")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_item_id")
    private AccountItem accountItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_item_id", nullable = false)
    private ReportItem reportItem;

    @Column(name = "detail_id", length = 500)
    private String detailId;

    @Column(name = "detail_revision", length = 80)
    private String detailRevision;

    @Column(name = "headquarters_code", length = 160)
    private String headquartersCode;

    @Column(name = "team_code", length = 160)
    private String teamCode;

    @Column(name = "task_code", length = 160)
    private String taskCode;

    @Column(name = "management_category", length = 500)
    private String managementCategory;

    @Column(name = "actual_basis", length = 500)
    private String actualBasis;

    @Column(name = "cost_type", length = 500)
    private String costType;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "source_sheet", nullable = false, length = 255)
    private String sourceSheet;

    @Column(name = "source_row", nullable = false)
    private Integer sourceRow;
}
