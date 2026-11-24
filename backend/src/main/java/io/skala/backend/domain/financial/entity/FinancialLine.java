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

    @Column(name = "headquarters_name", length = 500)
    private String headquartersName;

    @Column(name = "team_name", length = 500)
    private String teamName;

    @Column(name = "task_name", length = 500)
    private String taskName;

    @Column(name = "person_in_charge", length = 500)
    private String personInCharge;

    @Column(name = "source_sheet", nullable = false, length = 255)
    private String sourceSheet;

    @Column(name = "source_row", nullable = false)
    private Integer sourceRow;

    @lombok.Builder(builderMethodName = "builder")
    private static FinancialLine of(Submission submission, AccountItem accountItem, ReportItem reportItem,
                                    String detailId, String detailRevision, String headquartersCode,
                                    String teamCode, String taskCode, String managementCategory,
                                    String actualBasis, String costType, String description,
                                    String headquartersName, String teamName, String taskName,
                                    String personInCharge, String sourceSheet, Integer sourceRow) {
        FinancialLine line = new FinancialLine();
        line.submission = submission;
        line.accountItem = accountItem;
        line.reportItem = reportItem;
        line.detailId = detailId;
        line.detailRevision = detailRevision;
        line.headquartersCode = headquartersCode;
        line.teamCode = teamCode;
        line.taskCode = taskCode;
        line.managementCategory = managementCategory;
        line.actualBasis = actualBasis;
        line.costType = costType;
        line.description = description;
        line.headquartersName = headquartersName;
        line.teamName = teamName;
        line.taskName = taskName;
        line.personInCharge = personInCharge;
        line.sourceSheet = sourceSheet;
        line.sourceRow = sourceRow;
        return line;
    }
}
