package io.skala.backend.parser;

import io.skala.backend.security.ActorResolver;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 모든 프로필에 등록되며 기존 인증 경계가 보호하는 파싱 후보 조회 API다. */
@RestController
@RequestMapping("/api/v1/submissions")
public class ParsedRecordsController {
    private final ParsedRecordsService service;
    private final ActorResolver actors;

    /** 인증 및 읽기 전용 서비스를 받는다.
     * Args: service 조회 서비스, actors DB 인증 해석기. Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public ParsedRecordsController(ParsedRecordsService service, ActorResolver actors) { this.service = service; this.actors = actors; }

    /** 본인 조직 또는 기획 권한의 제출 후보 페이지를 반환한다.
     * Args: submissionId 제출, query limit/cursor. Returns: summary/items/nextCursor.
     * Raises: 인증/권한/상태/쿼리 오류. Side Effects: 저장 결과 DB 읽기만 수행.
     */
    @GetMapping("/{submissionId}/parsed-records")
    public Map<String, Object> records(@PathVariable String submissionId, @RequestParam Map<String, String> query) {
        return service.records(actors.current(), submissionId, query);
    }
}
