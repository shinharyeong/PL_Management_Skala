package io.skala.backend.review;

import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.ActorResolver;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 검토 목록·근거·소명 HTTP 계약을 연결한다. */
@RestController
@RequestMapping("/api/v1/review-items")
public class ReviewController {
    private final ReviewService service;
    private final ActorResolver actors;
    /** 의존성을 받는다. Args: service 검토, actors 인증. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public ReviewController(ReviewService service,ActorResolver actors){this.service=service;this.actors=actors;}
    /** 검토 목록을 읽는다. Args: query 필터. Returns: 페이지. Raises: 권한·입력 오류. Side Effects: DB 읽기. */
    @GetMapping public Map<String,Object> list(@RequestParam Map<String,String> query){return service.list(actors.current(),query);}
    /** 검토 상세를 읽는다. Args: reviewId 항목, query 빈조건. Returns: 상세. Raises: 권한·입력 오류. Side Effects: DB 읽기. */
    @GetMapping("/{reviewId}") public Map<String,Object> get(@PathVariable String reviewId,@RequestParam Map<String,String> query){service.checkQuery(query,Set.of());return service.get(actors.current(),reviewId);}
    /** 원천 변경 근거를 읽는다. Args: reviewId 항목, query 비교선택. Returns: 근거페이지. Raises: 권한·입력 오류. Side Effects: DB 읽기. */
    @GetMapping("/{reviewId}/evidence") public Map<String,Object> evidence(@PathVariable String reviewId,@RequestParam Map<String,String> query){return service.evidence(actors.current(),reviewId,query);}
    /** 현재 회차에 답변한다. Args: reviewId 항목, key 멱등키, input 답변, query 빈조건. Returns: 상세응답. Raises: 권한·상태 오류. Side Effects: DB 명령. */
    @PutMapping("/{reviewId}/answer") public ResponseEntity<String> answer(@PathVariable String reviewId,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ReviewService.AnswerInput input,@RequestParam Map<String,String> query){service.checkQuery(query,Set.of());return response(service.answer(actors.current(),reviewId,key,input));}
    /** 소명을 수용하거나 보완을 요청한다. Args: reviewId 항목, key 멱등키, input 판단, query 빈조건. Returns: 상세응답. Raises: 권한·상태 오류. Side Effects: DB 명령. */
    @PostMapping("/{reviewId}/decisions") public ResponseEntity<String> decide(@PathVariable String reviewId,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ReviewService.DecisionInput input,@RequestParam Map<String,String> query){service.checkQuery(query,Set.of());return response(service.decide(actors.current(),reviewId,key,input));}
    /** 멱등 응답 헤더를 전달한다. Args: result 결과. Returns: HTTP응답. Raises: 없음. Side Effects: 없음. */
    private ResponseEntity<String> response(CommandExecutor.Result result){var response=ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON);if(result.replayed())response.header("Idempotency-Replayed","true");return response.body(result.body());}
}
