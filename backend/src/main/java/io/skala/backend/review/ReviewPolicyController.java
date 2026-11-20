package io.skala.backend.review;

import io.skala.backend.security.ActorResolver;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 임계값과 별도로 전역 소명 필수 모드를 제공한다. */
@RestController
@RequestMapping("/api/v1/review-policy")
public class ReviewPolicyController {
    private final ReviewPolicyService service;private final ActorResolver actors;private final ReviewService reviews;
    /** 경로 의존성을 받는다. Args: service 정책, actors 인증, reviews 필터검증. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public ReviewPolicyController(ReviewPolicyService service,ActorResolver actors,ReviewService reviews) { this.service=service;this.actors=actors;this.reviews=reviews; }
    /** 현재 정책을 읽는다. Args: query 빈조건. Returns: 정책 DTO. Raises: 인증·입력 오류. Side Effects: DB 읽기. */
    @GetMapping("/current") public Map<String,Object> current(@RequestParam Map<String,String> query) { reviews.checkQuery(query,Set.of());return service.current(actors.current()); }
    /** 후보 모드의 필수 건수를 평가한다. Args: input 요청·모드, query 빈조건. Returns: 영향 건수. Raises: 권한·입력 오류. Side Effects: DB 읽기. */
    @PostMapping("/preview") public Map<String,Object> preview(@Valid @RequestBody ReviewPolicyService.PreviewInput input,@RequestParam Map<String,String> query) { reviews.checkQuery(query,Set.of());return service.preview(actors.current(),input); }
    /** 새 정책 이력을 적용한다. Args: key 멱등키, input 토큰·모드, query 빈조건. Returns: 생성 정책. Raises: 권한·입력·상태 오류. Side Effects: 정책·멱등 응답 저장. */
    @PostMapping public ResponseEntity<String> apply(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ReviewPolicyService.ApplyInput input,@RequestParam Map<String,String> query) {
        reviews.checkQuery(query,Set.of());var result=service.apply(actors.current(),key,input);var response=ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON);if(result.replayed())response.header("Idempotency-Replayed","true");return response.body(result.body());
    }
}
