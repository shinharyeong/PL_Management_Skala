package io.skala.backend.domain.user.controller;

import io.skala.backend.domain.user.dto.OrganizationResponse;
import io.skala.backend.domain.user.service.OrganizationQueryService;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.response.PageResponse;
import io.skala.backend.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REQ-01·ARCH-01 조직 목록·검색(수신 담당자 포함). 경영기획팀 전용. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationQueryService organizationQueryService;
    private final CurrentUser currentUser;

    @GetMapping
    public PageResponse<OrganizationResponse> list(@RequestParam(required = false) String q,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(required = false) Integer limit) {
        currentUser.get().requireRole(UserRole.PLANNER);
        return organizationQueryService.list(q, cursor, limit);
    }
}
