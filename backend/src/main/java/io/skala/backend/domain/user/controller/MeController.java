package io.skala.backend.domain.user.controller;

import io.skala.backend.domain.user.dto.BadgeResponse;
import io.skala.backend.domain.user.dto.MeResponse;
import io.skala.backend.domain.user.service.MeService;
import io.skala.backend.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AUTH-04·05 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/me")
public class MeController {

    private final MeService meService;
    private final CurrentUser currentUser;

    @GetMapping
    public MeResponse me() {
        return meService.me(currentUser.get().id());
    }

    @GetMapping("/badges")
    public BadgeResponse badges() {
        return meService.badges(currentUser.get());
    }
}
