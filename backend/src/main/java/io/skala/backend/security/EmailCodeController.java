package io.skala.backend.security;

import io.skala.backend.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 루프백 시연 화면에만 이메일 코드·세션 API를 노출한다. */
@RestController
@Profile("local & demo")
@RequestMapping("/api/v1/auth")
public class EmailCodeController {
    private final EmailCodeSessions sessions;
    private final Set<String> origins=new HashSet<>(), hosts=new HashSet<>();
    /** 허용 루프백 주소를 읽는다. Args: sessions 코드 서비스, environment 설정. Returns: 없음. Raises: 외부주소 구성 오류. Side Effects: 설정 읽기. */
    public EmailCodeController(EmailCodeSessions sessions,Environment environment) {
        this.sessions=sessions;
        for (String value:environment.getProperty("app.demo.frontend-origin","http://localhost:5173,http://127.0.0.1:5173").split(",")) {
            URI uri=URI.create(value.trim());
            if (!"http".equals(uri.getScheme()) || !Set.of("localhost","127.0.0.1").contains(uri.getHost()) || uri.getPort()<1 || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || (uri.getRawPath()!=null&&!uri.getRawPath().isEmpty())) throw new IllegalArgumentException("인증 체험 Origin은 루프백 HTTP만 허용합니다.");
            origins.add(value.trim());hosts.add(uri.getRawAuthority());
        }
        int port=environment.getProperty("server.port",Integer.class,8080);hosts.add("localhost:"+port);hosts.add("127.0.0.1:"+port);
    }
    /** 코드를 발급한다. Args: input 이메일, request 요청. Returns: 로컬 수신 코드. Raises: 인증·Origin·제한 오류. Side Effects: 메모리 챌린지 저장. */
    @PostMapping(value="/codes",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String,Object>> request(@RequestBody CodeRequest input,HttpServletRequest request) { guard(request);return response(sessions.request(input.email())); }
    /** 코드를 소비해 세션을 발급한다. Args: input 코드·챌린지,request 요청. Returns: 세션. Raises: 코드·Origin 오류. Side Effects: 세션 메모리 저장. */
    @PostMapping(value="/sessions",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String,Object>> verify(@RequestBody CodeVerification input,HttpServletRequest request) { guard(request);return response(sessions.verify(input.challengeId(),input.code())); }
    /** 현재 세션을 폐기한다. Args: authorization 인증 헤더. Returns: 종료 결과. Raises: 없음. Side Effects: 세션 메모리 삭제. */
    @DeleteMapping("/session") public Map<String,Object> logout(@RequestHeader("Authorization") String authorization) { sessions.revoke(authorization.substring(7));return Map.of("loggedOut",true); }
    /** 허용된 프론트의 Origin·Host를 검사한다. Args: request 요청. Returns: 없음. Raises: 403. Side Effects: 없음. */
    private void guard(HttpServletRequest request) {
        var origin=Collections.list(request.getHeaders("Origin"));var host=Collections.list(request.getHeaders("Host"));
        if (origin.size()!=1||host.size()!=1||!origins.contains(origin.getFirst())||!hosts.contains(host.getFirst())) throw new ApiException(HttpStatus.FORBIDDEN,"DEMO_ORIGIN_FORBIDDEN","허용된 로컬 화면에서 로그인해 주세요.");
    }
    /** 인증응답의 캐시를 금지한다. Args: body DTO. Returns: HTTP 200. Raises: 없음. Side Effects: 없음. */
    private ResponseEntity<Map<String,Object>> response(Map<String,Object> body) { return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(body); }
    public record CodeRequest(String email) {}
    public record CodeVerification(String challengeId,String code) {}
}
