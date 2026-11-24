package io.skala.backend.domain.dashboard.controller;

import io.skala.backend.domain.dashboard.dto.ChangeHistoryResponse;
import io.skala.backend.domain.dashboard.dto.OrganizationVersionsResponse;
import io.skala.backend.domain.dashboard.service.ArchiveQueryService;
import io.skala.backend.global.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ARCH-02 조직별 월별 버전·이력, ARCH-03 변경 이력 */
@RestController
@RequiredArgsConstructor
public class ArchiveController {

    private final ArchiveQueryService archiveService;
    private final CurrentUser currentUser;

    @GetMapping("/api/v1/organizations/{organizationId}/versions")
    public OrganizationVersionsResponse versions(@PathVariable Long organizationId,
                                                 @RequestParam(required = false) Integer year,
                                                 @RequestParam(required = false) Integer month) {
        return archiveService.versions(currentUser.get(), organizationId, year, month);
    }

    @GetMapping("/api/v1/submissions/{submissionId}/change-history")
    public ChangeHistoryResponse changeHistory(@PathVariable Long submissionId) {
        return archiveService.changeHistory(currentUser.get(), submissionId);
    }
}
