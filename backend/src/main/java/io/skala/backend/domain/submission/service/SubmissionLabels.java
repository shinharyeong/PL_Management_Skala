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
        return submission.getSeq() == null ? null : of(submission.getSeq(), request);
    }

    /** 회차로 만든 버전 이름(제출 전 '예상 제출 버전'에도 쓴다) */
    public static String of(int seq, SubmissionRequest request) {
        int year = request.getAnnualPlan().getYear();
        if (request.getType() == RequestType.ANNUAL_PLAN) {
            return "%d-PLAN-%02d".formatted(year, seq);
        }
        return "%d-%02d-%02d".formatted(year, request.getMonth(), seq);
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
