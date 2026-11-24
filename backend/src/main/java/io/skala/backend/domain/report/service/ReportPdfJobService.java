package io.skala.backend.domain.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.file.repository.StoredObjectRepository;
import io.skala.backend.domain.file.service.FileStorage;
import io.skala.backend.domain.file.value.FilePurpose;
import io.skala.backend.domain.report.dto.PdfJobResponse;
import io.skala.backend.domain.report.exception.ReportErrorCode;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RPT-01·02 PDF 틀 추출 작업(FR-RPT-001). 백엔드는 PDF 보관·작업 상태·결과 보관만 하고, 추출은 report-ai가 한다(명세 12절).
 * <ol>
 *   <li>RPT-01: PDF 저장 → pdf_job(RUNNING) → report-ai에 전달(1차: HTTP). 전달이 실패하면 작업을 FAILED로 두고 503</li>
 *   <li>report-ai가 끝나면 POST /internal/pdf-jobs/{id}/result로 보고 → 결과 JSON 보관, SUCCEEDED·FAILED</li>
 *   <li>RPT-02: 상태·틀 초안·페이지 이미지 주소를 돌려준다(폴링)</li>
 * </ol>
 * 보고가 전달보다 먼저 와도 덮어쓰지 않도록, 전달 전에 RUNNING으로 두고 실패 처리는 RUNNING일 때만 한다.
 */
@Service
@RequiredArgsConstructor
public class ReportPdfJobService {

    private final JdbcTemplate jdbc;
    private final FileStorage fileStorage;
    private final StoredObjectRepository storedObjectRepository;
    private final ReportAiClient reportAi;
    private final TemplateDefinitionValidator validator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PdfJobResponse create(AuthenticatedUser user, String filename, byte[] pdf, boolean maskNumbers) {
        user.requireRole(UserRole.PLANNER);
        if (pdf.length < 5 || !new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "PDF 파일만 올릴 수 있습니다.");
        }
        Long id = tx.execute(status -> {
            StoredObject stored = fileStorage.save(FilePurpose.REPORT_PDF, filename, "application/pdf", new ByteArrayInputStream(pdf));
            storedObjectRepository.flush();                       // JDBC로 넣는 pdf_job의 외래키가 보이게
            return jdbc.queryForObject("""
                    INSERT INTO pdf_job(requested_by, source_object_key, mask_numbers, status, created_at, started_at)
                    VALUES (?, ?, ?, 'RUNNING', ?, ?) RETURNING id""", Long.class,
                    user.id(), stored.getObjectKey(), maskNumbers, OffsetDateTime.now(clock), OffsetDateTime.now(clock));
        });
        try {
            reportAi.submitPdf(id, pdf, filename, maskNumbers, user.id());
        } catch (BusinessException e) {
            jdbc.update("""
                    UPDATE pdf_job SET status = 'FAILED', error_code = ?, error_message = ?, finished_at = ?
                     WHERE id = ? AND status = 'RUNNING'""", e.getCode(), e.getMessage(), OffsetDateTime.now(clock), id);
            throw e;
        }
        return get(user, id);
    }

    @Transactional(readOnly = true)
    public PdfJobResponse get(AuthenticatedUser user, long id) {
        user.requireRole(UserRole.PLANNER);
        Map<String, Object> row = row(id);
        JsonNode result = result(row);
        List<PdfJobResponse.Page> pages = new ArrayList<>();
        if (result != null) {
            for (JsonNode page : result.path("pages")) {
                if (page.hasNonNull("imagePng")) {
                    int no = page.path("no").asInt();
                    pages.add(new PdfJobResponse.Page(no, "/api/v1/report/pdf-jobs/" + id + "/pages/" + no));
                }
            }
        }
        return new PdfJobResponse(String.valueOf(id), (String) row.get("status"), (Boolean) row.get("mask_numbers"),
                result == null ? null : result.get("definition"), pages, (String) row.get("error_code"),
                (String) row.get("error_message"), time(row.get("created_at")), time(row.get("finished_at")));
    }

    /** 숫자를 가린 페이지 이미지(PNG) */
    @Transactional(readOnly = true)
    public byte[] page(AuthenticatedUser user, long id, int no) {
        user.requireRole(UserRole.PLANNER);
        JsonNode result = result(row(id));
        if (result == null) {
            throw ReportErrorCode.PDF_JOB_NOT_READY.exception();
        }
        for (JsonNode page : result.path("pages")) {
            if (page.path("no").asInt() == no && page.hasNonNull("imagePng")) {
                try {
                    return Base64.getDecoder().decode(page.get("imagePng").asText());
                } catch (IllegalArgumentException e) {
                    break;
                }
            }
        }
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "페이지 이미지를 찾을 수 없습니다.");
    }

    /**
     * report-ai의 결과 보고. 이미 끝난 작업이면 아무것도 바꾸지 않는다(재전송 허용).
     * SUCCEEDED인데 틀 초안이 저장 봉투에 맞지 않으면 FAILED(INVALID_TEMPLATE)로 둔다.
     */
    @Transactional
    public void report(long id, String internalToken, JsonNode body) {
        if (!reportAi.acceptsToken(internalToken)) {
            throw ReportErrorCode.INVALID_INTERNAL_TOKEN.exception();
        }
        String status = jdbc.queryForList("SELECT status FROM pdf_job WHERE id = ? FOR UPDATE", String.class, id).stream()
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "PDF 분석 작업을 찾을 수 없습니다."));
        if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
            return;
        }
        String reported = body == null ? null : body.path("status").asText(null);
        if (!"SUCCEEDED".equals(reported) && !"FAILED".equals(reported)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "status는 SUCCEEDED 또는 FAILED입니다.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if ("FAILED".equals(reported)) {
            JsonNode error = body.path("error");
            jdbc.update("UPDATE pdf_job SET status = 'FAILED', error_code = ?, error_message = ?, finished_at = ? WHERE id = ?",
                    truncate(error.path("code").asText("PDF_ANALYSIS_FAILED"), 100), error.path("message").asText(null), now, id);
            return;
        }
        try {
            validator.validate(body.get("definition"));
        } catch (BusinessException e) {
            jdbc.update("UPDATE pdf_job SET status = 'FAILED', error_code = ?, error_message = ?, finished_at = ? WHERE id = ?",
                    e.getCode(), "추출한 틀 초안의 형식이 올바르지 않습니다.", now, id);
            return;
        }
        byte[] json = body.toString().getBytes(StandardCharsets.UTF_8);
        StoredObject stored = fileStorage.save(FilePurpose.REPORT_PDF_RESULT, "pdf-result.json", "application/json",
                new ByteArrayInputStream(json));
        storedObjectRepository.flush();
        jdbc.update("UPDATE pdf_job SET status = 'SUCCEEDED', result_object_key = ?, finished_at = ? WHERE id = ?",
                stored.getObjectKey(), now, id);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForList("""
                SELECT status, mask_numbers, result_object_key, error_code, error_message, created_at, finished_at
                  FROM pdf_job WHERE id = ?""", id).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "PDF 분석 작업을 찾을 수 없습니다."));
    }

    private JsonNode result(Map<String, Object> row) {
        String key = (String) row.get("result_object_key");
        if (key == null) {
            return null;
        }
        StoredObject object = storedObjectRepository.findById(key)
                .orElseThrow(() -> new IllegalStateException("PDF 분석 결과 파일이 없습니다: " + key));
        try (InputStream in = fileStorage.open(object)) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static OffsetDateTime time(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime t) {
            return t;
        }
        return ((java.sql.Timestamp) value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
