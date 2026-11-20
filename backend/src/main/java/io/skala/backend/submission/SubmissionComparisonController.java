package io.skala.backend.submission;

import io.skala.backend.review.ReviewService;
import io.skala.backend.security.ActorResolver;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 사용자가 선택한 제출 버전의 읽기 전용 비교를 연결한다. */
@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionComparisonController {
    private final ReviewService reviews;
    private final ActorResolver actors;
    private final ComparisonExplanationService requests;

    /** 비교·인증 의존성을 받는다. Args: reviews 비교 서비스, actors 인증, requests 명시 요청. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public SubmissionComparisonController(ReviewService reviews,ActorResolver actors,ComparisonExplanationService requests) { this.reviews=reviews;this.actors=actors;this.requests=requests; }

    /** 임의 기준 버전과 현재 제출본을 비교한다. Args: submissionId 현재 제출, query 기준본·시나리오·월. Returns: 비교 DTO. Raises: 권한·검증 오류. Side Effects: DB 읽기. */
    @GetMapping("/{submissionId}/comparison")
    public Map<String,Object> comparison(@PathVariable String submissionId,@RequestParam Map<String,String> query) { return reviews.compareSubmissions(actors.current(),submissionId,query); }

    /** 선택한 버전 비교를 별도 소명 회차의 근거로 저장한다.
     * Args: submissionId 현재 제출, key 멱등키, input 비교·항목·토큰, query 빈 쿼리. Returns: 검토·제출 상세.
     * Raises: 권한·입력·충돌. Side Effects: 서비스 트랜잭션 DB 저장; 금융값·고정 Gap 보존.
     */
    @PostMapping("/{submissionId}/comparison/explanation-requests")
    public ResponseEntity<String> request(@PathVariable String submissionId,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode input,@RequestParam Map<String,String> query){
        if(!query.isEmpty())throw new io.skala.backend.common.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_REQUEST","지원하지 않는 쿼리입니다.");
        var result=requests.request(actors.current(),submissionId,key,input);var response=ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON);if(result.replayed())response.header("Idempotency-Replayed","true");return response.body(result.body());
    }
}
