package io.skala.backend.domain.master.controller;

import io.skala.backend.domain.master.dto.AnnualMasterResponse;
import io.skala.backend.domain.master.service.AnnualMasterQueryService;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 연간 계획 master 목록 및 버전별 엑셀 다운로드 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/annual-masters")
public class AnnualMasterController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final AnnualMasterQueryService masterService;
    private final CurrentUser currentUser;

    @GetMapping
    public AnnualMasterResponse.ListResponse list(@RequestParam(required = false) Integer year) {
        return masterService.list(currentUser.get(), year);
    }

    @GetMapping("/{masterId}/file")
    public ResponseEntity<byte[]> download(@PathVariable Long masterId) throws IOException {
        AuthenticatedUser actor = currentUser.get();
        String filename = masterService.filename(actor, masterId);
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        masterService.writeExcel(masterId, file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentType(XLSX)
                .body(file.toByteArray());
    }
}
