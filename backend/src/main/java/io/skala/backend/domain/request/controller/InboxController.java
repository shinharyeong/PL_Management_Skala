package io.skala.backend.domain.request.controller;

import io.skala.backend.domain.request.dto.InboxResponse;
import io.skala.backend.domain.request.service.InboxService;
import io.skala.backend.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** INBOX-01 조직 담당자 요청함 */
@RestController
@RequiredArgsConstructor
public class InboxController {

    private final InboxService inboxService;
    private final CurrentUser currentUser;

    @GetMapping("/api/v1/inbox")
    public InboxResponse inbox() {
        return inboxService.inbox(currentUser.get());
    }
}
