package io.skala.backend.workspace;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.review.ReviewComparisonService;
import io.skala.backend.review.ReviewService;
import io.skala.backend.security.Actor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 소명 회차별 첨부 원문을 권한·현재 상태와 함께 보존한다. */
@Service
public class ReviewAttachmentService {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final JdbcClient jdbc;
    private final ReviewService reviews;
    private final ReviewComparisonService comparisons;
    private final CommandExecutor commands;
    private final ObjectMapper mapper;

    /** 첨부 저장에 필요한 기존 업무 경계를 연결한다.
     * Args: jdbc DB, reviews 검토 권한·토큰, comparisons 초안 비교 문맥, commands 멱등 실행, mapper JSON.
     * Returns: 없음. Raises: 없음. Side Effects: 참조만 보관한다.
     */
    public ReviewAttachmentService(JdbcClient jdbc, ReviewService reviews, ReviewComparisonService comparisons,
            CommandExecutor commands, ObjectMapper mapper) {
        this.jdbc = jdbc; this.reviews = reviews; this.comparisons = comparisons; this.commands = commands; this.mapper = mapper;
    }

    /** 접근 가능한 검토 항목의 회차별 첨부 목록을 읽는다.
     * Args: actor 인증 사용자, reviewId 검토 ID. Returns: 첨부 메타데이터 목록.
     * Raises: ApiException 범위 밖 검토 404. Side Effects: DB 읽기만 수행한다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(Actor actor, String reviewId) {
        reviews.get(actor, reviewId);
        var items = rows("""
                SELECT a.*,e.round,e.review_item_id,u.name AS uploader_name
                FROM review_attachment a JOIN review_explanation e ON e.id=a.explanation_id
                JOIN app_user u ON u.id=a.uploaded_by WHERE e.review_item_id=? ORDER BY e.round,a.id
                """, id(reviewId)).stream().map(this::metadata).toList();
        return object("reviewItemId", reviewId, "items", items);
    }

    /** 현재 답변 가능한 회차에만 원문을 추가하고 같은 요청은 한 번만 저장한다.
     * Args: actor 조직 담당자, reviewId 항목, key 멱등키, round 회차, expectedToken 상태 토큰,
     * filename 원래 파일명, declaredType multipart MIME, bytes 원문 바이트.
     * Returns: 201 첨부 메타데이터와 갱신 검토 DTO. Raises: 권한·파일·회차·문맥·상태 충돌.
     * Side Effects: 조직→제출→검토 잠금, 원문·첨부·소명 revision·성공 응답을 같은 DB 트랜잭션에 추가한다.
     */
    public CommandExecutor.Result add(Actor actor, String reviewId, String key, int round, String expectedToken,
            String filename, String declaredType, byte[] bytes) {
        actor.requireRole("ORG_MANAGER");
        reviews.get(actor, reviewId);
        if (round < 1 || expectedToken == null || expectedToken.isBlank() || expectedToken.length() > 200) throw invalid("소명 회차와 현재 상태 토큰을 확인해 주세요.");
        String contentType = validateFile(filename, declaredType, bytes);
        String digest = hash(bytes);
        String fingerprint = hash(json(object("round", round, "expectedStateToken", expectedToken, "filename", filename,
                "contentType", contentType, "sha256", digest)).getBytes(StandardCharsets.UTF_8));
        return commands.execute(actor, "POST /api/v1/review-items/" + id(reviewId) + "/attachments", key, fingerprint, () -> {
            var review = reviews.get(actor, reviewId);
            jdbc.sql("SELECT id FROM organization WHERE id=? FOR UPDATE").param(id(review.get("organizationId").toString())).query(Long.class).single();
            long submissionId = id(review.get("submissionId").toString());
            String status = jdbc.sql("SELECT status FROM submission WHERE id=? FOR UPDATE").param(submissionId).query(String.class).single();
            jdbc.sql("SELECT id FROM review_item WHERE id=? FOR UPDATE").param(id(reviewId)).query(Long.class).single();
            review = reviews.get(actor, reviewId);
            if (!Objects.equals(review.get("stateToken"), expectedToken)) throw conflict("RESOURCE_CHANGED", "소명이 변경되었습니다. 최신 내용을 확인한 뒤 첨부해 주세요.");
            if (((Number) review.get("currentRound")).intValue() != round) throw conflict("EXPLANATION_ROUND_CHANGED", "현재 소명 회차가 변경되었습니다.");
            var explanation = rows("SELECT * FROM review_explanation WHERE review_item_id=? AND round=?", id(reviewId), round).getFirst();
            if (!List.of("DRAFT", "PENDING").contains(status) || "EXCLUDED".equals(review.get("workflowState"))
                    || !"PENDING".equals(explanation.get("verdict")) || explanation.get("inherited_from_id") != null
                    || ("PENDING".equals(status) && (explanation.get("answer") != null || explanation.get("requested_by") == null))) {
                throw conflict("EXPLANATION_LOCKED", "현재 회차에는 첨부할 수 없습니다. 답변 가능한 소명 회차를 확인해 주세요.");
            }
            if ("DRAFT".equals(status) && !comparisons.currentContext(submissionId)) throw conflict("REVIEW_CONTEXT_CHANGED", "비교 기준이 바뀌어 파일 검증을 다시 진행해야 합니다.");
            if (rows("""
                    SELECT t.request_id FROM request_target t JOIN submission s ON s.request_id=t.request_id
                    AND s.organization_id=t.organization_id WHERE s.id=? AND t.excluded=FALSE
                    """, submissionId).isEmpty()) throw conflict("REQUEST_TARGET_EXCLUDED", "현재 제출 대상에서 제외된 조직입니다.");
            String objectKey = "review-attachment/" + UUID.randomUUID();
            jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES (?,?,?)").params(objectKey, contentType, bytes).update();
            jdbc.sql("""
                    INSERT INTO review_attachment(explanation_id,original_filename,content_type,byte_size,sha256,object_key,uploaded_by,uploaded_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """).params(explanation.get("id"), filename, contentType, bytes.length, digest, objectKey, id(actor.id()), OffsetDateTime.now(ZoneOffset.UTC)).update();
            jdbc.sql("UPDATE review_explanation SET attachment_revision=attachment_revision+1 WHERE id=?").param(explanation.get("id")).update();
            var attachment = metadata(rows("""
                    SELECT a.*,e.round,e.review_item_id,u.name AS uploader_name
                    FROM review_attachment a JOIN review_explanation e ON e.id=a.explanation_id
                    JOIN app_user u ON u.id=a.uploaded_by WHERE a.object_key=?
                    """, objectKey).getFirst());
            return new CommandExecutor.Result(201, json(object("attachment", attachment, "review", reviews.get(actor, reviewId))),
                    "/api/v1/review-items/" + reviewId + "/attachments/" + attachment.get("id") + "/file");
        });
    }

