package io.skala.backend.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** AUTH-05 메뉴 배지. 역할에 해당하지 않는 항목은 응답에서 빠진다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BadgeResponse(Long unreadNotifications, Long monthlyReviewNeeded, Long annualReviewNeeded,
                            Long openRequests) {
}
