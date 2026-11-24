package io.skala.backend.domain.request.controller;

import io.skala.backend.domain.request.service.SubmissionTemplateService;
import io.skala.backend.global.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** UP-01 제출 양식 다운로드. 연간·월간 공통 파일 하나. 로그인한 사용자 누구나 받을 수 있다. */
@RestController
@RequiredArgsConstructor
public class SubmissionTemplateController {

    private final SubmissionTemplateService templateService;
    private final CurrentUser currentUser;

    @GetMapping("/api/v1/submission-template/file")
    public ResponseEntity<InputStreamResource> download() {
        currentUser.get();
        SubmissionTemplateService.Download file = templateService.download();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }
}
