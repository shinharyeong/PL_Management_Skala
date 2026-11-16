package io.skala.backend.demo;

import io.skala.backend.common.ApiException;
import io.skala.backend.security.ActorResolver;
import io.skala.backend.submission.SubmissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 실제 로그인이 아닌 local+demo 역할 체험과 인증된 합성 양식 다운로드 경계다. */
@RestController
@Profile("local & demo")
@RequestMapping("/api/dev")
public class DemoController {
    private final DemoAccounts accounts;
    private final DemoTemplateService templates;
    private final ActorResolver actors;
    private final SubmissionService submissions;
    private final Set<String> origins = new HashSet<>();
    private final Set<String> hosts = new HashSet<>();

    /** 루프백 프런트엔드 Origin·Host만 체험 토큰을 받을 수 있도록 구성한다.
     * Args: accounts 개발 자격, templates 합성 양식, actors 인증, submissions 쿼리 검증, environment 설정.
     * Returns: 없음. Raises: IllegalArgumentException 외부 Origin 설정. Side Effects: 환경 읽기; 토큰 출력 없음.
     */
    public DemoController(DemoAccounts accounts, DemoTemplateService templates, ActorResolver actors,
            SubmissionService submissions, Environment environment) {
        this.accounts = accounts;
        this.templates = templates;
        this.actors = actors;
        this.submissions = submissions;
        String configured = environment.getProperty("app.demo.frontend-origin", "http://localhost:5173,http://127.0.0.1:5173");
        for (String value : configured.split(",")) {
            String origin = value.trim();
            URI uri = URI.create(origin);
            if (!"http".equals(uri.getScheme()) || !Set.of("localhost", "127.0.0.1").contains(uri.getHost())
                    || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getRawUserInfo() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("체험 Origin은 포트를 명시한 루프백 HTTP 주소여야 합니다.");
            }
            origins.add(origin);
            hosts.add(uri.getRawAuthority());
        }
        int port = environment.getProperty("server.port", Integer.class, 8080);
        hosts.add("localhost:" + port);
        hosts.add("127.0.0.1:" + port);
    }

    /** 허용 Origin·Host의 JSON 요청에만 개발 역할 자격을 반환한다.
     * Args: input 체험 역할·선택 조직, request HTTP 헤더, query 빈 쿼리.
     * Returns: token·DB Actor·허용 활성 조직 선택 목록. Raises: 403 Origin/Host, 400 역할/조직/쿼리, DB 계정 충돌.
     * Side Effects: DB 읽기; 토큰은 응답에서만 전달하고 저장·쿠키 발급·로깅 없음.
     */
    @PostMapping(value="/session", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> session(@Valid @RequestBody SessionInput input,
            HttpServletRequest request, @RequestParam Map<String, String> query) {
        submissions.checkQuery(query, Set.of());
        var originHeaders = Collections.list(request.getHeaders("Origin"));
        var hostHeaders = Collections.list(request.getHeaders("Host"));
        if (originHeaders.size() != 1 || hostHeaders.size() != 1
                || !origins.contains(originHeaders.getFirst()) || !hosts.contains(hostHeaders.getFirst())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_ORIGIN_FORBIDDEN", "허용된 로컬 체험 화면에서 요청해야 합니다.");
        }
        var actor = accounts.actor(input.role(), input.organizationCode());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(Map.of("token", accounts.token(input.role(), input.organizationCode()), "actor", actor,
                        "organizations", accounts.organizations(input.role())));
    }

    /** 본인 조직의 요청에 맞는 합성 XLSX를 메모리로 내려준다.
     * Args: query 필수 requestId 한 개. Returns: XLSX attachment.
     * Raises: 인증/권한/쿼리/요청 오류. Side Effects: DB 읽기·메모리 ZIP; 파일 저장 없음.
     */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam Map<String, String> query) {
        submissions.checkQuery(query, Set.of("requestId"));
        var file = templates.generate(actors.current(), query.get("requestId"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }

    /** 실제 로그인 자격이 아닌 체험 역할·선택 조직이며 생략한 조직은 기존 D100이다. */
    public record SessionInput(@NotBlank @Pattern(regexp="PLANNER|ORG_MANAGER") String role,
            @Size(max=80) String organizationCode) {}
}
