package io.skala.backend.domain.decisionworkflow.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static io.skala.backend.domain.decisionworkflow.dto.DecisionWorkflowDtos.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.decisionagent.service.DecisionAgentService;
import io.skala.backend.domain.decisionagent.service.DecisionAnalysisStore;
import io.skala.backend.domain.decisionagent.service.DecisionBaselineLoader;
import io.skala.backend.domain.decisionagent.service.DecisionDiscoveryService;
import io.skala.backend.domain.decisionagent.service.DecisionScenarioCalculator;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 개선 가설 확인부터 검토·외부 결정 기록·후속 비교까지 인앱 업무 흐름을 처리한다. */
@Service
@RequiredArgsConstructor
public class DecisionWorkflowService {
    private final DecisionWorkflowStore store;
    private final DecisionWorkflowPolicy policy;
    private final DecisionAnalysisStore analyses;
    private final DecisionAgentService analysisService;
    private final DecisionDiscoveryService discovery;
    private final DecisionScenarioCalculator calculator;
    private final DecisionBaselineLoader baselines;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** 목적: 기획자 소유 또는 자기 조직의 발행된 요청만 조회한다. Args: actor 사용자, analysisId 선택 필터. Returns: 요청 목록. Raises: 권한/DB 오류. Side Effects: DB 읽기. */
    @Transactional(readOnly = true)
    public Items<WorkItem> list(AuthenticatedUser actor, Long analysisId) {
        return new Items<>(store.list(actor, analysisId));
    }

    /** 목적: 권한 범위의 요청 상세를 조회한다. Args: actor 사용자, id 요청. Returns: 요청. Raises: 404 미소유. Side Effects: DB 읽기. */
    @Transactional(readOnly = true)
    public WorkItem get(AuthenticatedUser actor, long id) {
        return store.get(actor, id, false);
    }

    /** 목적: 해당 조직의 후속 확정 기준 선택지를 제공한다. Args: actor 사용자, id 요청. Returns: 같은 연도 후속 master 목록. Raises: 404 미소유. Side Effects: DB 읽기. */
    @Transactional(readOnly = true)
    public Items<Baseline> followupBaselines(AuthenticatedUser actor, long id) {
        return new Items<>(store.followupBaselines(store.get(actor, id, false)));
    }

