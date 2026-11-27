package io.skala.backend.domain.decisionagent.controller;

import io.skala.backend.domain.decisionagent.service.DecisionReportArtifactStore;
import io.skala.backend.global.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 클릭으로만 호출하는 인증된 임시 XLSX 다운로드. */
@RestController
@RequiredArgsConstructor
public class DecisionReportArtifactController {
    private final DecisionReportArtifactStore store;
    private final CurrentUser currentUser;

    /** 목적: 현재 기획자 본인의 유효한 보고서만 내려준다. Args: artifactId 서버가 발급한 영수증 ID.
     * Returns: 캐시 금지 XLSX 응답. Raises: 인증/권한/만료 오류.
     * Side Effects: 메모리 사본 응답과 권한 DB 읽기. 서버 파일·업무 저장 없음. */
    @GetMapping("/api/v1/decision-agent/report-artifacts/{artifactId}")
    public ResponseEntity<byte[]> download(@PathVariable String artifactId) {
        var download = store.get(currentUser.get(),artifactId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(DecisionReportArtifactStore.CONTENT_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                        .filename(download.filename(),StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff")
                .body(download.bytes());
    }
}
