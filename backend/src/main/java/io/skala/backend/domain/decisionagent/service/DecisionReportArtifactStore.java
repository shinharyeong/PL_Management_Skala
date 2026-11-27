package io.skala.backend.domain.decisionagent.service;

import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 사용자별 임시 보고서를 메모리에만 보관한다. 재시작·만료 시 다시 생성해야 한다. */
@Component
@RequiredArgsConstructor
public class DecisionReportArtifactStore {
    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 20 * 1024 * 1024;
    private final AppUserRepository users;
    private final Clock clock;
    private final Map<String, Entry> entries = new HashMap<>();
    private record Entry(long ownerId, String filename, byte[] bytes, Instant expiresAt) { }
    public record Receipt(String artifactId, String filename, String contentType, int byteSize, Instant expiresAt) { }
    public record Download(String filename, byte[] bytes) { }

    /** 목적: 현재 활성 기획자 권한을 DB에서 재확인한다. Args: actor 인증 사용자.
     * Returns: 없음. Raises: BusinessException 권한 철회/탈퇴. Side Effects: DB 읽기만. */
    public void assertCurrent(AuthenticatedUser actor) {
        actor.requireRole(UserRole.PLANNER);
        var user = users.findById(actor.id()).orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        if (!Boolean.TRUE.equals(user.getActive()) || user.getRole() != UserRole.PLANNER)
            throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    /** 목적: 크기·사용자 한도를 검사한 XLSX를 짧게 보관한다. Args: actor 소유자, filename 표시 이름, bytes XLSX.
     * Returns: 원문 없는 다운로드 영수증. Raises: 권한/용량 오류.
     * Side Effects: DB 권한 읽기와 10분 TTL 메모리 사본 생성. 디스크·외부 저장 없음. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public synchronized Receipt put(AuthenticatedUser actor, String filename, byte[] bytes) {
        assertCurrent(actor);
        expire();
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES)
            throw new DecisionAssistantTools.ToolFailure("보고서 파일이 비어 있거나 임시 다운로드 크기 한도를 초과했습니다. 블록을 좁혀 주세요.");
        int used = entries.values().stream().mapToInt(value -> value.bytes().length).sum();
        long owned = entries.values().stream().filter(value -> value.ownerId()==actor.id()).count();
        if (owned >= 5 || used + bytes.length > MAX_TOTAL_BYTES || entries.size() >= 100)
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"REPORT_ARTIFACT_LIMIT","임시 보고서가 많습니다. 만료 후 다시 요청해 주세요.");
        String safeName = filename.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");
        if (safeName.length() > 180) safeName = safeName.substring(0,175)+".xlsx";
        Instant expiry = clock.instant().plusSeconds(600);
        String id = UUID.randomUUID().toString();
        entries.put(id,new Entry(actor.id(),safeName,bytes.clone(),expiry));
        return new Receipt(id,safeName,CONTENT_TYPE,bytes.length,expiry);
    }

    /** 목적: 현재 권한·소유권·만료를 확인해 파일 사본을 반환한다. Args: actor 사용자, artifactId 영수증 ID.
     * Returns: XLSX 바이트 사본. Raises: 권한 또는 만료/미존재 404.
     * Side Effects: 권한 DB 읽기·만료 메모리 정리만. 파일 저장·서버 경로 접근 없음. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public synchronized Download get(AuthenticatedUser actor, String artifactId) {
        assertCurrent(actor);
        expire();
        Entry entry = entries.get(artifactId);
        if (entry == null || entry.ownerId()!=actor.id())
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"보고서가 없거나 만료되었습니다. 다시 생성해 주세요.");
        return new Download(entry.filename(),entry.bytes().clone());
    }

    /** 목적: 사용 종료된 바이트를 메모리에서 제거한다. Args: 없음. Returns: 없음.
     * Raises: 없음. Side Effects: 현재 저장소의 만료 항목 삭제만. */
    private void expire() { entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(clock.instant())); }
}
