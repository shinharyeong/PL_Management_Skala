package io.skala.backend.domain.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** RPT-07 기준 월 선택지. default는 가장 최근 확정 월(없으면 가장 최근 잠정 월, 둘 다 없으면 null) */
public record ReportPeriodResponse(@JsonProperty("default") YearMonth defaultMonth, int year, List<Month> months) {

    public record YearMonth(int year, int month) {
    }

    /** state: CONFIRMED(master 있음) · PROVISIONAL(master 없이 승인본만) · NONE */
    public record Month(int month, String state, String masterId) {
    }
}
