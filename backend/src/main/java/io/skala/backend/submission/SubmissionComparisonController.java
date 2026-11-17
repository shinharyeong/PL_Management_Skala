package io.skala.backend.submission;

import io.skala.backend.review.ReviewService;
import io.skala.backend.security.ActorResolver;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 사용자가 선택한 제출 버전의 읽기 전용 비교를 연결한다. */
@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionComparisonController {
    private final ReviewService reviews;
    private final ActorResolver actors;

    /** 비교·인증 의존성을 받는다. Args: reviews 비교 서비스, actors 인증. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public SubmissionComparisonController(ReviewService reviews,ActorResolver actors) { this.reviews=reviews;this.actors=actors; }

    /** 임의 기준 버전과 현재 제출본을 비교한다. Args: submissionId 현재 제출, query 기준본·시나리오·월. Returns: 비교 DTO. Raises: 권한·검증 오류. Side Effects: DB 읽기. */
    @GetMapping("/{submissionId}/comparison")
    public Map<String,Object> comparison(@PathVariable String submissionId,@RequestParam Map<String,String> query) { return reviews.compareSubmissions(actors.current(),submissionId,query); }
}
