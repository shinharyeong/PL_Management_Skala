package io.skala.backend.domain.review.controller;

import io.skala.backend.domain.review.dto.VersionComparisonResponse;
import io.skala.backend.domain.review.service.VersionComparisonService;
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

/** GAP-01 버전 비교, GAP-02 비교 결과 엑셀 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/submissions/{submissionId}/comparison")
public class VersionComparisonController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final VersionComparisonService versionComparisonService;
    private final CurrentUser currentUser;

    @GetMapping
    public VersionComparisonResponse compare(@PathVariable Long submissionId,
                                             @RequestParam(required = false) Long baseSubmissionId) {
        return versionComparisonService.compare(submissionId, baseSubmissionId, currentUser.get());
    }

    @GetMapping("/file")
    public ResponseEntity<byte[]> file(@PathVariable Long submissionId,
                                       @RequestParam(required = false) Long baseSubmissionId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String filename = versionComparisonService.writeExcel(submissionId, baseSubmissionId, currentUser.get(), out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentType(XLSX)
                .body(out.toByteArray());
    }
}
