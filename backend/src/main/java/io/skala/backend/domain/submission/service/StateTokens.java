package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.submission.entity.ParsingJob;
import io.skala.backend.domain.submission.entity.Submission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 리소스 상태 지문(stateToken). 상태를 이루는 값을 이어 해시한 "st_" + 16자리 16진수.
 * 명령 API는 expectedStateToken이 지금 계산한 값과 다르면 RESOURCE_CHANGED로 막는다.
 */
public final class StateTokens {

    private StateTokens() {
    }

    /** 제출본 지문: 상태·회차·승인 회차·파싱 작업(상태·시도·검증 오류)이 바뀌면 달라진다. */
    public static String forSubmission(Submission submission, ParsingJob job) {
        return of("submission", submission.getId(), submission.getStatus(), submission.getSeq(),
                submission.getApprovedSeq(), job == null ? null : job.getStatus(),
                job == null ? null : job.getAttemptNo(), job == null ? null : job.getErrorCode());
    }

    public static String of(Object... parts) {
        String joined = Stream.of(parts).map(p -> Objects.toString(p, "-")).collect(Collectors.joining("|"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return "st_" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
