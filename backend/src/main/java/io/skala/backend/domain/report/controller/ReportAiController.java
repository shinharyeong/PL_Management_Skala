package io.skala.backend.domain.report.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.skala.backend.domain.report.dto.MetricDtos;
import io.skala.backend.domain.report.dto.PdfJobResponse;
import io.skala.backend.domain.report.dto.ReportAiRequests;
import io.skala.backend.domain.report.service.ReportAiRelayService;
import io.skala.backend.domain.report.service.ReportMetricService;
import io.skala.backend.domain.report.service.ReportPdfJobService;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.idempotency.IdempotentCommandExecutor;
import io.skala.backend.global.response.PageResponse;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import jakarta.validation.Valid;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * report-ai를 거치는 AI 보고서 API(RPT-01·02·10·11·15). 프론트는 report-ai를 직접 부르지 않는다. 경영기획팀만.
 * 조회성 POST(데이터·질의·편집 변경안)는 상태를 바꾸지 않아 Idempotency-Key를 받지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/report")
public class ReportAiController {

    private final ReportPdfJobService pdfJobs;
    private final ReportAiRelayService relay;
    private final ReportMetricService metricService;
    private final CurrentUser currentUser;
    private final IdempotentCommandExecutor idempotency;

    /** RPT-01 PDF 업로드 → 분석 요청. 202와 작업(RUNNING) */
    @PostMapping(path = "/pdf-jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PdfJobResponse> createPdfJob(@RequestParam("file") MultipartFile file,
                                                       @RequestParam(defaultValue = "true") boolean maskNumbers) {
        byte[] pdf;
        try {
            pdf = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "파일을 읽을 수 없습니다.");
        }
        PdfJobResponse job = pdfJobs.create(currentUser.get(), file.getOriginalFilename(), pdf, maskNumbers);
        return ResponseEntity.status(HttpStatus.ACCEPTED).header("Location", "/api/v1/report/pdf-jobs/" + job.id()).body(job);
    }

    /** RPT-02 분석 상태·틀 초안(폴링) */
    @GetMapping("/pdf-jobs/{jobId}")
    public PdfJobResponse pdfJob(@PathVariable long jobId) {
        return pdfJobs.get(currentUser.get(), jobId);
    }

    /** 숫자를 가린 페이지 이미지 */
    @GetMapping(path = "/pdf-jobs/{jobId}/pages/{no}", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] pdfJobPage(@PathVariable long jobId, @PathVariable int no) {
        return pdfJobs.page(currentUser.get(), jobId, no);
    }

    /** RPT-08 지표 사전 목록 */
    @GetMapping("/metrics")
    public PageResponse<MetricDtos.Metric> metrics(@RequestParam(required = false) String q,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(required = false) Integer limit) {
        return metricService.list(currentUser.get(), q, cursor, limit);
    }

    /** RPT-09 지표 등록(SQL은 report-ai sqlguard로 검사) */
    @PostMapping("/metrics")
    public ResponseEntity<String> createMetric(@RequestHeader(IdempotentCommandExecutor.HEADER) String idempotencyKey,
                                               @Valid @RequestBody MetricDtos.CreateRequest request) {
        AuthenticatedUser user = currentUser.get();
        return idempotency.execute(user.id(), "POST /api/v1/report/metrics", idempotencyKey, request,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(metricService.create(user, request)));
    }

    /** RPT-10 블록 데이터 채우기 */
    @PostMapping("/data")
    public JsonNode data(@Valid @RequestBody ReportAiRequests.Data request) {
        return relay.data(currentUser.get(), request);
    }

    /** RPT-11 자연어 질의(text2SQL) */
    @PostMapping("/nl-query")
    public JsonNode nlQuery(@Valid @RequestBody ReportAiRequests.NlQuery request) {
        return relay.nlQuery(currentUser.get(), request);
    }

    /** RPT-15 대화 편집 변경안(저장하지 않음, 적용은 화면·저장은 RPT-05) */
    @PostMapping("/ai/edit")
    public JsonNode edit(@Valid @RequestBody ReportAiRequests.Edit request) {
        return relay.edit(currentUser.get(), request);
    }
}
