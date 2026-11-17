package io.skala.backend.workspace;

import io.skala.backend.security.ActorResolver;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 인앱 알림 조회와 읽음 API다. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;
    private final ActorResolver actors;
    /** 경계를 연결한다. Args: service 알림, actors 인증. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public NotificationController(NotificationService service, ActorResolver actors) { this.service=service; this.actors=actors; }
    /** 알림을 읽는다. Args: query 필터. Returns: 페이지. Raises: 권한·입력 오류. Side Effects: DB 읽기. */
    @GetMapping public Map<String,Object> list(@RequestParam Map<String,String> query) { return service.list(actors.current(), query); }
    /** 명시한 이벤트를 읽음으로 기록한다. Args: input 식별자들. Returns: 요약. Raises: 권한·입력 오류. Side Effects: DB 변경. */
    @PostMapping("/read") public Map<String,Object> read(@RequestBody ReadInput input) { return service.read(actors.current(), input.eventIds()); }
    /** 읽음 대상 목록. 자동 접근자는 입력을 반환하며 외부 부작용이 없다. */
    public record ReadInput(List<String> eventIds) {}
}
