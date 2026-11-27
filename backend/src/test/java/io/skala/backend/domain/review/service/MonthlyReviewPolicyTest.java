package io.skala.backend.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import io.skala.backend.domain.financial.value.Scenario;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.domain.master.entity.AnnualPlan;
import io.skala.backend.domain.review.service.ChangeDetector.Key;
import org.junit.jupiter.api.Test;

class MonthlyReviewPolicyTest {
    private SubmissionRequest request(int month) {
        SubmissionRequest request=mock(SubmissionRequest.class);
        AnnualPlan plan=mock(AnnualPlan.class);
        when(plan.getYear()).thenReturn(2026);
        when(request.getAnnualPlan()).thenReturn(plan);
        when(request.getType()).thenReturn(RequestType.MONTHLY_ACTUAL);
        when(request.getMonth()).thenReturn(month);
        return request;
    }
    @Test void 삼월은_삼월실적과_사월부터십이월추정만_검토한다() {
        var request=request(3);
        for(int month=1;month<=12;month++) {
            assertThat(MonthlyReviewPolicy.inScope(request,new Key(1L,2026,month,Scenario.ACTUAL))).isEqualTo(month==3);
            assertThat(MonthlyReviewPolicy.inScope(request,new Key(1L,2026,month,Scenario.ESTIMATE))).isEqualTo(month>3);
            assertThat(MonthlyReviewPolicy.inScope(request,new Key(1L,2026,month,Scenario.PLAN))).isFalse();
        }
        assertThat(MonthlyReviewPolicy.inScope(request,new Key(1L,2025,3,Scenario.ACTUAL))).isFalse();
    }
    @Test void 십이월은_실적만_일월은_이월부터_추정을_검토한다() {
        assertThat(MonthlyReviewPolicy.inScope(request(12),new Key(1L,2026,12,Scenario.ACTUAL))).isTrue();
        assertThat(MonthlyReviewPolicy.inScope(request(12),new Key(1L,2026,12,Scenario.ESTIMATE))).isFalse();
        assertThat(MonthlyReviewPolicy.inScope(request(1),new Key(1L,2026,2,Scenario.ESTIMATE))).isTrue();
    }
}
