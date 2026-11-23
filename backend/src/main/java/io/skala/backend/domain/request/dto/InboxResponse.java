package io.skala.backend.domain.request.dto;

import io.skala.backend.domain.user.dto.OrganizationResponse;
import java.time.OffsetDateTime;
import java.util.List;

/** INBOX-01 조직 담당자 요청함. kind: SUBMISSION(제출 요청)·EXPLANATION(소명 요청) */
public record InboxResponse(OrganizationResponse organization, int openCount, List<Item> items) {

    public record Item(String kind, String title, String requestId, String submissionId, OffsetDateTime dueAt,
                       String action, Link link) {
    }

    public record Link(String path) {
    }
}
