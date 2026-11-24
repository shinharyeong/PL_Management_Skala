package io.skala.backend.domain.notification.value;

/** 경영기획팀 알림 종류 (notification.type) */
public enum NotificationType {

    /** 조직 제출 */
    SUBMISSION,
    /** 소명 회신 */
    REPLY,
    /** 기한 경과 */
    OVERDUE
}
