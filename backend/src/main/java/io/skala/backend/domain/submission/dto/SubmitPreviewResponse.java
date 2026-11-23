package io.skala.backend.domain.submission.dto;

import io.skala.backend.domain.financial.value.Scenario;
import java.util.List;

/** PROOF-07 제출 내용 확인 */
public record SubmitPreviewResponse(FileInfo file, String expectedLabel, BaselineResponse baseline, List<Item> items,
                                    boolean canSubmit, String stateToken) {

    /** savedAs: 제출하면 보관되는 이름(예: D100_2027-PLAN-01.xlsx) */
    public record FileInfo(String name, String savedAs) {
    }

    public record Item(String reviewItemId, String name, int month, Scenario scenario, String amount,
                       boolean answered) {
    }
}
