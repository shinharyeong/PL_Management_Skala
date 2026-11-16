package io.skala.backend.master;

import io.skala.backend.security.ActorResolver;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 연간 계획 조회·명시적 확정 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1/annual-plans/{year}")
public class AnnualPlanController {
    private final AnnualPlanService service;
    private final ActorResolver actors;
    /** 의존성을 연결한다. Args: service 계획, actors 인증. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public AnnualPlanController(AnnualPlanService service, ActorResolver actors) { this.service = service; this.actors = actors; }
    /** 계획과 확정 후보를 읽는다. Args: year 연도, query 빈 쿼리. Returns: 계획. Raises: 권한·입력·404. Side Effects: DB 읽기. */
    @GetMapping
    public Map<String, Object> get(@PathVariable String year, @RequestParam Map<String, String> query) { return service.get(actors.current(), year, query); }
    /** 계획본과 사용자가 입력한 Contingency를 고정한다.
     * Args: year 연도, key 멱등키, input 금액·토큰, query 빈 쿼리. Returns: 확정 계획.
     * Raises: 권한·입력·상태 오류. Side Effects: 같은 트랜잭션의 계획·대상·요청 갱신.
     */
    @PostMapping("/confirm")
    public ResponseEntity<String> confirm(@PathVariable String year, @RequestHeader(value="Idempotency-Key", required=false) String key,
            @Valid @RequestBody AnnualPlanService.ConfirmInput input, @RequestParam Map<String, String> query) {
        var actor = actors.current(); if (!query.isEmpty()) service.get(actor, year, query);
        var result = service.confirm(actor, key, year, input);
        var response = ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON);
        if (result.replayed()) response.header("Idempotency-Replayed", "true"); return response.body(result.body());
    }
}
