package io.skala.backend.master;

import io.skala.backend.security.ActorResolver;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 월별 ACTUAL 마스터의 발행 현황·고정 값 조회 경계다. */
@RestController
@RequestMapping("/api/v1")
public class MonthlyMasterController {
    private final MonthlyMasterService service;
    private final ActorResolver actors;
    /** 의존성을 연결한다. Args: service 월마스터, actors 인증. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public MonthlyMasterController(MonthlyMasterService service, ActorResolver actors) { this.service = service; this.actors = actors; }
    /** 12개월 발행 현황을 읽는다. Args: query year. Returns: 기간 현황. Raises: 권한·입력·근거오류. Side Effects: DB읽기. */
    @GetMapping("/master-periods")
    public Map<String, Object> periods(@RequestParam Map<String, String> query) { return service.periods(actors.current(), query); }
    /** 지정 월 이력을 읽는다. Args: query year·month·limit·cursor. Returns: 페이지. Raises: 권한·입력오류. Side Effects: DB읽기. */
    @GetMapping("/masters")
    public Map<String, Object> list(@RequestParam Map<String, String> query) { return service.list(actors.current(), query); }
    /** 고정 월 버전을 읽는다. Args: masterId 버전,query 빈쿼리. Returns: 상세. Raises: 권한·입력·404·근거오류. Side Effects: DB읽기. */
    @GetMapping("/masters/{masterId}")
    public Map<String, Object> detail(@PathVariable String masterId, @RequestParam Map<String, String> query) { return service.detail(actors.current(), masterId, query); }
    /** ACTUAL 보고값을 읽는다. Args: masterId 버전,query 조직·항목·페이지. Returns: 페이지. Raises: 권한·입력·404·근거오류. Side Effects: DB읽기. */
    @GetMapping("/masters/{masterId}/report-values")
    public Map<String, Object> reportValues(@PathVariable String masterId, @RequestParam Map<String, String> query) { return service.reportValues(actors.current(), masterId, query); }
}
