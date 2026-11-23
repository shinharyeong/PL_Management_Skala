package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.submission.entity.ParsingJob;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.global.util.StateToken;

/** 제출본 stateToken. 상태·회차·승인 회차·파싱 작업(상태·시도·검증 오류)이 바뀌면 달라진다. */
public final class SubmissionStateToken {

    private SubmissionStateToken() {
    }

    public static String of(Submission submission, ParsingJob job) {
        return StateToken.of("submission", submission.getId(), submission.getStatus(), submission.getSeq(),
                submission.getApprovedSeq(), job == null ? null : job.getStatus(),
                job == null ? null : job.getAttemptNo(), job == null ? null : job.getErrorCode());
    }
}
