package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;

import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 경영기획팀 개인 분석의 기준 고정·후보 검토·시나리오 실행을 조율한다. */
@Service
@RequiredArgsConstructor
public class DecisionAgentService {
    private final DecisionAnalysisStore store;
    private final DecisionBaselineLoader baselines;
    private final DecisionScenarioCalculator calculator;
    private final DecisionNarrativeService narratives;
    private final Clock clock;

    /** 목적: 확정 기준 선택지를 제공한다. Args: actor 사용자, year 연도. Returns: 기준 목록. Raises: 403 권한, 입력 오류. Side Effects: DB 읽기. */
    @Transactional(readOnly = true)
    public Items<Baseline> baselines(AuthenticatedUser actor, Integer year) {
        actor.requireRole(UserRole.PLANNER);
        return new Items<>(baselines.list(year));
    }

    /** 목적: 본인의 분석만 나열한다. Args: actor 사용자. Returns: 분석 목록. Raises: 403 권한. Side Effects: DB 읽기. */
    @Transactional(readOnly = true)
    public Items<AnalysisSummary> list(AuthenticatedUser actor) {
        actor.requireRole(UserRole.PLANNER);
        return new Items<>(store.list(actor.id()));
    }

    /** 목적: 본인 분석을 다시 연다. Args: actor 사용자, id 분석 ID. Returns: 저장된 분석. Raises: 403 권한, 404 미소유/미존재. Side Effects: DB 읽기. */
    @Transactional(readOnly = true)
    public Analysis get(AuthenticatedUser actor, long id) {
        actor.requireRole(UserRole.PLANNER);
        return store.get(actor.id(), id, false);
    }

    /** 목적: 확정 master를 복사하여 새로운 분석을 시작한다. Args: actor 사용자, request 목표/제약. Returns: revision 1 분석. Raises: 권한/기준/입력 오류. Side Effects: 분석 DB 생성. */
    @Transactional
    public Analysis create(AuthenticatedUser actor, CreateRequest request) {
        actor.requireRole(UserRole.PLANNER);
        if (request.title() == null || request.title().isBlank() || request.title().length() > 200
                || request.prompt() == null || request.prompt().isBlank() || request.prompt().length() > 4000
                || request.protectedCategories() == null || request.protectedCategories().size() > 2
                || new HashSet<>(request.protectedCategories()).size() != request.protectedCategories().size()
                || request.protectedCategories().stream().anyMatch(category -> category != Category.HEADCOUNT && category != Category.RND)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "제목·요청과 보호 제약(HEADCOUNT/RND)을 확인해 주세요.");
        }
        String target = DecisionScenarioCalculator.decimal(calculator.amount(request.targetProfitWon(), true));
        long masterId;
        try {
            masterId = Long.parseLong(request.masterId());
            if (masterId <= 0) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "masterId는 양의 정수 문자열입니다.");
        }
        Snapshot snapshot = baselines.load(masterId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        return store.create(actor.id(), new Analysis(null, request.title().strip(), request.prompt().strip(), 1, "DRAFT", now, now,
                target, List.copyOf(request.protectedCategories()), snapshot, List.of(), null, null));
    }

    /** 목적: 후보를 교체하고 이전 계산 결과를 무효화한다. Args: actor 사용자, id 분석, request 예상 버전/후보. Returns: 새 revision 분석. Raises: 권한/소유권/409/후보 오류. Side Effects: DB 상태 갱신. */
    @Transactional
    public Analysis candidates(AuthenticatedUser actor, long id, CandidatesRequest request) {
        actor.requireRole(UserRole.PLANNER);
        Analysis before = current(actor, id, request.expectedRevision());
        calculator.validate(before.snapshot(), request.candidates());
        Analysis after = changed(before, "DRAFT", List.copyOf(request.candidates()), null, null);
        return store.update(actor.id(), request.expectedRevision(), after);
    }

    /** 목적: 현재 후보를 검증·계산하고 설명과 함께 저장한다. Args: actor 사용자, id 분석, request 예상 버전. Returns: 계산 완료 분석. Raises: 권한/소유권/409/후보 오류. Side Effects: DB 갱신 및 내부 AI 호출. */
    @Transactional
    public Analysis run(AuthenticatedUser actor, long id, RunRequest request) {
        actor.requireRole(UserRole.PLANNER);
        Analysis before = current(actor, id, request.expectedRevision());
        Result result = calculator.calculate(before.snapshot(), before.targetProfitWon(), before.protectedCategories(), before.candidates());
        Narrative narrative = narratives.compose(actor.id(), before, result);
        return store.update(actor.id(), request.expectedRevision(), changed(before, "COMPLETED", before.candidates(), result, narrative));
    }

    /** 목적: 소유 분석을 잠그고 예상 버전을 비교한다. Args: actor 사용자, id 분석, revision 요청 버전. Returns: 현재 상태. Raises: 404/409. Side Effects: DB 행 잠금. */
    private Analysis current(AuthenticatedUser actor, long id, int revision) {
        Analysis analysis = store.get(actor.id(), id, true);
        if (analysis.revision() != revision) throw new BusinessException(ErrorCode.RESOURCE_CHANGED);
        return analysis;
    }

    /** 목적: 불변 기준은 유지하며 다음 상태를 구성한다. Args: before 기존 분석, status 상태, candidates 후보, result 계산, narrative 설명. Returns: 다음 revision. Raises: 없음. Side Effects: 시계 읽기. */
    private Analysis changed(Analysis before, String status, List<Candidate> candidates, Result result, Narrative narrative) {
        return new Analysis(before.id(), before.title(), before.prompt(), before.revision() + 1, status, before.createdAt(),
                OffsetDateTime.now(clock), before.targetProfitWon(), before.protectedCategories(), before.snapshot(), candidates, result, narrative);
    }
}
