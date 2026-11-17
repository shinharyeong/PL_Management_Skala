package io.skala.backend.security;

import io.skala.backend.common.ApiException;
import static io.skala.backend.submission.ApiData.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 로컬 시연용 이메일 코드와 만료·폐기 가능한 세션. 실제 메일 발송은 별도 제공자가 필요하다. */
@Service
@Profile("local & demo")
public class EmailCodeSessions {
    private final ActorResolver actors;
    private final SecureRandom random = new SecureRandom();
    private final Map<String,Challenge> challenges = new HashMap<>();
    private final Map<String,Session> sessions = new HashMap<>();

    /** 인증 사용자 조회를 연결한다. Args: actors DB 사용자. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public EmailCodeSessions(ActorResolver actors) { this.actors=actors; }

    /** 등록된 사용자에게 3분 유효 코드를 발급하며 로컬 수신함으로만 전달한다.
     * Args: email 등록 이메일. Returns: challengeId/만료/재요청 시각 및 localCode.
     * Raises: 미등록·비활성 계정, 60초 이내 재요청. Side Effects: 메모리 챌린지 교체. 메일·파일·로그 없음.
     */
    public synchronized Map<String,Object> request(String email) {
        cleanup();
        String normalized=email==null?"":email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length()>320 || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw invalid("이메일 형식을 확인해 주세요.");
        actors.resolveEmail(normalized);
        Instant now=Instant.now();
        for (var c:challenges.values()) if (c.email.equals(normalized) && now.isBefore(c.resendAt)) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"CODE_RESEND_WAIT","인증코드는 1분 뒤 다시 요청할 수 있습니다.");
        challenges.entrySet().removeIf(e->e.getValue().email.equals(normalized));
        String id=UUID.randomUUID().toString(), code=String.format(Locale.ROOT,"%06d",random.nextInt(1000000));
        challenges.put(id,new Challenge(normalized,hash((id+":"+code).getBytes(StandardCharsets.UTF_8)),now.plusSeconds(180),now.plusSeconds(60)));
        return object("challengeId",id,"email",normalized,"expiresAt",now.plusSeconds(180).toString(),"resendAt",now.plusSeconds(60).toString(),"delivery","LOCAL_PREVIEW","localCode",code);
    }

    /** 일회용 코드를 검증하고 8시간 세션을 만든다.
     * Args: id 챌린지, code 6자리. Returns: 토큰·DB 역할·조직·만료.
     * Raises: 만료·5회 실패·코드 불일치·계정 권한 오류. Side Effects: 챌린지 소비와 해시 세션 메모리 저장.
     */
    public synchronized Map<String,Object> verify(String id,String code) {
        var challenge=challenges.get(id);
        if (challenge==null || !Instant.now().isBefore(challenge.expiresAt)) { challenges.remove(id); throw invalid("유효시간이 경과되어, 코드 재전송을 요청해주세요."); }
        if (code==null || !code.matches("[0-9]{6}") || !MessageDigest.isEqual(challenge.digest.getBytes(StandardCharsets.UTF_8),hash((id+":"+code).getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8))) {
            challenge.attempts++;
            if (challenge.attempts>=5) challenges.remove(id);
            throw invalid(challenge.attempts>=5?"5회 잘못 입력했습니다. 인증코드를 다시 요청해 주세요.":"인증코드가 올바르지 않습니다. 다시 확인해 주세요.");
        }
        var actor=actors.resolveEmail(challenge.email);
        challenges.remove(id); cleanup();
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);Instant expiry=Instant.now().plusSeconds(8*60*60);
        sessions.put(hash(token.getBytes(StandardCharsets.UTF_8)),new Session(actor.email(),expiry));
        return object("token",token,"actor",actor,"expiresAt",expiry.toString(),"organizations",List.of());
    }

    /** 세션 토큰의 해시·만료를 확인한다. Args: token Bearer. Returns: 이메일 또는 null. Raises: 없음. Side Effects: 만료 세션 정리. */
    synchronized String emailFor(String token) { cleanup();var session=sessions.get(hash(token.getBytes(StandardCharsets.UTF_8)));return session==null?null:session.email; }
    /** 세션을 즉시 폐기한다. Args: token Bearer. Returns: 없음. Raises: 없음. Side Effects: 메모리 세션 삭제. */
    public synchronized void revoke(String token) { sessions.remove(hash(token.getBytes(StandardCharsets.UTF_8))); }
    /** 만료된 인증 데이터를 정리한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 메모리 만료 행 제거. */
    private void cleanup() { Instant now=Instant.now();challenges.entrySet().removeIf(e->!now.isBefore(e.getValue().expiresAt));sessions.entrySet().removeIf(e->!now.isBefore(e.getValue().expiresAt)); }
    /** 코드 검증 오류를 만든다. Args: message 설명. Returns: 400 오류. Raises: 없음. Side Effects: 없음. */
    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_AUTH_CODE",message); }
    private static final class Challenge {
        final String email,digest;final Instant expiresAt,resendAt;int attempts;
        /** 챌린지 스냅샷을 만든다. Args: email 사용자,digest 해시,expiresAt/resendAt 시각. Returns: 없음. Raises: 없음. Side Effects: 없음. */
        Challenge(String email,String digest,Instant expiresAt,Instant resendAt) { this.email=email;this.digest=digest;this.expiresAt=expiresAt;this.resendAt=resendAt; }
    }
    /** 해시로만 인덱싱하는 세션. 자동 접근자는 불변 값을 반환한다. */
    private record Session(String email,Instant expiresAt) {}
}