    /** 특정 검토 항목에 속한 첨부 원문을 권한 확인 후 그대로 읽는다.
     * Args: actor 인증 사용자, reviewId 항목, attachmentId 첨부. Returns: 다운로드 원문·검증된 이름·MIME.
     * Raises: ApiException 다른 조직·항목 첨부 404. Side Effects: DB 읽기만 수행한다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AttachmentFile file(Actor actor, String reviewId, String attachmentId) {
        reviews.get(actor, reviewId);
        var found = rows("""
                SELECT a.original_filename,a.content_type,o.payload
                FROM review_attachment a JOIN review_explanation e ON e.id=a.explanation_id
                JOIN stored_object o ON o.object_key=a.object_key WHERE a.id=? AND e.review_item_id=?
                """, id(attachmentId), id(reviewId));
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "첨부 파일을 찾을 수 없습니다.");
        var row = found.getFirst();
        return new AttachmentFile(row.get("original_filename").toString(), row.get("content_type").toString(), (byte[]) row.get("payload"));
    }

    /** 파일명·크기·선언 MIME과 실제 파일 헤더를 함께 확인한다.
     * Args: filename 원래 파일명, declaredType 클라이언트 MIME, bytes 원문.
     * Returns: 허용한 정규 MIME. Raises: ApiException 형식·크기 위반. Side Effects: 메모리 바이트만 읽는다.
     */
    private String validateFile(String filename, String declaredType, byte[] bytes) {
        if (filename == null || filename.isBlank() || filename.length() > 255 || !filename.equals(filename.strip())
                || filename.chars().anyMatch(value -> value < 32 || value == 127) || filename.contains("/") || filename.contains("\\")) throw invalid("첨부 파일 이름을 확인해 주세요.");
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid("첨부 파일은 비어 있지 않은 16MB 이하 파일이어야 합니다.");
        String lower = filename.toLowerCase(Locale.ROOT);
        String type;
        if (lower.endsWith(".pdf") && prefix(bytes, new byte[]{37,80,68,70,45})) type = "application/pdf";
        else if (lower.endsWith(".png") && prefix(bytes, new byte[]{(byte)137,80,78,71,13,10,26,10})) type = "image/png";
        else if ((lower.endsWith(".jpg") || lower.endsWith(".jpeg")) && prefix(bytes, new byte[]{(byte)255,(byte)216,(byte)255})) type = "image/jpeg";
        else if (lower.endsWith(".xlsx") && workbookArchive(bytes)) type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        else throw invalid("PDF·XLSX·PNG·JPEG 원본 파일만 첨부할 수 있습니다. 확장자와 파일 형식을 확인해 주세요.");
        if (declaredType != null && !declaredType.isBlank() && !"application/octet-stream".equals(declaredType) && !type.equals(declaredType)) throw invalid("첨부 파일의 MIME 형식과 원문이 일치하지 않습니다.");
        return type;
    }

