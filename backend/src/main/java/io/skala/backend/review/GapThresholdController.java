package io.skala.backend.review;

import io.skala.backend.security.ActorResolver;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Gap 기준 조회·영향 미리보기·새 이력 적용 경로다. */
@RestController
@RequestMapping("/api/v1/gap-thresholds")
public class GapThresholdController {
    private final GapThresholdService service;private final ActorResolver actors;private final ReviewService reviews;
    /** 의존성을 받는다. Args: service 기준, actors 인증, reviews 필터검증. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public GapThresholdController(GapThresholdService service,ActorResolver actors,ReviewService reviews){this.service=service;this.actors=actors;this.reviews=reviews;}
    /** 현재 기준을 읽는다. Args: query 빈조건. Returns: 기준쌍. Raises: 권한·입력 오류. Side Effects: 읽기. */
    @GetMapping("/current") public Map<String,Object> current(@RequestParam Map<String,String> query){reviews.checkQuery(query,Set.of());return service.current(actors.current());}
    /** 후보 기준 영향을 저장 없이 평가한다. Args: input 기준, query 빈조건. Returns: 영향수치. Raises: 권한·입력 오류. Side Effects: 읽기. */
    @PostMapping("/preview") public Map<String,Object> preview(@Valid @RequestBody GapThresholdService.PreviewInput input,@RequestParam Map<String,String> query){reviews.checkQuery(query,Set.of());return service.preview(actors.current(),input);}
    /** 새 기준을 적용한다. Args: key 멱등키, input 기준, query 빈조건. Returns: 생성응답. Raises: 권한·입력·상태 오류. Side Effects: DB 저장. */
    @PostMapping public ResponseEntity<String> apply(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody GapThresholdService.ApplyInput input,@RequestParam Map<String,String> query){reviews.checkQuery(query,Set.of());var result=service.apply(actors.current(),key,input);var response=ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON);if(result.replayed())response.header("Idempotency-Replayed","true");return response.body(result.body());}
}
