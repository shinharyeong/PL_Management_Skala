package io.skala.backend.master;

import io.skala.backend.security.ActorResolver;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 월간 요청의 최신 승인본 기준 연간 전망을 제공하는 읽기 경계다. */
@RestController
@RequestMapping("/api/v1/submission-requests")
public class AnnualForecastController {
    private final AnnualForecastService service;
    private final ActorResolver actors;

    /** 읽기 서비스와 인증된 사용자를 연결한다.
     * Args: service 전망 조회, actors 인증. Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public AnnualForecastController(AnnualForecastService service, ActorResolver actors) { this.service = service; this.actors = actors; }

    /** 같은 월간 요청의 최신 승인본을 고정해 연간 전망을 읽는다.
     * Args: requestId 월간 요청, query 빈 쿼리. Returns: 출처·완전성·월별/연간 손익.
     * Raises: 권한·입력·404·근거 오류. Side Effects: DB 읽기만 수행한다.
     */
    @GetMapping("/{requestId}/forecast")
    public Map<String, Object> get(@PathVariable String requestId, @RequestParam Map<String, String> query) { return service.get(actors.current(), requestId, query); }
}
