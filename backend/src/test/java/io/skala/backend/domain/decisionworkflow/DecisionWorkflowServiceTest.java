package io.skala.backend.domain.decisionworkflow;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static io.skala.backend.domain.decisionworkflow.dto.DecisionWorkflowDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.decisionagent.dto.DecisionResearchDtos.Hypothesis;
import io.skala.backend.domain.decisionagent.service.*;
import io.skala.backend.domain.decisionworkflow.service.*;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DB나 알림 없이 요청·회신·검토·결정·후속 비교 업무 전이를 실행한다. */
class DecisionWorkflowServiceTest {
    private final DecisionWorkflowStore store = mock(DecisionWorkflowStore.class);
    private final DecisionAnalysisStore analyses = mock(DecisionAnalysisStore.class);
    private final DecisionAgentService analysisService = mock(DecisionAgentService.class);
    private final DecisionDiscoveryService discovery = mock(DecisionDiscoveryService.class);
    private final DecisionBaselineLoader baselines = mock(DecisionBaselineLoader.class);
    private final DecisionScenarioCalculator calculator = new DecisionScenarioCalculator();
    private final DecisionWorkflowService service = new DecisionWorkflowService(store, new DecisionWorkflowPolicy(), analyses,
            analysisService, discovery, calculator, baselines, new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC));
    private final AuthenticatedUser planner = new AuthenticatedUser(10L, UserRole.PLANNER, null);
    private final AuthenticatedUser manager = new AuthenticatedUser(20L, UserRole.ORG_MANAGER, 1L);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-26T00:00:00Z");
    private WorkItem item;
    private Analysis analysis;

    /** 목적: 저장소를 메모리 상태로 대체한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 테스트 모의 상태 초기화. */
    @BeforeEach
    void setUp() {
        reset(store, analyses, analysisService, discovery, baselines);
        analysis = analysis(1, List.of(candidate("c1", EvidenceStatus.ASSUMPTION)), null);
        item = WorkItem.builder().id("7").analysisId("5").candidateId("c1").organizationId("1").organizationName("A조직")
                .ownerId("10").title("계약 개선").year(2026).baseMonth(11).masterLabel("master_ver11.01")
                .baselineOrganizationProfitWon("100").sourceAnalysisRevision(1).revision(1).state(State.DRAFT).round(1)
                .message("개선 가능액 확인").dueAt(now.plusDays(5)).createdAt(now).updatedAt(now)
                .sourceCandidate(analysis.candidates().getFirst()).events(List.of(new Event(1, "DRAFT_CREATED", "10", now, "초안", null))).build();
        when(store.get(any(), eq(7L), anyBoolean())).thenAnswer(invocation -> item);
        when(analyses.get(eq(10L), eq(5L), anyBoolean())).thenAnswer(invocation -> analysis);
        when(store.create(any())).thenAnswer(invocation -> {
            item = ((WorkItem) invocation.getArgument(0)).toBuilder().id("7").build();
            return item;
        });
        when(store.update(any(), anyInt(), any())).thenAnswer(invocation -> {
            item = invocation.getArgument(2);
            return item;
        });
        when(analysisService.candidates(eq(planner), eq(5L), any())).thenAnswer(invocation -> {
            CandidatesRequest request = invocation.getArgument(2);
            analysis = analysis(analysis.revision() + 1, request.candidates(), null);
            return analysis;
        });
    }

    /** 목적: 금액 없는 가설이 조직 요청 초안으로 보존되고 자동 금액을 만들지 않음을 확인한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 저장소 호출. */
    @Test
    void createsHypothesisRequestWithoutInventingCandidateAmount() {
        Hypothesis hypothesis = new Hypothesis("h1", "1", "계약 개선", "승인 소명 기반", List.of("e1"), null, null,
                Category.PROCUREMENT, List.of("변경 가능액은?"), true, "계약 조정 검토");
        when(discovery.findHypothesis(planner, 5L, "h1")).thenReturn(hypothesis);
        WorkItem created = service.create(planner, 5L, new io.skala.backend.domain.decisionworkflow.dto.DecisionWorkflowDtos.CreateRequest(
                1, null, "h1", "가능액 확인", now.plusDays(3)));
        assertThat(created.sourceCandidate()).isNull();
        assertThat(created.sourceHypothesis().get("amountWon").isNull()).isTrue();
        assertThat(created.candidateId()).isNotBlank().isNotEqualTo("h1");
        assertThat(created.organizationId()).isEqualTo("1");
        verifyNoInteractions(analysisService);
    }

    /** 목적: 발행·회신·수락·재계산·추진·후속 비교의 전체 흐름과 이력을 검증한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 상태 변경. */
    @Test
    void completesWorkflowWithReviewedCandidateAndNonCausalFollowupComparison() {
        service.issue(planner, 7L, new RevisionRequest(1));
        service.reply(manager, 7L, new ReplyRequest(2, Feasibility.FEASIBLE, candidate("c1", EvidenceStatus.REVIEWED), "계약 검토", "계약-1"));
        assertThat(item.reply().candidate().evidenceStatus()).isEqualTo(EvidenceStatus.ASSUMPTION);
        service.review(planner, 7L, new ReviewRequest(3, 1, ReviewVerdict.ACCEPT, "근거 확인"));
        assertThat(analysis.revision()).isEqualTo(2);
        assertThat(analysis.result()).isNull();
        assertThat(analysis.candidates().getFirst().evidenceStatus()).isEqualTo(EvidenceStatus.REVIEWED);
        assertThatThrownBy(() -> service.decide(planner, 7L, decision(4, 2, DecisionVerdict.APPROVE))).isInstanceOf(BusinessException.class);
        analysis = analysis(3, analysis.candidates(), calculator.calculate(analysis.snapshot(), "130", List.of(), analysis.candidates()));
        service.decide(planner, 7L, decision(4, 3, DecisionVerdict.APPROVE));
        assertThat(item.decision().candidateEffectWon()).isEqualTo("15");
        when(baselines.load(2L)).thenReturn(snapshot(12, "130"));
        service.progress(manager, 7L, new ProgressRequest(5, ProgressStatus.COMPLETED, "일정 실행 완료", "납품증빙", "2"));
        assertThat(item.state()).isEqualTo(State.COMPLETED);
        assertThat(item.progress().baselineProfitWon()).isEqualTo("100");
        assertThat(item.progress().followupProfitWon()).isEqualTo("130");
        assertThat(item.progress().profitChangeWon()).isEqualTo("30");
        assertThat(item.decision().candidateEffectWon()).isEqualTo("15");
        assertThat(item.events()).hasSize(6);
        assertThat(item.events().get(2).details().get("feasibility").asText()).isEqualTo("FEASIBLE");
    }

    /** 목적: 실현 불가 회신을 숫자로 수락할 수 없고 보완 이력이 유지됨을 검증한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 상태 변경. */
    @Test
    void infeasibleReplyCannotBeAcceptedAndChangeRequestPreservesReplyHistory() {
        service.issue(planner, 7L, new RevisionRequest(1));
        service.reply(manager, 7L, new ReplyRequest(2, Feasibility.NOT_FEASIBLE, null, "계약 변경 불가", "계약-2"));
        assertThatThrownBy(() -> service.review(planner, 7L, new ReviewRequest(3, 1, ReviewVerdict.ACCEPT, "확인")))
                .isInstanceOf(BusinessException.class);
        service.review(planner, 7L, new ReviewRequest(3, 1, ReviewVerdict.REQUEST_CHANGES, "대안 확인"));
        assertThat(item.state()).isEqualTo(State.CHANGES_REQUESTED);
        assertThat(item.round()).isEqualTo(2);
        service.reply(manager, 7L, new ReplyRequest(4, Feasibility.FEASIBLE, candidate("c1", EvidenceStatus.ASSUMPTION), "대안 계약", "계약-3"));
        assertThat(item.events().get(2).details().get("feasibility").asText()).isEqualTo("NOT_FEASIBLE");
        assertThat(item.reply().feasibility()).isEqualTo(Feasibility.FEASIBLE);
    }

    /** 목적: 수정된 후보와 오래된 revision을 수락·취소 과정에서 덮어쓰지 않는다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void staleAnalysisOrEditedCandidateBlocksMutation() {
        item = item.toBuilder().state(State.REPLIED).reply(new Reply(Feasibility.FEASIBLE, candidate("c1", EvidenceStatus.ASSUMPTION), "확인", "ref", "20", now)).build();
        analysis = analysis(2, List.of(new Candidate("c1", "1", "새 사용자 편집", EffectType.NET_PROFIT, "30", null, "0", "0", 12,
                Category.OPERATIONS, "", EvidenceStatus.ASSUMPTION, "", "")), null);
        assertThatThrownBy(() -> service.review(planner, 7L, new ReviewRequest(1, 1, ReviewVerdict.ACCEPT, "검토")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.review(planner, 7L, new ReviewRequest(1, 2, ReviewVerdict.ACCEPT, "검토")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.cancel(planner, 7L, new CancelRequest(1, 2, "취소"))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(analysisService);
    }

    /** 목적: 검토 거절·추진 보류·취소가 연결 효과를 제거하여 재계산을 요구한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 분석 변경. */
    @Test
    void rejectionDeclineAndCancellationRemoveOnlyMatchingLinkedCandidate() {
        item = item.toBuilder().state(State.REPLIED).build();
        service.review(planner, 7L, new ReviewRequest(1, 1, ReviewVerdict.REJECT, "조건 미충족"));
        assertThat(analysis.candidates()).isEmpty();
        assertThat(analysis.revision()).isEqualTo(2);
        setUp();
        service.cancel(planner, 7L, new CancelRequest(1, 1, "검토 취소"));
        assertThat(analysis.candidates()).isEmpty();
        setUp();
        item = item.toBuilder().state(State.ACCEPTED)
                .reply(new Reply(Feasibility.FEASIBLE, candidate("c1", EvidenceStatus.ASSUMPTION), "확인", "ref", "20", now))
                .review(new Review(ReviewVerdict.ACCEPT, "수락", "10", now)).build();
        Candidate accepted = new DecisionWorkflowPolicy().acceptedCandidate(item);
        analysis = analysis(2, List.of(accepted), calculator.calculate(snapshot(11, "100"), "130", List.of(), List.of(accepted)));
        service.decide(planner, 7L, decision(1, 2, DecisionVerdict.DECLINE));
        assertThat(analysis.candidates()).isEmpty();
        assertThat(analysis.result()).isNull();
    }

    /** 목적: 다른 조직 후보나 기획자의 조직 회신을 거절한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void rejectsPlannerReplyAndForeignOrganizationCandidate() {
        item = item.toBuilder().state(State.REQUESTED).build();
        assertThatThrownBy(() -> service.reply(planner, 7L, new ReplyRequest(1, Feasibility.NOT_FEASIBLE, null, "불가", "ref")))
                .isInstanceOf(BusinessException.class);
        Candidate alien = new Candidate("c1", "2", "타조직", EffectType.NET_PROFIT, "20", null, "0", "0", 12,
                Category.OPERATIONS, "", EvidenceStatus.ASSUMPTION, "", "");
        assertThatThrownBy(() -> service.reply(manager, 7L, new ReplyRequest(1, Feasibility.FEASIBLE, alien, "확인", "ref")))
                .isInstanceOf(BusinessException.class);
        verify(store, never()).update(any(), anyInt(), any());
    }

    /** 목적: 완료에 후속 확정본이 필요하고 과거 기준은 비교하지 않음을 확인한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 DB 조회. */
    @Test
    void requiresFollowupMasterForCompletionAndRejectsPastPeriod() {
        item = item.toBuilder().state(State.APPROVED).build();
        assertThatThrownBy(() -> service.progress(manager, 7L, new ProgressRequest(1, ProgressStatus.COMPLETED, "완료", "ref", null)))
                .isInstanceOf(BusinessException.class);
        when(baselines.load(2L)).thenReturn(snapshot(11, "120"));
        assertThatThrownBy(() -> service.progress(manager, 7L, new ProgressRequest(1, ProgressStatus.COMPLETED, "완료", "ref", "2")))
                .isInstanceOf(BusinessException.class);
        verify(store, never()).update(any(), anyInt(), any());
    }

    /** 목적: 발행 전 취소한 초안이 조직 조회 조건에 필요한 발행 이력을 만들지 않음을 검증한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 모의 취소 저장. */
    @Test
    void cancellingUnissuedDraftDoesNotExposeItToOrganizationInbox() {
        service.cancel(planner, 7L, new CancelRequest(1, 1, "초안 취소"));
        assertThat(item.state()).isEqualTo(State.CANCELLED);
        assertThat(item.events()).noneMatch(event -> event.action().equals("ISSUED"));
    }

    /** 목적: 공통 결정 입력을 만든다. Args: itemRevision 요청 버전, analysisRevision 분석 버전, verdict 결정. Returns: 결정 입력. Raises: 없음. Side Effects: 없음. */
    private DecisionRequest decision(int itemRevision, int analysisRevision, DecisionVerdict verdict) {
        return new DecisionRequest(itemRevision, analysisRevision, verdict, "경영회의", "회의록-1", "결정 기록", now.plusDays(10));
    }

    /** 목적: 순효과 15원의 검증 후보를 만든다. Args: id 후보, status 근거 수준. Returns: 후보. Raises: 없음. Side Effects: 없음. */
    private Candidate candidate(String id, EvidenceStatus status) {
        return new Candidate(id, "1", "계약 개선", EffectType.NET_PROFIT, "20", null, "2", "3", 12, Category.PROCUREMENT, "", status, "근거", "ref");
    }

    /** 목적: 테스트 분석 상태를 만든다. Args: revision 버전, candidates 후보, result 계산. Returns: 분석. Raises: 없음. Side Effects: 없음. */
    private Analysis analysis(int revision, List<Candidate> candidates, Result result) {
        return new Analysis("5", "목표 검토", "비용 보호", revision, result == null ? "DRAFT" : "COMPLETED", now, now, "130", List.of(),
                snapshot(11, "100"), candidates, result, null);
    }

    /** 목적: 조직 한 곳의 고정 전망을 만든다. Args: month 월, profit 이익. Returns: 기준. Raises: 없음. Side Effects: 없음. */
    private Snapshot snapshot(int month, String profit) {
        return new Snapshot(month == 11 ? "1" : "2", "master_ver" + month + ".01", 2026, month, now, List.of("11"),
                List.of(new Organization("1", "A", "A조직", "1000", "900", profit)), profit);
    }
}
