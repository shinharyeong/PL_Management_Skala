package io.skala.backend.domain.decisionagent.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.skala.backend.domain.decisionagent.controller.DecisionReportArtifactController;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 실제 파일 없이 메모리 보고서의 소유권·만료·용량·응답 경계를 검사한다. */
class DecisionReportArtifactTest {
    private final AppUserRepository users=mock(AppUserRepository.class);
    private final AppUser user=mock(AppUser.class);
    private final Clock clock=mock(Clock.class);
    private final Instant now=Instant.parse("2026-11-27T00:00:00Z");
    private final AuthenticatedUser actor=new AuthenticatedUser(7L,UserRole.PLANNER,null);
    private DecisionReportArtifactStore store;

    /** 목적: 활성 기획자 대역과 시간을 고정한다. Args: 없음. Returns: 없음.
     * Raises: 없음. Side Effects: 메모리 대역만. */
    @BeforeEach void prepare() {
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(users.findById(8L)).thenReturn(Optional.of(user));
        when(user.getActive()).thenReturn(true); when(user.getRole()).thenReturn(UserRole.PLANNER);
        when(clock.instant()).thenReturn(now); store=new DecisionReportArtifactStore(users,clock);
    }

    /** 목적: 입력·다운로드 배열 변형이 저장된 파일을 바꾸지 않는지 확인한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 메모리 바이트만. */
    @Test void preservesPrivateBytesAndRejectsAnotherOwner() {
        byte[] original={1,2,3}; var receipt=store.put(actor,"보고서.xlsx",original); original[0]=9;
        byte[] first=store.get(actor,receipt.artifactId()).bytes(); assertThat(first).containsExactly(1,2,3); first[1]=9;
        assertThat(store.get(actor,receipt.artifactId()).bytes()).containsExactly(1,2,3);
        assertThatThrownBy(() -> store.get(new AuthenticatedUser(8L,UserRole.PLANNER,null),receipt.artifactId()))
                .isInstanceOf(BusinessException.class);
        assertThat(receipt.expiresAt()).isEqualTo(now.plusSeconds(600));
    }

    /** 목적: 만료 시각부터 다운로드를 거부한다. Args: 없음. Returns: 없음.
     * Raises: AssertionError. Side Effects: 메모리 만료 정리만. */
    @Test void expiresAtTheDeclaredDeadline() {
        var receipt=store.put(actor,"보고서.xlsx",new byte[]{1}); when(clock.instant()).thenReturn(now.plusSeconds(600));
        assertThatThrownBy(() -> store.get(actor,receipt.artifactId())).isInstanceOf(BusinessException.class);
    }

    /** 목적: 발급 후 탈퇴·역할 철회를 다운로드 전에 재검사한다. Args: 없음. Returns: 없음.
     * Raises: AssertionError. Side Effects: 대역 권한 변경만. */
    @Test void checksRevokedRoleAndInactiveUserAgain() {
        var receipt=store.put(actor,"보고서.xlsx",new byte[]{1}); when(user.getActive()).thenReturn(false);
        assertThatThrownBy(() -> store.get(actor,receipt.artifactId())).isInstanceOf(BusinessException.class);
        when(user.getActive()).thenReturn(true); when(user.getRole()).thenReturn(UserRole.ORG_MANAGER);
        assertThatThrownBy(() -> store.get(actor,receipt.artifactId())).isInstanceOf(BusinessException.class);
    }

    /** 목적: 빈 파일·파일당 크기·사용자별 개수 한도를 확인한다. Args: 없음. Returns: 없음.
     * Raises: AssertionError. Side Effects: 제한된 테스트 메모리만. */
    @Test void boundsMemoryAndPerUserArtifacts() {
        assertThatThrownBy(() -> store.put(actor,"x.xlsx",new byte[0])).isInstanceOf(DecisionAssistantTools.ToolFailure.class);
        assertThatThrownBy(() -> store.put(actor,"x.xlsx",new byte[5*1024*1024+1])).isInstanceOf(DecisionAssistantTools.ToolFailure.class);
        for (int i=0;i<5;i++) store.put(actor,"x.xlsx",new byte[]{1});
        assertThatThrownBy(() -> store.put(actor,"x.xlsx",new byte[]{1})).isInstanceOf(BusinessException.class);
    }

    /** 목적: 인증 다운로드가 캐시되지 않고 경로·헤더 문자를 제거하는지 확인한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 메모리 HTTP 응답만. */
    @Test void sendsNoStoreDownloadWithoutFilenamePathOrHeaderInjection() {
        var receipt=store.put(actor,"../보고서\r\n.xlsx",new byte[]{1,2});
        CurrentUser current=mock(CurrentUser.class); when(current.get()).thenReturn(actor);
        var response=new DecisionReportArtifactController(store,current).download(receipt.artifactId());
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo(DecisionReportArtifactStore.CONTENT_TYPE);
        assertThat(receipt.filename()).doesNotContain("/","\r","\n");
        assertThat(response.getBody()).containsExactly(1,2);
    }
}
