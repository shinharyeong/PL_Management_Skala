package io.skala.backend.domain.financial.entity;

import io.skala.backend.domain.financial.value.Scenario;
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
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "financial_value")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinancialValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "line_id", nullable = false)
    private FinancialLine line;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Enumerated(EnumType.STRING)
    @Column(name = "scenario", nullable = false, length = 20)
    private Scenario scenario;

    @Column(name = "amount", nullable = false, precision = 30, scale = 6)
    private BigDecimal amount;

    @Column(name = "source_record_id", nullable = false, length = 500)
    private String sourceRecordId;

    @Column(name = "source_cell", nullable = false, length = 30)
    private String sourceCell;

    public static FinancialValue create(FinancialLine line, int year, int month, Scenario scenario,
                                        BigDecimal amount, String sourceRecordId, String sourceCell) {
        FinancialValue value = new FinancialValue();
        value.line = line;
        value.year = year;
        value.month = month;
        value.scenario = scenario;
        value.amount = amount;
        value.sourceRecordId = sourceRecordId;
        value.sourceCell = sourceCell;
        return value;
    }
}
