package io.skala.backend.domain.request.service;

import io.skala.backend.domain.request.exception.RequestErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * 제출 양식(UP-01). 연간 계획과 월간 시트가 같은 양식이라 파일 하나를 모든 요청에 내려준다.
 * 파일 위치는 app.submission-template.location(기본 file:./templates/submission-template.xlsx).
 * 엑셀 파일은 저장소에 커밋하지 않으므로 배포 환경에 따로 둔다.
 */
@Service
public class SubmissionTemplateService {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final Resource resource;
    private final String filename;

    public SubmissionTemplateService(ResourceLoader resourceLoader,
                                     @Value("${app.submission-template.location:file:./templates/submission-template.xlsx}") String location,
                                     @Value("${app.submission-template.filename:제출양식.xlsx}") String filename) {
        this.resource = resourceLoader.getResource(location);
        this.filename = filename;
    }

    /** 파일이 없으면 404 TEMPLATE_NOT_FOUND */
    public Download download() {
        if (!resource.exists() || !resource.isReadable()) {
            throw RequestErrorCode.TEMPLATE_NOT_FOUND.exception();
        }
        try {
            return new Download(filename, XLSX, resource.contentLength(), resource.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record Download(String filename, String contentType, long size, InputStream content) {
    }
}
