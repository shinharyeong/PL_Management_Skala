package io.skala.backend.domain.notification.service;

import io.skala.backend.domain.notification.dto.NotificationEvent;

/**
 * 알림 저장 포트. 제출·소명 회신을 처리하는 쪽이 같은 트랜잭션 안에서 호출한다.
 * 받는 사람은 사용 중인 경영기획팀 사용자 전원이다.
 */
public interface NotificationRecorder {

    void record(NotificationEvent event);
}
