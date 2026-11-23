package io.skala.backend.domain.submission.dto;

/** PROOF-01 응답 */
public record ValidationResponse(BaselineResponse baseline, Progress progress, boolean canSubmit, String stateToken) {

    public record Progress(int answered, int required) {
    }
}
