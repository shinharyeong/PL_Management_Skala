package io.skala.backend.domain.report.controller;

import io.skala.backend.domain.report.dto.ReportPeriodResponse;
import io.skala.backend.domain.report.dto.TemplateCreateRequest;
import io.skala.backend.domain.report.dto.TemplateResponse;
import io.skala.backend.domain.report.dto.TemplateVersionRequest;
import io.skala.backend.domain.report.service.ReportTemplateService;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.response.PageResponse;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** RPT-03~07 자료 틀 저장·불러오기, 기준 월 선택지. 경영기획팀만 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/report")
public class ReportTemplateController {

    private final ReportTemplateService reportTemplateService;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    @GetMapping("/templates")
    public PageResponse<TemplateResponse> list(@RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit) {
        return reportTemplateService.list(currentUser.get(), cursor, limit);
    }

    @PostMapping("/templates")
    public ResponseEntity<String> create(@RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                         @Valid @RequestBody TemplateCreateRequest request) {
        AuthenticatedUser user = currentUser.get();
        return idempotency.execute(user.id(), "POST /api/v1/report/templates", idempotencyKey, request, () -> {
            TemplateResponse saved = reportTemplateService.create(user, request);
            return ResponseEntity.created(URI.create("/api/v1/report/templates/" + saved.id())).body(saved);
        });
    }

    @GetMapping("/templates/{templateId}")
    public TemplateResponse get(@PathVariable long templateId, @RequestParam(required = false) Integer version) {
        return reportTemplateService.get(currentUser.get(), templateId, version);
    }

    @PostMapping("/templates/{templateId}/versions")
    public ResponseEntity<String> addVersion(@PathVariable long templateId,
                                             @RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                             @Valid @RequestBody TemplateVersionRequest request) {
        AuthenticatedUser user = currentUser.get();
        return idempotency.execute(user.id(), "POST /api/v1/report/templates/" + templateId + "/versions",
                idempotencyKey, request, () -> ResponseEntity.status(201)
                        .body(reportTemplateService.addVersion(user, templateId, request)));
    }

    @GetMapping("/periods")
    public ReportPeriodResponse periods(@RequestParam(required = false) Integer year) {
        return reportTemplateService.periods(currentUser.get(), year);
    }
}