    /** 목적: 후보 또는 금액 미정 가설의 조직 확인 초안을 만든다. Args: actor 소유 기획자, analysisId 분석, request 요청 내용. Returns: 초안. Raises: 권한/409/입력/근거 오류. Side Effects: 요청 DB 생성, 외부 전송 없음. */
    @Transactional
    public WorkItem create(AuthenticatedUser actor, long analysisId,
                           io.skala.backend.domain.decisionworkflow.dto.DecisionWorkflowDtos.CreateRequest request) {
        actor.requireRole(UserRole.PLANNER);
        Analysis analysis = analyses.get(actor.id(), analysisId, true);
        policy.revision(analysis.revision(), request.expectedAnalysisRevision());
        boolean fromCandidate = request.candidateId() != null && !request.candidateId().isBlank();
        boolean fromHypothesis = request.hypothesisId() != null && !request.hypothesisId().isBlank();
        if (fromCandidate == fromHypothesis) throw new BusinessException(ErrorCode.INVALID_REQUEST, "candidateId 또는 hypothesisId 하나만 지정해 주세요.");
        OffsetDateTime now = OffsetDateTime.now(clock);
        policy.futureDeadline(request.dueAt(), now);
        WorkItem.WorkItemBuilder draft = WorkItem.builder().analysisId(analysis.id()).ownerId(String.valueOf(actor.id()))
                .sourceAnalysisRevision(analysis.revision()).year(analysis.snapshot().year()).baseMonth(analysis.snapshot().baseMonth())
                .masterLabel(analysis.snapshot().masterLabel()).revision(1).state(State.DRAFT).round(1)
                .message(policy.text(request.message(), 4000, "확인 요청 내용")).dueAt(request.dueAt()).createdAt(now).updatedAt(now);
        String organizationId;
        if (fromCandidate) {
            Candidate candidate = analysis.candidates().stream().filter(value -> value.id().equals(request.candidateId())).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "분석의 후보를 찾을 수 없습니다."));
            organizationId = candidate.organizationId();
            draft.candidateId(candidate.id()).title(candidate.title()).sourceCandidate(candidate);
        } else {
            var hypothesis = discovery.findHypothesis(actor, analysisId, request.hypothesisId());
            organizationId = hypothesis.organizationId();
            draft.candidateId(UUID.randomUUID().toString()).hypothesisId(hypothesis.id()).title(hypothesis.title())
                    .sourceHypothesis(mapper.valueToTree(hypothesis));
        }
        Organization organization = analysis.snapshot().organizations().stream().filter(value -> value.id().equals(organizationId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "고정 기준에 포함된 조직만 확인 요청할 수 있습니다."));
        draft.organizationId(organization.id()).organizationName(organization.name()).baselineOrganizationProfitWon(organization.profitWon())
                .events(List.of(new Event(1, "DRAFT_CREATED", String.valueOf(actor.id()), now, "조직 확인 요청 초안 작성", null)));
        return store.create(draft.build());
    }

    /** 목적: 기획자가 확인한 초안을 조직 인앱 요청함에 발행한다. Args: actor 소유 기획자, id 요청, request 예상 revision. Returns: 발행 요청. Raises: 권한/409/기한 오류. Side Effects: DB 상태 갱신, 메일/외부 전송 없음. */
    @Transactional
    public WorkItem issue(AuthenticatedUser actor, long id, RevisionRequest request) {
        Locked locked = lockPlanner(actor, id, request.expectedRevision(), null);
        WorkItem before = locked.item();
        policy.state(before, State.DRAFT);
        policy.unchangedSource(before, locked.analysis());
        policy.futureDeadline(before.dueAt(), OffsetDateTime.now(clock));
        return save(actor, before, before.toBuilder().state(State.REQUESTED).build(), "ISSUED", before.message(), null);
    }

    /** 목적: 자기 조직이 가능액·시점·근거 또는 실현 불가 사유를 회신한다. Args: actor 조직 담당자, id 요청, request 회신. Returns: 회신 완료 요청. Raises: 권한/409/후보 오류. Side Effects: 회신·이력 DB 저장. */
    @Transactional
    public WorkItem reply(AuthenticatedUser actor, long id, ReplyRequest request) {
        actor.requireRole(UserRole.ORG_MANAGER);
        WorkItem before = store.get(actor, id, true);
        policy.revision(before.revision(), request.expectedRevision());
        policy.state(before, State.REQUESTED, State.CHANGES_REQUESTED);
        String note = policy.text(request.note(), 4000, "회신 사유");
        String evidenceRef = policy.text(request.evidenceRef(), 2000, "회신 근거 참조");
        if (request.feasibility() == null) throw new BusinessException(ErrorCode.INVALID_REQUEST, "실현 가능 여부를 선택해 주세요.");
        Candidate candidate = null;
        if (request.feasibility() == Feasibility.FEASIBLE) {
            Candidate input = request.candidate();
            if (input == null || !before.candidateId().equals(input.id()) || !before.organizationId().equals(input.organizationId())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "회신 후보 ID와 조직은 확인 요청과 같아야 합니다.");
            }
            candidate = new Candidate(input.id(), input.organizationId(), input.title(), input.type(), input.amountWon(), input.contributionMarginPercent(),
                    input.implementationCostWon(), input.embeddedEffectWon(), input.recognitionMonth(), input.category(), input.duplicateGroup(),
                    EvidenceStatus.ASSUMPTION, note, evidenceRef);
            calculator.validate(localSnapshot(before), List.of(candidate));
        } else if (request.candidate() != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "실현 불가 회신에는 금액 후보 대신 사유와 근거를 남겨 주세요.");
        }
        Reply reply = new Reply(request.feasibility(), candidate, note, evidenceRef, String.valueOf(actor.id()), OffsetDateTime.now(clock));
        return save(actor, before, before.toBuilder().state(State.REPLIED).reply(reply).review(null).build(), "REPLIED", note, reply);
    }

    /** 목적: 회신을 검토하고 수락된 후보만 분석에 동기화한다. Args: actor 소유 기획자, id 요청, request 판정과 두 revision. Returns: 검토 요청. Raises: 권한/409/실현 불가/후보 오류. Side Effects: 요청·분석 DB 갱신, 기존 계산 무효화. */
    @Transactional
    public WorkItem review(AuthenticatedUser actor, long id, ReviewRequest request) {
        Locked locked = lockPlanner(actor, id, request.expectedRevision(), request.expectedAnalysisRevision());
        WorkItem before = locked.item();
        policy.state(before, State.REPLIED);
        String note = policy.text(request.note(), 4000, "검토 사유");
        if (request.verdict() == null) throw new BusinessException(ErrorCode.INVALID_REQUEST, "검토 판정을 선택해 주세요.");
        State next;
        int round = before.round();
        if (request.verdict() == ReviewVerdict.ACCEPT) {
            policy.unchangedSource(before, locked.analysis());
            Candidate accepted = policy.acceptedCandidate(before);
            List<Candidate> candidates = new ArrayList<>(locked.analysis().candidates());
            candidates.removeIf(candidate -> candidate.id().equals(before.candidateId()));
            candidates.add(accepted);
            analysisService.candidates(actor, Long.parseLong(before.analysisId()), new CandidatesRequest(request.expectedAnalysisRevision(), List.copyOf(candidates)));
            next = State.ACCEPTED;
        } else if (request.verdict() == ReviewVerdict.REQUEST_CHANGES) {
            next = State.CHANGES_REQUESTED;
            round++;
        } else {
            removeLinkedCandidate(actor, before, locked.analysis());
            next = State.REJECTED;
        }
        Review review = new Review(request.verdict(), note, String.valueOf(actor.id()), OffsetDateTime.now(clock));
        return save(actor, before, before.toBuilder().state(next).round(round).review(review).build(), "REVIEW_" + request.verdict().name(), note, review);
    }

    /** 목적: 검토된 과제의 외부 경영 결정을 증빙과 함께 기록한다. Args: actor 기록 기획자, id 요청, request 결정 내용. Returns: 추진/보류 결정 요청. Raises: 권한/409/제약/증빙 오류. Side Effects: 결정 DB 기록, 실제 임원 인증/결재 실행 없음. */
    @Transactional
    public WorkItem decide(AuthenticatedUser actor, long id, DecisionRequest request) {
        Locked locked = lockPlanner(actor, id, request.expectedRevision(), request.expectedAnalysisRevision());
        WorkItem before = locked.item();
        policy.state(before, State.ACCEPTED);
        if (request.verdict() == null) throw new BusinessException(ErrorCode.INVALID_REQUEST, "결정 내용을 선택해 주세요.");
        policy.futureDeadline(request.dueAt(), OffsetDateTime.now(clock));
        if (request.verdict() == DecisionVerdict.APPROVE) policy.approvable(before, locked.analysis());
        else removeLinkedCandidate(actor, before, locked.analysis());
        String effect = request.verdict() == DecisionVerdict.APPROVE
                ? locked.analysis().result().effects().stream().filter(value -> value.candidateId().equals(before.candidateId()))
                        .findFirst().orElseThrow().netEffectWon() : null;
        Decision decision = new Decision(request.verdict(), policy.text(request.decisionMaker(), 200, "외부 결정자"),
                policy.text(request.decisionEvidenceRef(), 2000, "결정 증빙"), policy.text(request.note(), 4000, "결정 내용"),
                request.dueAt(), locked.analysis().revision(), effect, String.valueOf(actor.id()), OffsetDateTime.now(clock));
        State next = request.verdict() == DecisionVerdict.APPROVE ? State.APPROVED : State.DECLINED;
        return save(actor, before, before.toBuilder().state(next).decision(decision).build(), "DECISION_" + request.verdict().name(), decision.note(), decision);
    }

    /** 목적: 조직이 실행 경과를 기록하고 후속 확정본과 자기 조직 전망만 비교한다. Args: actor 조직 담당자, id 요청, request 진행 내용. Returns: 진행 상태. Raises: 권한/409/후속 기준 오류. Side Effects: 진행·고정 후속 비교 DB 저장. */
    @Transactional
    public WorkItem progress(AuthenticatedUser actor, long id, ProgressRequest request) {
        actor.requireRole(UserRole.ORG_MANAGER);
        WorkItem before = store.get(actor, id, true);
        policy.revision(before.revision(), request.expectedRevision());
        policy.state(before, State.APPROVED, State.IN_PROGRESS);
        if (request.status() == null) throw new BusinessException(ErrorCode.INVALID_REQUEST, "진행 상태를 선택해 주세요.");
        String note = policy.text(request.note(), 4000, "진행 내용");
        String evidence = policy.text(request.evidenceRef(), 2000, "진행 증빙");
        boolean hasFollowup = request.followupMasterId() != null && !request.followupMasterId().isBlank();
        if (request.status() == ProgressStatus.COMPLETED && !hasFollowup) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "실행 완료에는 후속 확정 master와 증빙이 필요합니다.");
        }
        Snapshot followup = null;
        Organization organization = null;
        if (hasFollowup) {
            followup = baselines.load(positiveId(request.followupMasterId()));
            organization = policy.followupOrganization(before, followup);
        }
        Progress progress = new Progress(request.status(), note, evidence, followup == null ? null : followup.masterId(),
                followup == null ? null : followup.masterLabel(), before.baselineOrganizationProfitWon(),
                organization == null ? null : organization.profitWon(), organization == null ? null
                : DecisionScenarioCalculator.decimal(new BigDecimal(organization.profitWon()).subtract(new BigDecimal(before.baselineOrganizationProfitWon()))),
                String.valueOf(actor.id()), OffsetDateTime.now(clock));
        State next = request.status() == ProgressStatus.COMPLETED ? State.COMPLETED : State.IN_PROGRESS;
        return save(actor, before, before.toBuilder().state(next).progress(progress).build(), "PROGRESS_" + request.status().name(), note, progress);
    }

    /** 목적: 미완료 요청/과제를 취소하고 연결 효과를 분석에서 제거한다. Args: actor 소유 기획자, id 요청, request 사유/버전. Returns: 취소 요청. Raises: 권한/409. Side Effects: 취소 이력·분석 DB 갱신, 이전 계산 무효화. */
    @Transactional
    public WorkItem cancel(AuthenticatedUser actor, long id, CancelRequest request) {
        Locked locked = lockPlanner(actor, id, request.expectedRevision(), request.expectedAnalysisRevision());
        WorkItem before = locked.item();
        policy.state(before, State.DRAFT, State.REQUESTED, State.CHANGES_REQUESTED, State.REPLIED, State.ACCEPTED, State.APPROVED, State.IN_PROGRESS);
        String note = policy.text(request.note(), 4000, "취소 사유");
        removeLinkedCandidate(actor, before, locked.analysis());
        return save(actor, before, before.toBuilder().state(State.CANCELLED).build(), "CANCELLED", note, null);
    }

    /** 목적: 모든 기획자 수정에서 분석→요청 순서로 잠근다. Args: actor 기획자, id 요청, revision 요청 버전, analysisRevision 선택 분석 버전. Returns: 잠긴 분석/요청. Raises: 권한/404/409. Side Effects: DB 행 잠금. */
    private Locked lockPlanner(AuthenticatedUser actor, long id, int revision, Integer analysisRevision) {
        actor.requireRole(UserRole.PLANNER);
        WorkItem reference = store.get(actor, id, false);
        Analysis analysis = analyses.get(actor.id(), Long.parseLong(reference.analysisId()), true);
        WorkItem item = store.get(actor, id, true);
        policy.revision(item.revision(), revision);
        if (analysisRevision != null) policy.revision(analysis.revision(), analysisRevision);
        return new Locked(analysis, item);
    }

    /** 목적: 변경마다 이력을 추가하고 revision을 증가시킨다. Args: actor 사용자, before 이전, after 다음 상태, action 동작, note 설명, details 감사 세부. Returns: 저장 요청. Raises: 409 이력 상한/경쟁 변경. Side Effects: DB 갱신. */
    private WorkItem save(AuthenticatedUser actor, WorkItem before, WorkItem after, String action, String note, Object details) {
        if (before.events().size() >= 500) throw new BusinessException(ErrorCode.INVALID_REQUEST, "요청 이력 한도에 도달했습니다. 새 확인 요청으로 이어가 주세요.");
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Event> events = new ArrayList<>(before.events());
        events.add(new Event(events.size() + 1, action, String.valueOf(actor.id()), now, note, details == null ? null : mapper.valueToTree(details)));
        WorkItem changed = after.toBuilder().revision(before.revision() + 1).updatedAt(now).events(List.copyOf(events)).build();
        return store.update(actor, before.revision(), changed);
    }

    /** 목적: 거절·취소한 효과를 제거하되 이후 사용자 편집을 덮어쓰지 않는다. Args: actor 소유 기획자, item 요청, analysis 잠긴 분석. Returns: 없음. Raises: 409 대상 후보 변경. Side Effects: 후보 제거·분석 revision 증가·계산 무효화. */
    private void removeLinkedCandidate(AuthenticatedUser actor, WorkItem item, Analysis analysis) {
        Candidate current = analysis.candidates().stream().filter(candidate -> candidate.id().equals(item.candidateId())).findFirst().orElse(null);
        if (current == null) return;
        Candidate expected = item.review() != null && item.review().verdict() == ReviewVerdict.ACCEPT
                ? policy.acceptedCandidate(item) : item.sourceCandidate();
        if (!Objects.equals(current, expected)) {
            throw new BusinessException(ErrorCode.RESOURCE_CHANGED, "연결된 후보가 확인 요청 이후 수정되었습니다. 새 편집을 유지하기 위해 거절·취소를 중단했습니다.");
        }
        List<Candidate> remaining = analysis.candidates().stream().filter(candidate -> !candidate.id().equals(item.candidateId())).toList();
        analysisService.candidates(actor, Long.parseLong(analysis.id()), new CandidatesRequest(analysis.revision(), remaining));
    }

    /** 목적: 조직 회신의 후보 검증에 필요한 최소 조직 범위를 만든다. Args: item 요청. Returns: 한 조직만 있는 검증용 기준. Raises: 없음. Side Effects: 없음. */
    private Snapshot localSnapshot(WorkItem item) {
        return new Snapshot("workflow-validation", item.masterLabel(), item.year(), item.baseMonth(), item.createdAt(), List.of(),
                List.of(new Organization(item.organizationId(), "", item.organizationName(), "0", "0", item.baselineOrganizationProfitWon())), item.baselineOrganizationProfitWon());
    }

    /** 목적: 후속 기준 ID를 안전하게 파싱한다. Args: value 식별자. Returns: 양의 long. Raises: INVALID_REQUEST 잘못된 ID. Side Effects: 없음. */
    private long positiveId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "후속 masterId는 양의 정수 문자열입니다.");
        }
    }

    private record Locked(Analysis analysis, WorkItem item) { }
}
