package io.skala.backend.domain.dashboard.dto;

import java.util.List;
import java.util.Map;

/** HOME-02 오늘 할 일. key: REQUEST_NEEDED·NOT_SUBMITTED·REVIEW_NEEDED·EXPLANATION_REVIEW_NEEDED */
public record HomeTodoResponse(List<Item> items) {

    public record Item(String key, String label, long count, Link link) {
    }

    public record Link(String path, Map<String, String> query) {
    }
}
