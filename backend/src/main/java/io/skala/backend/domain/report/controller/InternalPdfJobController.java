package io.skala.backend.domain.report.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.skala.backend.domain.report.service.ReportPdfJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * report-ai → backend 내부 보고(명세 12절). 클러스터 내부 전용: Ingress는 /api만 열고, 여기서 X-Internal-Token을 확인한다.
 * 로그인 사용자가 없는 호출이라 SecurityConfig에서 이 경로만 permitAll이다.
 */
@RestController
@RequiredArgsConstructor
public class InternalPdfJobController {

    private final ReportPdfJobService pdfJobs;

    @PostMapping("/internal/pdf-jobs/{jobId}/result")
    public ResponseEntity<Void> result(@PathVariable long jobId,
                                       @RequestHeader(value = "X-Internal-Token", required = false) String token,
                                       @RequestBody JsonNode body) {
        pdfJobs.report(jobId, token, body);
        return ResponseEntity.noContent().build();
    }
}
