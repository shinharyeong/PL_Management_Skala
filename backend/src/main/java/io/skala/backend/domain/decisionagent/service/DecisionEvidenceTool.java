package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionResearchDtos.*;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 실행 중인 분석 하나의 고정 근거만 읽는 단기 capability. DB·모델에 토큰을 저장하지 않는다. */
@Component
@RequiredArgsConstructor
public class DecisionEvidenceTool {
    private final Clock clock;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    /** 목적: 내부 AI 호출 동안 허용할 근거를 고정한다. Args: analysisId 분석, revision 입력 버전, evidence 복사 근거. Returns: 3분 토큰. Raises: IllegalStateException 동시 실행 상한. Side Effects: 프로세스 메모리 저장. */
    public String grant(String analysisId, int revision, List<Evidence> evidence) {
        grants.entrySet().removeIf(entry -> !entry.getValue().expires().isAfter(clock.instant()));
        if (grants.size() >= 200) throw new IllegalStateException("동시 근거 조회 실행 한도를 초과했습니다.");
        String token = UUID.randomUUID() + ":" + UUID.randomUUID();
        grants.put(token, new Grant(analysisId, revision, List.copyOf(evidence), clock.instant().plusSeconds(180)));
        return token;
    }

    /** 목적: 분석·revision·허용 참조가 일치할 때만 근거를 반환한다. Args: token capability, request 읽기 범위. Returns: 요청된 근거. Raises: FORBIDDEN 토큰/범위 오류. Side Effects: 없음. */
    public ReadEvidenceResponse read(String token, ReadEvidenceRequest request) {
        Grant grant = token == null ? null : grants.get(token);
        if (grant == null || !grant.expires().isAfter(clock.instant()) || !grant.analysisId().equals(request.analysisId())
                || grant.revision() != request.revision() || request.refIds() == null || request.refIds().isEmpty()
                || request.refIds().size() > 20 || new HashSet<>(request.refIds()).size() != request.refIds().size()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "이 분석의 근거 조회 권한이 없거나 만료되었습니다.");
        }
        List<Evidence> selected = grant.evidence().stream().filter(item -> request.refIds().contains(item.refId())).toList();
        if (selected.size() != request.refIds().size()) throw new BusinessException(ErrorCode.FORBIDDEN, "허용된 근거만 조회할 수 있습니다.");
        return new ReadEvidenceResponse(grant.analysisId(), grant.revision(), selected);
    }

    /** 목적: AI 실행이 끝난 토큰을 폐기한다. Args: token capability. Returns: 없음. Raises: 없음. Side Effects: 메모리 권한 삭제. */
    public void revoke(String token) { if (token != null) grants.remove(token); }

    private record Grant(String analysisId, int revision, List<Evidence> evidence, Instant expires) { }
}