    /** 바이트 앞부분이 알려진 파일 서명과 같은지 확인한다.
     * Args: bytes 원문, signature 고정 파일 서명. Returns: 일치 여부. Raises: 없음. Side Effects: 없음.
     */
    private boolean prefix(byte[] bytes, byte[] signature) {
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
        return true;
    }

    /** XLSX의 필수 ZIP 항목을 제한된 메모리 읽기로 확인한다.
     * Args: bytes 원문 압축 바이트. Returns: 통합문서 항목 존재 여부. Raises: 없음(깨짐·과도한 압축은 false).
     * Side Effects: 파일을 풀어 저장하지 않으며 최대 64MB·5,000개 항목까지만 메모리로 읽는다.
     */
    private boolean workbookArchive(byte[] bytes) {
        boolean content = false, workbook = false; long expanded = 0; int entries = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var buffer = new byte[8192];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > 5000) return false;
                content |= "[Content_Types].xml".equals(entry.getName()); workbook |= "xl/workbook.xml".equals(entry.getName());
                int length;
                while ((length = zip.read(buffer)) != -1) { expanded += length; if (expanded > 64L * 1024 * 1024) return false; }
            }
            return content && workbook;
        } catch (IOException exception) { return false; }
    }

    /** 내부 저장 경로를 제외한 공개 첨부 메타데이터를 만든다.
     * Args: row DB 조인 행. Returns: 첨부 DTO. Raises: 저장 행 타입 오류. Side Effects: 없음.
     */
    private Map<String, Object> metadata(Map<String, Object> row) {
        return object("id", row.get("id").toString(), "reviewItemId", row.get("review_item_id").toString(), "round", row.get("round"),
                "filename", row.get("original_filename"), "contentType", row.get("content_type"), "size", row.get("byte_size"),
                "sha256", row.get("sha256"), "uploadedAt", instant(row.get("uploaded_at")),
                "uploadedBy", object("id", row.get("uploaded_by").toString(), "name", row.get("uploader_name")));
    }

    /** 위치 매개변수로 내부 SQL을 실행한다.
     * Args: sql 내부 고정 SQL, params 바인딩 값. Returns: 결과 행 목록. Raises: DB 오류. Side Effects: DB 읽기.
     */
    private List<Map<String, Object>> rows(String sql, Object... params) { return jdbc.sql(sql).params(params).query().listOfRows(); }

    /** 응답과 멱등 해시 입력을 JSON으로 직렬화한다.
     * Args: value 공개 DTO. Returns: JSON. Raises: IllegalStateException 직렬화 실패. Side Effects: 없음.
     */
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException exception) { throw new IllegalStateException("첨부 응답 직렬화 실패", exception); } }

    /** 파일·입력 오류를 고정 상태로 구성한다.
     * Args: message 공개 안내. Returns: 400 예외. Raises: 없음. Side Effects: 없음.
     */
    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT", message); }

    /** 현재 소명 상태와 충돌하는 명령을 거절한다.
     * Args: code 오류 코드, message 공개 안내. Returns: 409 예외. Raises: 없음. Side Effects: 없음.
     */
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }

    /** 검증된 다운로드 파일 정보를 담는다. 자동 접근자는 저장된 메타데이터·바이트를 반환한다. */
    public record AttachmentFile(String filename, String contentType, byte[] bytes) {}
}
