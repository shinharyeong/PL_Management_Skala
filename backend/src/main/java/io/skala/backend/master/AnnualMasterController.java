package io.skala.backend.master;

import io.skala.backend.security.ActorResolver;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 승인으로 발행된 연간 계획 마스터를 경영기획 담당자에게 조회한다. */
@RestController
@RequestMapping("/api/v1")
public class AnnualMasterController {
    private final AnnualMasterService service;
    private final ActorResolver actors;

    /** HTTP 인증과 마스터 조회 서비스를 연결한다.
     * Args: service 마스터 서비스, actors 인증 해석기. Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public AnnualMasterController(AnnualMasterService service, ActorResolver actors) {
        this.service = service;
        this.actors = actors;
    }

    /** 요청의 최초 발행 대기 상태와 현재 마스터를 반환한다.
     * Args: requestId 요청 ID, query 빈 쿼리. Returns: 발행 현황. Raises: 권한·입력·404 오류. Side Effects: DB 읽기.
     */
    @GetMapping("/submission-requests/{requestId}/annual-master")
    public Map<String, Object> status(@PathVariable String requestId, @RequestParam Map<String, String> query) {
        return service.status(actors.current(), requestId, query);
    }

    /** 한 요청의 발행 이력을 페이지로 반환한다.
     * Args: query requestId·limit·cursor. Returns: 이력 페이지. Raises: 권한·입력·404 오류. Side Effects: DB 읽기.
     */
    @GetMapping("/annual-masters")
    public Map<String, Object> list(@RequestParam Map<String, String> query) {
        return service.list(actors.current(), query);
    }

    /** 고정된 마스터의 출처와 월별 합계를 반환한다.
     * Args: masterId 마스터 ID, query 빈 쿼리. Returns: 마스터 상세. Raises: 권한·입력·404·근거 오류. Side Effects: DB 읽기.
     */
    @GetMapping("/annual-masters/{masterId}")
    public Map<String, Object> detail(@PathVariable String masterId, @RequestParam Map<String, String> query) {
        return service.detail(actors.current(), masterId, query);
    }

    /** 고정된 마스터의 전사 또는 지정 조직 보고값을 반환한다.
     * Args: masterId 마스터 ID, query organizationId·month·limit·cursor. Returns: 보고값 페이지.
     * Raises: 권한·입력·404·근거 오류. Side Effects: DB 읽기.
     */
    @GetMapping("/annual-masters/{masterId}/report-values")
    public Map<String, Object> reportValues(@PathVariable String masterId, @RequestParam Map<String, String> query) {
        return service.reportValues(actors.current(), masterId, query);
    }
}
