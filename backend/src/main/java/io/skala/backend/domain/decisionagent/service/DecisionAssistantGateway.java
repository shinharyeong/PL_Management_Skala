package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static io.skala.backend.domain.decisionagent.dto.DecisionAssistantDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 실제 호출과 관찰을 서버 메모리에 기록하고 동일 실행의 읽기 권한을 제한한다. */
@Component
@RequiredArgsConstructor
public class DecisionAssistantGateway {
    static final int MAX_OBSERVATION_BYTES = 256 * 1024;
    static final int MAX_LEDGER_BYTES = 192 * 1024;
    private final DecisionAssistantTools tools;
    private final DecisionAssistantJson json;
    private final Clock clock;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    /** 목적: 사용자와 고정 기준에 한정한 실행을 발급한다. Args: actor 사용자, snapshot 기준, analysis 선택 분석. Returns: 180초 capability. Raises: 429 동시 실행 한도. Side Effects: 메모리 권한 생성. */
    public synchronized String grant(AuthenticatedUser actor, Snapshot snapshot, Analysis analysis) {
        return grant(actor, snapshot, analysis, null);
    }

    /** 목적: 현재 질문까지 고정하여 최종 요구 항목의 원문 인용을 검증한다. Args: actor 사용자, snapshot 기준, analysis 선택 분석, message 현재 질문. Returns: 실행 capability. Raises: 429 동시 실행 한도. Side Effects: 메모리에만 권한·질문 보관. */
    public synchronized String grant(AuthenticatedUser actor, Snapshot snapshot, Analysis analysis, String message) {
        runs.entrySet().removeIf(entry -> !entry.getValue().expires.isAfter(clock.instant()));
        if (runs.size() >= 200) throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "ASSISTANT_BUSY", "대화 실행이 많습니다. 잠시 후 다시 시도해 주세요.");
        String token = UUID.randomUUID() + ":" + UUID.randomUUID();
        runs.put(token, new Run(actor, snapshot, analysis, clock.instant().plusSeconds(180), message));
        return token;
    }

    /** 목적: 실제 서비스 호출을 제한·재사용하고 관찰 원장을 남긴다. Args: token capability, request 허용 도구와 인자. Returns: 검증된 관찰. Raises: 403 만료, 429 실행 횟수. Side Effects: 업무 DB 읽기와 메모리 원장 갱신만. */
    public Observation call(String token, ToolRequest request) {
        Run run = require(token);
        synchronized (run) {
            tools.assertCurrent(run);
            JsonNode args;
            String failure = null;
            try { args = tools.normalize(request.tool(), request.arguments()); }
            catch (IllegalArgumentException exception) { args = json.canonical(request.arguments()); failure = "허용되지 않은 도구 또는 인자입니다. 도구 스키마를 확인해 주세요."; }
            String key = request.tool() + ":" + args;
            Observation cached = run.cache.get(key);
            if (cached != null) return cached;
            if (run.observations.size() >= 8) throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "ASSISTANT_TOOL_LIMIT", "이번 대화의 조회 횟수 한도에 도달했습니다.");
            String tool = request.tool().matches("[a-z_]{1,60}") ? request.tool() : "unsupported_tool";
            String label = tools.label(tool);
            JsonNode data = null;
            List<String> warnings = List.of();
            String comparison = "고정 마스터 원천을 기준으로 조회";
            if (failure == null) {
                try {
                    var value = tools.execute(run, tool, args);
                    data = json.safe(value.data()); warnings = value.warnings(); comparison = value.comparisonBasis();
                } catch (DecisionAssistantTools.ToolFailure exception) { failure = exception.getMessage(); }
                catch (RuntimeException exception) { failure = "해당 자료를 안전하게 조회하지 못했습니다. 기준과 조회 조건을 확인해 주세요."; }
            }
            Basis basis = new Basis(run.context.masterId(), run.context.year(), run.context.baseMonth(),
                    run.context.analysisId(), run.context.revision(), OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), comparison);
            Observation result = new Observation("obs-" + (run.observations.size() + 1), tool, label,
                    failure == null ? "SUCCESS" : "ERROR", basis, failure == null ? data : null,
                    failure == null ? warnings : List.of(failure));
            int size = json.size(result);
            if (size > MAX_OBSERVATION_BYTES || run.bytes + size > MAX_LEDGER_BYTES - 8192) {
                result = new Observation(result.id(), tool, label, "ERROR", basis, null,
                        List.of("조회 자료가 대화 응답 크기 한도를 초과했습니다. 조직이나 후보 범위를 좁혀 다시 질문해 주세요."));
                size = json.size(result);
            }
            if (tool.equals("submission_versions") && result.status().equals("SUCCESS")) {
                Set<String> allowed = new HashSet<>(); result.data().path("history").forEach(item -> allowed.add(item.path("submissionId").asText()));
                run.allowedVersions.put(args.get("organizationId").textValue(), Set.copyOf(allowed));
            }
            run.observations.add(result); run.cache.put(key, result); run.bytes += size;
            return result;
        }
    }

    /** 목적: 실제 실행 원장과 기준을 모델 검증에 제공한다. Args: token capability. Returns: 해당 실행. Raises: 403 만료/미등록. Side Effects: 없음. */
    Run require(String token) {
        Run run = token == null ? null : runs.get(token);
        if (run == null || !run.expires.isAfter(clock.instant())) throw new BusinessException(ErrorCode.FORBIDDEN, "대화 도구 권한이 없거나 만료되었습니다.");
        return run;
    }

    /** 목적: 완료한 capability와 캐시를 폐기한다. Args: token capability. Returns: 없음. Raises: 없음. Side Effects: 메모리 삭제. */
    public void revoke(String token) { if (token != null) runs.remove(token); }

    /** 한 사용자·한 기준의 메모리 상태. DB나 모델 그래프에 저장하지 않는다. */
    static final class Run {
        final AuthenticatedUser actor;
        final Snapshot snapshot;
        final Analysis analysis;
        final Context context;
        final Instant expires;
        final String message;
        final Map<String, Observation> cache = new LinkedHashMap<>();
        final List<Observation> observations = new ArrayList<>();
        final Map<String, Set<String>> allowedVersions = new HashMap<>();
        int bytes;
        /** 목적: 실행에 사용할 권한과 입력 사본을 고정한다. Args: actor 사용자, snapshot 기준, analysis 선택 분석, expires 만료, message 현재 질문. Returns: 실행. Raises: 없음. Side Effects: 메모리 초기화. */
        Run(AuthenticatedUser actor, Snapshot snapshot, Analysis analysis, Instant expires, String message) {
            this.actor = actor; this.snapshot = snapshot; this.analysis = analysis; this.expires = expires;
            this.message = message;
            this.context = new Context(snapshot.masterId(), snapshot.masterLabel(), snapshot.year(), snapshot.baseMonth(),
                    analysis == null ? null : analysis.id(), analysis == null ? null : analysis.revision(), snapshot.asOf());
        }
        /** 목적: 실제 관찰에 대응하는 공개 호출 이력을 만든다. Args: 없음. Returns: 순서가 고정된 호출 사본. Raises: 없음. Side Effects: 없음. */
        List<ToolCall> calls() {
            List<ToolCall> result = new ArrayList<>();
            for (int index = 0; index < observations.size(); index++) {
                Observation value = observations.get(index);
                result.add(new ToolCall("call-" + (index + 1), value.tool(), value.label(), value.status(), value.id()));
            }
            return List.copyOf(result);
        }
    }
}
