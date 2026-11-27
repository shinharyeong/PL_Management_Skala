package io.skala.backend.domain.notification.controller;

import io.skala.backend.domain.notification.dto.MarkNotificationsReadRequest;
import io.skala.backend.domain.notification.dto.NotificationListResponse;
import io.skala.backend.domain.notification.service.NotificationQueryService;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** NOTI-01~02 알림 모아보기(경영기획팀) */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationQueryService notificationService;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    @GetMapping
    public NotificationListResponse list(@RequestParam(required = false) String type,
                                         @RequestParam(required = false) Boolean unreadOnly,
                                         @RequestParam(required = false) Boolean readOnly,
                                         @RequestParam(required = false) String sort,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer limit) {
        return notificationService.list(currentUser.get(), type, unreadOnly, readOnly, sort, cursor, limit);
    }

    @PostMapping("/read")
    public ResponseEntity<String> markRead(@RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                           @Valid @RequestBody MarkNotificationsReadRequest request) {
        AuthenticatedUser actor = currentUser.get();
        return idempotency.execute(actor.id(), "POST /api/v1/notifications/read", idempotencyKey, request,
                () -> ResponseEntity.ok(notificationService.markRead(actor, request)));
    }
}
