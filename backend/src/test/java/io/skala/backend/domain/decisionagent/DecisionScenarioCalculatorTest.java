package io.skala.backend.domain.decisionagent;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static org.assertj.core.api.Assertions.*;

import io.skala.backend.domain.decisionagent.service.DecisionScenarioCalculator;
import io.skala.backend.global.exception.BusinessException;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 실제 원 금액과 소수 연산으로 의사결정 계산의 경계 조건을 검증한다. */
class DecisionScenarioCalculatorTest {
    private final DecisionScenarioCalculator calculator = new DecisionScenarioCalculator();

    /** 목적: 1050억 목표 예시와 가정/검토 효과 분리를 검증한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void computesDemoInWonAndSeparatesReviewedFromAssumption() {
        Result result = calculator.calculate(snapshot(), "105000000000", List.of(Category.HEADCOUNT, Category.RND), List.of(
                candidate("a", "3500000000", EvidenceStatus.REVIEWED, Category.REVENUE, "", 12),
                candidate("b", "2500000000", EvidenceStatus.REVIEWED, Category.PROCUREMENT, "", 12),
                candidate("c", "1500000000", EvidenceStatus.ASSUMPTION, Category.OPERATIONS, "", 12)));
        assertThat(result.initialGapWon()).isEqualTo("13000000000");
        assertThat(result.reviewedEffectWon()).isEqualTo("6000000000");
        assertThat(result.conditionalEffectWon()).isEqualTo("7500000000");
        assertThat(result.reviewedOutlookWon()).isEqualTo("98000000000");
        assertThat(result.conditionalOutlookWon()).isEqualTo("99500000000");
        assertThat(result.conditionalGapWon()).isEqualTo("5500000000");
    }

    /** 목적: 매출 마진·실행비·기반영액을 부동소수 오차 없이 계산한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void preservesDecimalPrecisionAndDeductsOnlyEmbeddedPortion() {
        Candidate revenue = new Candidate("a", "1", "매출 인식", EffectType.REVENUE, "10.000001", "33.333333",
                "0.100001", "0.2", 12, Category.REVENUE, "", EvidenceStatus.REVIEWED, "일정 검토", "계약 1");
        Result result = calculator.calculate(snapshot(), "105000000000", List.of(), List.of(revenue));
        assertThat(result.reviewedEffectWon()).isEqualTo("3.03333263333333");
        assertThat(result.effects().getFirst().netEffectWon()).isEqualTo("3.03333263333333");
    }

    /** 목적: 중복 후보 모두와 제약·기간 위반을 제외한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void excludesEveryDuplicateAndProtectedUnknownOrPastEffect() {
        List<Candidate> candidates = List.of(
                candidate("a", "1", EvidenceStatus.REVIEWED, Category.REVENUE, "same", 12),
                candidate("b", "2", EvidenceStatus.REVIEWED, Category.REVENUE, " same ", 12),
                candidate("c", "3", EvidenceStatus.REVIEWED, Category.HEADCOUNT, "", 12),
                candidate("d", "4", EvidenceStatus.REVIEWED, Category.UNKNOWN, "", 12),
                candidate("e", "5", EvidenceStatus.REVIEWED, Category.OPERATIONS, "", 11),
                candidate("f", "6", EvidenceStatus.REVIEWED, Category.OPERATIONS, "", 13));
        Result result = calculator.calculate(snapshot(), "105000000000", List.of(Category.HEADCOUNT), candidates);
        assertThat(result.conditionalEffectWon()).isEqualTo("0");
        assertThat(result.effects()).allMatch(effect -> !effect.included());
        assertThat(result.effects().get(0).exclusionReasons()).containsExactly("DUPLICATE_EFFECT");
        assertThat(result.effects().get(1).exclusionReasons()).containsExactly("DUPLICATE_EFFECT");
        assertThat(result.effects().get(2).exclusionReasons()).contains("PROTECTED_CATEGORY");
        assertThat(result.effects().get(3).exclusionReasons()).contains("UNCLASSIFIED_PROTECTED_SCOPE");
        assertThat(result.effects().get(4).exclusionReasons()).contains("ALREADY_ACTUAL_PERIOD");
        assertThat(result.effects().get(5).exclusionReasons()).contains("OUTSIDE_TARGET_YEAR");
    }

    /** 목적: 비용보다 작은 증익과 초과 달성 부족액을 검증한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void excludesNonPositiveNetAndClampsGapToZero() {
        Candidate loss = new Candidate("a", "1", "비용 검토", EffectType.NET_PROFIT, "10", null, "5", "7", 12,
                Category.OPERATIONS, "", EvidenceStatus.ASSUMPTION, "", "");
        Result result = calculator.calculate(snapshot(), "1", List.of(), List.of(loss));
        assertThat(result.effects().getFirst().netEffectWon()).isEqualTo("-2");
        assertThat(result.effects().getFirst().exclusionReasons()).contains("NON_POSITIVE_NET_EFFECT");
        assertThat(result.initialGapWon()).isEqualTo("0");
        assertThat(result.conditionalGapWon()).isEqualTo("0");
    }

    /** 목적: 근거 누락·다른 조직·중복 ID·잘못된 금액을 거절한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void rejectsMissingEvidenceUnknownOrganizationDuplicateIdAndInvalidMoney() {
        Candidate missing = new Candidate("a", "1", "과제", EffectType.NET_PROFIT, "1", null, "0", "0", 12,
                Category.OPERATIONS, "", EvidenceStatus.REVIEWED, "", "");
        assertThatThrownBy(() -> calculator.validate(snapshot(), List.of(missing))).isInstanceOf(BusinessException.class);
        Candidate alien = new Candidate("a", "999", "과제", EffectType.NET_PROFIT, "1", null, "0", "0", 12,
                Category.OPERATIONS, "", EvidenceStatus.ASSUMPTION, "", "");
        assertThatThrownBy(() -> calculator.validate(snapshot(), List.of(alien))).isInstanceOf(BusinessException.class);
        Candidate repeated = candidate("a", "1", EvidenceStatus.ASSUMPTION, Category.REVENUE, "", 12);
        assertThatThrownBy(() -> calculator.validate(snapshot(), List.of(repeated, repeated))).isInstanceOf(BusinessException.class);
        for (String value : List.of("1e8", "-1", "1.0000001", "NaN", "01", "0")) {
            assertThatThrownBy(() -> calculator.amount(value, true)).isInstanceOf(BusinessException.class);
        }
    }

    /** 목적: 공통 고정 기준을 구성한다. Args: 없음. Returns: 920억 전망. Raises: 없음. Side Effects: 없음. */
    static Snapshot snapshot() {
        return new Snapshot("1", "master_ver11.01", 2026, 11, OffsetDateTime.parse("2026-11-25T00:00:00Z"), List.of("10"),
                List.of(new Organization("1", "A", "A사업부", "100000000000", "8000000000", "92000000000")), "92000000000");
    }

    /** 목적: 검증용 직접 증익 후보를 구성한다. Args: id 식별자, amount 금액, status 근거, category 분류, duplicate 중복 그룹, month 반영 월. Returns: 후보. Raises: 없음. Side Effects: 없음. */
    static Candidate candidate(String id, String amount, EvidenceStatus status, Category category, String duplicate, int month) {
        return new Candidate(id, "1", "개선 검토", EffectType.NET_PROFIT, amount, null, "0", "0", month,
                category, duplicate, status, "검토 근거", "문서 1");
    }
}
