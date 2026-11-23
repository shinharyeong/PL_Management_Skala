package io.skala.backend.global.util;

import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.response.PageResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 목록 커서(위치 + 조회 조건 지문). 조건이 바뀐 커서를 쓰면 400 INVALID_CURSOR.
 * <pre>{@code
 * int size = OffsetCursor.limit(limit); int offset = OffsetCursor.decode(cursor, scope);
 * // size + 1개 조회 → OffsetCursor.next(rows.size() > size, offset, size, scope)
 * }</pre>
 */
public final class OffsetCursor {

    private OffsetCursor() {
    }

    public static int limit(Integer requested) {
        int value = requested == null ? PageResponse.DEFAULT_LIMIT : requested;
        if (value < 1 || value > PageResponse.MAX_LIMIT) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "limit은 1~100 사이여야 합니다.");
        }
        return value;
    }

    public static int decode(String cursor, String scope) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 2);
            int offset = Integer.parseInt(parts[0]);
            if (offset < 0 || parts.length != 2 || !parts[1].equals(Integer.toHexString(scope.hashCode()))) {
                throw new IllegalArgumentException();
            }
            return offset;
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.INVALID_CURSOR);
        }
    }

    public static String next(boolean hasMore, int offset, int limit, String scope) {
        if (!hasMore) {
            return null;
        }
        String raw = (offset + limit) + ":" + Integer.toHexString(scope.hashCode());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
