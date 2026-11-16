package io.skala.backend.submission;

import com.fasterxml.jackson.databind.JsonNode;
import io.skala.backend.security.ActorResolver;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 조직 권한 내 실제 적재값과 보존한 원본 근거를 노출한다. */
@RestController
@RequestMapping("/api/v1/submissions/{submissionId}")
public class FinancialController {
    private final SubmissionValidationService service;
    private final ActorResolver actors;

    /** 조회 경계를 연결한다. Args: service 검증 조회, actors 인증. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public FinancialController(SubmissionValidationService service, ActorResolver actors) { this.service = service; this.actors = actors; }

    /** 적재 상세를 조회한다. Args: submissionId 제출, query 필터. Returns: 상세 페이지·요약. Raises: API 권한·필터·검증 오류. Side Effects: DB 읽기. */
    @GetMapping("/financial-values")
    public Map<String, Object> financialValues(@PathVariable String submissionId, @RequestParam Map<String, String> query) { return service.financialValues(actors.current(), submissionId, query); }

    /** 보고 항목별 검증 합계를 조회한다. Args: submissionId 제출, query 필터. Returns: 집계 페이지. Raises: API 권한·필터·검증 오류. Side Effects: DB 읽기. */
    @GetMapping("/report-values")
    public Map<String, Object> reportValues(@PathVariable String submissionId, @RequestParam Map<String, String> query) { return service.reportValues(actors.current(), submissionId, query); }

    /** 정확한 원본 셀의 수식·캐시·환산값을 조회한다. Args: submissionId 제출, query 시트·셀. Returns: 보존 셀. Raises: API 권한·위치 오류. Side Effects: DB 읽기. */
    @GetMapping("/source-cells")
    public JsonNode sourceCell(@PathVariable String submissionId, @RequestParam Map<String, String> query) { return service.sourceCell(actors.current(), submissionId, query); }
}
