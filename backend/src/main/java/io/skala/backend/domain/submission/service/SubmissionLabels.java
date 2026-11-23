package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.domain.submission.entity.Submission;

/** 화면 표시용 버전 이름(api-spec 0절 "버전 표기"). */
public final class SubmissionLabels {

    private SubmissionLabels() {
    }

    /** 조직 제출본 YYYY-MM-NN(월간) · YYYY-PLAN-NN(연간). DRAFT는 회차가 없어 null. */
    public static String of(Submission submission, SubmissionRequest request) {
        if (submission.getSeq() == null) {
            return null;
        }
        int year = request.getAnnualPlan().getYear();
        if (request.getType() == RequestType.ANNUAL_PLAN) {
            return "%d-PLAN-%02d".formatted(year, submission.getSeq());
        }
        return "%d-%02d-%02d".formatted(year, request.getMonth(), submission.getSeq());
    }

    /** 승인 버전 ver{월}.{NN}. 승인 전이면 null. */
    public static String approved(Submission submission, SubmissionRequest request) {
        if (submission.getApprovedSeq() == null) {
            return null;
        }
        Integer month = request.getMonth();
        return "ver%d.%02d".formatted(month == null ? 0 : month, submission.getApprovedSeq());
    }
}
