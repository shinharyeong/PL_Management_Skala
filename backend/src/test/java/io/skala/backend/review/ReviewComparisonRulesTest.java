package io.skala.backend.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** DB와 무관한 Gap 경계·정규화 규칙을 정확한 십진수로 검사한다. */
class ReviewComparisonRulesTest {
    private final ReviewComparisonService service=new ReviewComparisonService(null,new ObjectMapper());
    /** 비율과 금액을 모두 엄격 초과해야 하며 기준 0은 비율 계산과 구분한다.
     * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 계산만 수행한다.
     */
    @Test
    void strictAndThresholdsAndZeroBaselinePreserveExactDecimals(){
        assertThat(exceeds("100","105","5","4")).isFalse();
        assertThat(exceeds("100","106","5","6")).isFalse();
        assertThat(exceeds("100","105.000001","5","4")).isTrue();
        assertThat(exceeds("-100","-106","5","4")).isTrue();
        assertThat(exceeds("0","0","5","10000000")).isFalse();
        assertThat(exceeds("0","0.000001","99","999999999999999999999999")).isTrue();
        assertThat(exceeds("99999999999999999999999","100000000000000000000000","0","0.999999")).isTrue();
    }
    /** 새 정책의 단독 토글·AND·최소금액 경계와 기준 0에서도 금액 조건을 지키는지 확인한다.
     * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 계산만 수행한다.
     */
    @Test
    void prdThresholdTogglesApplyOnlyEnabledConditionsAndRequireOne() {
        var baseline=new BigDecimal("100");var current=new BigDecimal("106");var percent=new BigDecimal("5");var amount=new BigDecimal("10");
        assertThat(service.exceeds(baseline,current,percent,amount,true,true)).isFalse();
        assertThat(service.exceeds(baseline,current,percent,amount,true,false)).isTrue();
        assertThat(service.exceeds(baseline,current,percent,amount,false,true)).isFalse();
        assertThat(service.exceeds(baseline,new BigDecimal("111"),percent,amount,false,true)).isTrue();
        assertThat(service.exceeds(BigDecimal.ZERO,BigDecimal.ONE,percent,amount,true,true)).isFalse();
        assertThat(service.exceeds(BigDecimal.ZERO,new BigDecimal("11"),percent,amount,true,true)).isTrue();
        assertThat(service.exceeds(baseline,baseline,percent,amount,true,false)).isFalse();
        assertThatThrownBy(()->service.exceeds(baseline,current,percent,amount,false,false)).isInstanceOf(IllegalArgumentException.class);
    }
    /** 생성형 마감 안내·ID 메타만 제외하고 거래 설명 속 마감 문구는 보존한다.
     * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 문자열만 처리한다.
     */
    @Test
    void onlyExactGeneratedMetadataIsRemoved(){
        assertThat(ReviewComparisonService.normalizedDescription("[2026년 1월 마감: 1월 실적 / 2~12월 추정] 월 사용료 [ID:S01-F10-REV-001;수정:2]")).isEqualTo("월 사용료");
        assertThat(ReviewComparisonService.normalizedDescription("월 사용료 [계약 마감 정산 추가금]")).isEqualTo("월 사용료 [계약 마감 정산 추가금]");
        assertThat(ReviewComparisonService.normalizedDescription("월 사용료 [ID:계약참조]")).isEqualTo("월 사용료 [ID:계약참조]");
    }
    /** 문자열을 정확한 십진값으로 변환해 판정한다. Args: baseline/current/percent/amount 값. Returns: 초과. Raises: 형식 오류. Side Effects: 없음. */
    private boolean exceeds(String baseline,String current,String percent,String amount){return service.exceeds(new BigDecimal(baseline),new BigDecimal(current),new BigDecimal(percent),new BigDecimal(amount));}
}
