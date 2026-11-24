package io.skala.backend.domain.notification.dto;

import io.skala.backend.domain.notification.value.NotificationType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** NOTI-01 알림 목록. groups는 한국 시간 날짜별 묶음(최신순), typeCounts의 키는 ALL·SUBMISSION·REPLY·OVERDUE */
public record NotificationListResponse(String nextCursor, Summary summary, Map<String, Long> typeCounts, List<Group> groups) {

    /** 상단 요약 카드: 안 읽은 알림, 회신 검토 대기, 기한 경과 조직 */
    public record Summary(long unreadCount, long replyPendingCount, long overdueOrganizationCount) {
    }

    /** unreadCount는 이 페이지가 아니라 그 날짜 전체(같은 필터) 기준 */
    public record Group(LocalDate date, long unreadCount, List<Item> items) {
    }

    /** 기한 경과(OVERDUE)는 actor가 null이고 occurredAt이 회신 기한이다. */
    public record Item(String id, NotificationType type, String title, OrganizationRef organization, UserRef actor,
                       OffsetDateTime occurredAt, boolean read, Link link) {
    }

    public record OrganizationRef(String id, String code, String name) {
    }

    public record UserRef(String id, String name) {
    }

    /** [검토하기]·[현황 보기]로 이동할 화면과 미리 선택할 요청·조직·제출본·항목 */
    public record Link(String path, Map<String, String> query) {
    }
}
