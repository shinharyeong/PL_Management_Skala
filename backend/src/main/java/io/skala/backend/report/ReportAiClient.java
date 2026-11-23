package io.skala.backend.report;

import static io.skala.backend.report.ReportAuthoringJson.*;
import static io.skala.backend.submission.ApiData.object;
import com.fasterxml.jackson.databind.JsonNode;
import io.skala.backend.security.Actor;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** 인증된 백엔드가 고정된 내부 AI 주소에만 초안 생성을 요청한다. */
@Service
public class ReportAiClient {
    private final String url;
    private final String token;
    private final HttpClient client;
    /** 내부 AI 연결 설정만 읽는다. Args: environment 환경. Returns: 없음. Raises: 없음. Side Effects: 네트워크 호출 없음. */
    public ReportAiClient(Environment environment) {
        url=environment.getProperty("REPORT_AI_URL","").replaceAll("/+$",""); token=environment.getProperty("REPORT_AI_TOKEN","");
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    /** 내부 연동 설정 여부를 표시한다. Args: 없음. Returns: URL·인증 설정 존재. Raises: 없음. Side Effects: 없음. */
    public boolean configured() { return !url.isBlank() && !token.isBlank(); }
    /** AI 초안을 요청하며 실패하면 수동 경로를 유지한다.
     * Args: actor 인증 기획자, operation sql/edit/pdf, payload 서버가 제한한 입력.
     * Returns: 명시 configured 상태와 확인할 초안. Raises: 권한·입력 오류.
     * Side Effects: 설정한 내부 서비스로 요청하며 파일·DB·캐시·응답 로그를 생성하지 않는다.
     */
    public Map<String,Object> propose(Actor actor,String operation,JsonNode payload) {
        actor.requireRole("PLANNER"); if (!Set.of("sql","edit","pdf").contains(operation)) throw invalid("지원하지 않는 AI 작업입니다.");
        if (!configured()) return unavailable("AI 연결이 설정되지 않았습니다. 직접 구성하고 미리보기로 확인할 수 있습니다.");
        try {
            URI address=URI.create(url+"/v1/report-authoring/"+operation); if (!Set.of("http","https").contains(address.getScheme())) return unavailable("AI 연결 주소 설정을 확인해 주세요.");
            var request=HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(90)).header("Content-Type","application/json").header("X-Report-Service-Token",token).POST(HttpRequest.BodyPublishers.ofString(write(payload),StandardCharsets.UTF_8)).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes; try (var body=response.body()) { bytes=body.readNBytes(512*1024+1); }
            if (response.statusCode()!=200 || bytes.length>512*1024) return unavailable("AI 초안을 가져오지 못했습니다. 입력 내용을 직접 수정할 수 있습니다.");
            JsonNode result=JSON.readTree(bytes); if (!result.isObject()) return unavailable("AI 응답 형식이 올바르지 않습니다. 직접 구성해 주세요.");
            return map(result);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); return unavailable("AI 요청이 중단되었습니다. 직접 작성할 수 있습니다."); }
        catch (Exception error) { return unavailable("AI 서비스에 연결할 수 없습니다. 직접 작성할 수 있습니다."); }
    }
    /** 설정·연결 실패를 값으로 전달한다. Args: message 안내. Returns: 수동 경로 응답. Raises: 없음. Side Effects: 없음. */
    private Map<String,Object> unavailable(String message) { return object("configured",false,"blocks",List.of(),"pages",List.of(),"warnings",List.of(message)); }
}
