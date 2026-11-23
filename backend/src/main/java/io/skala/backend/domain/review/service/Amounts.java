package io.skala.backend.domain.review.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 응답용 금액·비율 표기. 금액은 원 단위 십진 문자열, 값이 없으면 null(0으로 바꾸지 않음). */
public final class Amounts {

    private Amounts() {
    }

    public static String money(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.signum() == 0 ? "0" : amount.stripTrailingZeros().toPlainString();
    }

    public static BigDecimal diff(BigDecimal current, BigDecimal base) {
        return current == null || base == null ? null : current.subtract(base);
    }

    /** (현재 − 기준) / |기준| × 100, 소수 첫째 자리. 기준이 없거나 0이면 null('산정 불가') */
    public static String rate(BigDecimal current, BigDecimal base) {
        if (current == null || base == null || base.signum() == 0) {
            return null;
        }
        return current.subtract(base).multiply(BigDecimal.valueOf(100))
                .divide(base.abs(), 1, RoundingMode.HALF_UP).toPlainString();
    }
}
