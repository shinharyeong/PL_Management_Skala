package io.skala.backend.submission;

import com.fasterxml.jackson.databind.JsonNode;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.Actor;
import io.skala.backend.security.ActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

/** 인증·공통 분류·요청 관리·원본 업로드와 파싱 조회 API를 노출한다. */
@RestController
@RequestMapping("/api/v1")
public class SubmissionController {
    private final SubmissionService service;
    private final ActorResolver actors;

    /** HTTP와 서비스 경계를 연결한다.
     * Args: service 업무 서비스, actors 인증 해석기. Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public SubmissionController(SubmissionService service, ActorResolver actors) {
        this.service = service;
        this.actors = actors;
    }

    /** 현재 사용자·역할·조직을 반환한다.
     * Args: query 빈 쿼리. Returns: 인증 사용자. Raises: 인증/쿼리 오류. Side Effects: DB 읽기.
     */
    @GetMapping("/me")
    public Actor me(@RequestParam Map<String, String> query) {
        service.checkQuery(query, Set.of());
        return actors.current();
    }

    /** 접근 가능한 조직을 조회한다.
     * Args: query 목록 필터. Returns: 조직 페이지. Raises: 인증/필터 오류. Side Effects: DB 읽기.
     */
    @GetMapping("/organizations")
    public Map<String, Object> organizations(@RequestParam Map<String, String> query) {
        return service.organizations(actors.current(), query);
    }

    /** 공통 보고 항목의 실제 분류와 계층을 조회한다.
     * Args: query active/kind/페이지 필터. Returns: 보고 항목 페이지.
     * Raises: 인증·필터 오류. Side Effects: DB 읽기만 수행한다.
     */
    @GetMapping("/report-items")
    public Map<String, Object> reportItems(@RequestParam Map<String, String> query) {
        return service.reportItems(actors.current(), query);
    }

    /** 전 조직 대상 요청을 등록한다.
     * Args: key 멱등키, input 요청 DTO, query 빈 쿼리. Returns: 201/재전달 응답.
     * Raises: 권한/검증/상태 오류. Side Effects: DB 생성.
     */
    @PostMapping("/submission-requests")
    public ResponseEntity<String> createRequest(@RequestHeader(value="Idempotency-Key", required=false) String key,
            @Valid @RequestBody SubmissionService.RequestInput input, @RequestParam Map<String, String> query) {
        service.checkQuery(query, Set.of());
        return command(service.createRequest(actors.current(), key, input));
    }

    /** 권한별 요청함을 조회한다.
     * Args: query 목록 필터. Returns: 요청 페이지. Raises: 인증/필터 오류. Side Effects: DB 읽기.
     */
    @GetMapping("/submission-requests")
    public Map<String, Object> requests(@RequestParam Map<String, String> query) {
        return service.requests(actors.current(), query);
    }

    /** 요청 상세와 권한 내 대상 현황을 조회한다.
     * Args: requestId ID, query 빈 쿼리. Returns: 요청 상세. Raises: 인증/404. Side Effects: DB 읽기.
     */
    @GetMapping("/submission-requests/{requestId}")
    public Map<String, Object> request(@PathVariable String requestId, @RequestParam Map<String, String> query) {
        service.checkQuery(query, Set.of());
        return service.request(actors.current(), requestId);
    }

    /** 요청 안내·기한·개폐 상태를 생략과 null을 구분해 변경한다.
     * Args: requestId 요청, key 멱등 키, input 변경 JSON, query 빈 쿼리.
     * Returns: 갱신된 요청 상세 200/재전달 응답. Raises: 권한·입력·토큰·상태 충돌.
     * Side Effects: 요청 헤더와 멱등 성공 응답을 DB에 저장한다.
     */
    @PatchMapping("/submission-requests/{requestId}")
    public ResponseEntity<String> updateRequest(@PathVariable String requestId,
            @RequestHeader(value="Idempotency-Key", required=false) String key,
            @RequestBody JsonNode input, @RequestParam Map<String, String> query) {
        service.checkQuery(query, Set.of());
        return command(service.updateRequest(actors.current(), requestId, key, input));
    }

