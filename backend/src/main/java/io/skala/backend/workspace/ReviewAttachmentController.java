package io.skala.backend.workspace;

import io.skala.backend.common.ApiException;
import io.skala.backend.security.ActorResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/** 소명 첨부의 조회·추가·원문 다운로드 경계를 제공한다. */
@RestController
@RequestMapping("/api/v1/review-items/{reviewId}/attachments")
public class ReviewAttachmentController {
    private final ReviewAttachmentService service;
    private final ActorResolver actors;

    /** 첨부 서비스와 인증 경계를 연결한다.
     * Args: service 첨부 서비스, actors 인증 해석. Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public ReviewAttachmentController(ReviewAttachmentService service, ActorResolver actors) { this.service = service; this.actors = actors; }

    /** 권한 범위 내 소명 회차별 첨부 목록을 반환한다.
     * Args: reviewId 검토 ID, query 빈 쿼리. Returns: 첨부 목록. Raises: 입력·권한 오류. Side Effects: DB 읽기.
     */
    @GetMapping
    public Map<String, Object> list(@PathVariable String reviewId, @RequestParam Map<String, String> query) {
        emptyQuery(query); return service.list(actors.current(), reviewId);
    }

    /** 현재 회차에 한 개의 첨부를 멱등 저장한다.
     * Args: reviewId 검토, key 멱등키, file 원문, round 회차, expectedStateToken 현재 토큰, request multipart.
     * Returns: 첨부와 갱신 검토의 201 응답. Raises: 파일·상태·권한 오류, IOException 메모리 입력 실패.
     * Side Effects: 17MiB multipart 메모리 임계 안에서 읽어 DB 원문·메타데이터를 추가한다.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> add(@PathVariable String reviewId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestPart("file") MultipartFile file, @RequestParam int round,
            @RequestParam String expectedStateToken, MultipartHttpServletRequest request) throws IOException {
        if (request.getMultiFileMap().size() != 1 || request.getFiles("file").size() != 1
                || !request.getParameterMap().keySet().equals(Set.of("round", "expectedStateToken"))
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) throw invalid();
        var result = service.add(actors.current(), reviewId, key, round, expectedStateToken, file.getOriginalFilename(), file.getContentType(), file.getBytes());
        var response = ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON);
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        return response.header(HttpHeaders.LOCATION, result.location()).body(result.body());
    }

    /** 접근 가능한 첨부 원문을 브라우저 실행 없이 다운로드한다.
     * Args: reviewId 검토, attachmentId 첨부, query 빈 쿼리. Returns: 원문 바이트 응답.
     * Raises: 입력·권한·존재 오류. Side Effects: DB 읽기와 사용자 다운로드.
     */
    @GetMapping("/{attachmentId}/file")
    public ResponseEntity<byte[]> file(@PathVariable String reviewId, @PathVariable String attachmentId, @RequestParam Map<String, String> query) {
        emptyQuery(query); var file = service.file(actors.current(), reviewId, attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType())).contentLength(file.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }

    /** 허용하지 않은 읽기 쿼리를 거절한다.
     * Args: query 쿼리맵. Returns: 없음. Raises: ApiException 비어 있지 않은 쿼리. Side Effects: 없음.
     */
    private void emptyQuery(Map<String, String> query) { if (!query.isEmpty()) throw invalid(); }

    /** 잘못된 첨부 요청 형식의 공개 오류를 만든다.
     * Args: 없음. Returns: 400 오류. Raises: 없음. Side Effects: 없음.
     */
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "첨부 파일 한 개와 현재 회차·상태 토큰만 전달해 주세요."); }
}
