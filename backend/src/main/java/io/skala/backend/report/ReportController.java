package io.skala.backend.report;

import io.skala.backend.common.ApiException;
import io.skala.backend.security.ActorResolver;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 선택한 실제 마스터의 보고 데이터와 메모리 Excel 다운로드를 제공한다. */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final ReportService service;
    private final ActorResolver actors;

    /** 보고 조회·현재 사용자 의존성을 연결한다.
     * Args: service 보고 조회, actors 인증 사용자. Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public ReportController(ReportService service, ActorResolver actors) { this.service = service; this.actors = actors; }

    /** 명시한 버전들의 실제 보고 데이터를 읽는다.
     * Args: input 선택 버전·소명 포함, query 빈 쿼리. Returns: 보고 데이터. Raises: 권한·검증 오류. Side Effects: DB 읽기만 수행한다.
     */
    @PostMapping("/preview")
    public Map<String,Object> preview(@Valid @RequestBody ReportService.Input input, @RequestParam Map<String,String> query) {
        checkQuery(query); return service.preview(actors.current(), input);
    }

    /** 같은 보고 계약의 표와 출처를 XLSX 바이트로 내려준다.
     * Args: input 선택 버전·소명 포함, query 빈 쿼리. Returns: XLSX 첨부 응답.
     * Raises: 권한·검증·메모리 직렬화 오류. Side Effects: DB 읽기·메모리 ZIP만 사용하며 서버 파일을 생성하지 않는다.
     */
    @PostMapping("/excel")
    public ResponseEntity<byte[]> excel(@Valid @RequestBody ReportService.Input input, @RequestParam Map<String,String> query) {
        checkQuery(query);
        byte[] bytes = ReportWorkbook.write(service.preview(actors.current(), input));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=financial-report.xlsx")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(bytes);
    }

    /** 보고 API에 정의되지 않은 조회 조건을 차단한다.
     * Args: query 요청 쿼리. Returns: 없음. Raises: 400 입력 오류. Side Effects: 없음.
     */
    private void checkQuery(Map<String,String> query) { if (!query.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "지원하지 않는 조회 조건입니다."); }
}