    /** 한 개의 전체 XLSX 파일을 접수한다.
     * Args: requestId 요청, key 멱등키, file 유일 파일 part, request multipart 요청.
     * Returns: 202/재전달 응답. Raises: IOException 입력 읽기 오류, ApiException 잘못된 parts.
     * Side Effects: DB 원본/작업 저장; multipart는 설정된 17MiB 메모리 임계값 내에서 처리.
     */
    @PostMapping(value="/submission-requests/{requestId}/submissions", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> upload(@PathVariable String requestId,
            @RequestHeader(value="Idempotency-Key", required=false) String key,
            @RequestPart("file") MultipartFile file, MultipartHttpServletRequest request) throws IOException {
        if (!request.getParameterMap().isEmpty() || request.getMultiFileMap().size() != 1
                || request.getFiles("file").size() != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "file part 하나만 허용합니다.");
        }
        return command(service.upload(actors.current(), requestId, key, file.getOriginalFilename(), file.getBytes()));
    }

    /** 제출 이력을 조회한다.
     * Args: query 목록 필터. Returns: 제출 페이지. Raises: 인증/필터 오류. Side Effects: DB 읽기.
     */
    @GetMapping("/submissions")
    public Map<String, Object> submissions(@RequestParam Map<String, String> query) {
        return service.submissions(actors.current(), query);
    }

    /** 파싱·검증 준비 상태를 폴링한다.
     * Args: submissionId 제출, query 빈 쿼리. Returns: 제출 상세. Raises: 인증/404. Side Effects: DB 읽기.
     */
    @GetMapping("/submissions/{submissionId}")
    public Map<String, Object> submission(@PathVariable String submissionId, @RequestParam Map<String, String> query) {
        service.checkQuery(query, Set.of());
        return service.submission(actors.current(), submissionId);
    }

    /** 운영 오류의 동일 원본 재파싱을 접수한다.
     * Args: submissionId 제출, key 멱등키, input 기대 상태, query 빈 쿼리.
     * Returns: 202/재전달 응답. Raises: 권한/상태 오류. Side Effects: DB 작업 갱신.
     */
    @PostMapping("/submissions/{submissionId}/parsing-retries")
    public ResponseEntity<String> retry(@PathVariable String submissionId,
            @RequestHeader(value="Idempotency-Key", required=false) String key,
            @Valid @RequestBody SubmissionService.RetryInput input, @RequestParam Map<String, String> query) {
        service.checkQuery(query, Set.of());
        return command(service.retry(actors.current(), submissionId, key, input));
    }

    /** 현재 시도의 안전한 진단을 조회한다.
     * Args: submissionId 제출, query 진단 필터. Returns: 진단 페이지. Raises: 인증/상태 오류. Side Effects: DB 읽기.
     */
    @GetMapping("/submissions/{submissionId}/diagnostics")
    public Map<String, Object> diagnostics(@PathVariable String submissionId, @RequestParam Map<String, String> query) {
        return service.diagnostics(actors.current(), submissionId, query);
    }

    /** 원본을 재계산 없이 내려준다.
     * Args: submissionId 제출, query 빈 쿼리. Returns: attachment 바이트. Raises: 인증/404. Side Effects: DB 읽기.
     */
    @GetMapping("/submissions/{submissionId}/file")
    public ResponseEntity<byte[]> original(@PathVariable String submissionId, @RequestParam Map<String, String> query) {
        service.checkQuery(query, Set.of());
        var file = service.original(actors.current(), submissionId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }

    /** 저장된 성공 응답의 HTTP 헤더를 복구한다.
     * Args: result 트랜잭션 결과. Returns: JSON HTTP 응답. Raises: 없음. Side Effects: 없음.
     */
    private ResponseEntity<String> command(CommandExecutor.Result result) {
        var builder = ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON);
        if (!result.location().isBlank()) builder.header(HttpHeaders.LOCATION, result.location());
        if (result.replayed()) builder.header("Idempotency-Replayed", "true");
        return builder.body(result.body());
    }
}
