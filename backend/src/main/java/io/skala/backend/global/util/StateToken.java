package io.skala.backend.global.util;

import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 동시 수정 검사용 상태 토큰. 리소스의 "바뀌면 안 되는 값"들로 만들어 응답의 stateToken으로 내려주고,
 * 수정 명령의 expectedStateToken과 비교한다(docs/api/api-spec.md 0-2절).
 */
public final class StateToken {

    private StateToken() {
    }

    /** 예: StateToken.of(submission.getId(), submission.getStatus(), submission.getSeq()) */
    public static String of(Object... parts) {
        StringBuilder joined = new StringBuilder();
        for (Object part : parts) {
            joined.append(Objects.toString(part, "∅")).append('|');
        }
        return "st_" + HexFormat.of().formatHex(sha256(joined.toString())).substring(0, 16);
    }

    /** 다르면 409 RESOURCE_CHANGED */
    public static void verify(String expected, String current) {
        if (expected == null || !expected.equals(current)) {
            throw new BusinessException(ErrorCode.RESOURCE_CHANGED);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
