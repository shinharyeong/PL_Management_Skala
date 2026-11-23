package io.skala.backend.global.response;

import java.util.List;

/** 목록 응답 공통 형식. 마지막 페이지면 nextCursor는 null(docs/api/api-spec.md 0-2절). */
public record PageResponse<T>(List<T> items, String nextCursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    public static <T> PageResponse<T> of(List<T> items, String nextCursor) {
        return new PageResponse<>(List.copyOf(items), nextCursor);
    }
}
