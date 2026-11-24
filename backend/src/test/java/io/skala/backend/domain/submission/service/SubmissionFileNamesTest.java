package io.skala.backend.domain.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.skala.backend.domain.master.entity.AnnualPlan;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.global.exception.BusinessException;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SubmissionFileNamesTest {

    private final SubmissionRequest monthly = request(RequestType.MONTHLY_ACTUAL, 11);
    private final SubmissionRequest annual = request(RequestType.ANNUAL_PLAN, null);

    @Test
    void 월간은_조직코드_연월_xlsx() {
        assertThatCode(() -> SubmissionFileNames.check("D100_2026-11.xlsx", monthly, "D100")).doesNotThrowAnyException();
        assertThatCode(() -> SubmissionFileNames.check("D100_2026-11.xlsb", monthly, "D100")).doesNotThrowAnyException();
        assertCode(() -> SubmissionFileNames.check("D100_2026-11.xls", monthly, "D100"), "INVALID_FILENAME");
        assertCode(() -> SubmissionFileNames.check("D100_2026-11.xlsm", monthly, "D100"), "INVALID_FILENAME");
        assertCode(() -> SubmissionFileNames.check("D100_2026-11.XLSB", monthly, "D100"), "INVALID_FILENAME");
        assertCode(() -> SubmissionFileNames.check("D100_2026-10.xlsx", monthly, "D100"), "INVALID_FILENAME");
        assertCode(() -> SubmissionFileNames.check("D100-2026-11.xlsx", monthly, "D100"), "INVALID_FILENAME");
        assertCode(() -> SubmissionFileNames.check("../D100_2026-11.xlsx", monthly, "D100"), "INVALID_FILENAME");
        assertCode(() -> SubmissionFileNames.check("D200_2026-11.xlsx", monthly, "D100"), "ORGANIZATION_MISMATCH");
    }

    @Test
    void 연간은_조직코드_연도_PLAN_xlsx() {
        assertThatCode(() -> SubmissionFileNames.check("D100_2026-PLAN.xlsx", annual, "D100")).doesNotThrowAnyException();
        assertThatCode(() -> SubmissionFileNames.check("D100_2026-PLAN.xlsb", annual, "D100")).doesNotThrowAnyException();
        assertCode(() -> SubmissionFileNames.check("D100_2026-11.xlsx", annual, "D100"), "INVALID_FILENAME");
        assertCode(() -> SubmissionFileNames.check("D100_2027-PLAN.xlsx", annual, "D100"), "INVALID_FILENAME");
    }

    @Test
    void 엑셀은_ZIP_서명으로_확인() {
        assertThat(SubmissionFileNames.looksLikeWorkbook(new byte[] {'P', 'K', 3, 4, 0})).isTrue();
        assertThat(SubmissionFileNames.looksLikeWorkbook("hello".getBytes())).isFalse();
        assertThat(SubmissionFileNames.looksLikeWorkbook(new byte[0])).isFalse();
    }

    @Test
    void 확장자와_기본_저장_형식() {
        assertThat(SubmissionFileNames.extension("D100_2026-11.xlsb")).isEqualTo("xlsb");
        assertThat(SubmissionFileNames.defaultContentType("D100_2026-11.xlsb"))
                .isEqualTo("application/vnd.ms-excel.sheet.binary.macroEnabled.12");
        assertThat(SubmissionFileNames.defaultContentType("D100_2026-11.xlsx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(code);
    }

    private static SubmissionRequest request(RequestType type, Integer month) {
        AnnualPlan plan = instance(AnnualPlan.class);
        ReflectionTestUtils.setField(plan, "year", 2026);
        SubmissionRequest request = instance(SubmissionRequest.class);
        ReflectionTestUtils.setField(request, "annualPlan", plan);
        ReflectionTestUtils.setField(request, "type", type);
        ReflectionTestUtils.setField(request, "month", month);
        return request;
    }

    private static <T> T instance(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
