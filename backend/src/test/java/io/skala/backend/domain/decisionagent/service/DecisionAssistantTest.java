package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static io.skala.backend.domain.decisionagent.dto.DecisionAssistantDtos.*;
import static io.skala.backend.domain.decisionagent.dto.DecisionResearchDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.skala.backend.domain.dashboard.dto.HomePnlResponse;
import io.skala.backend.domain.dashboard.dto.HomeDivisionsResponse;
import io.skala.backend.domain.dashboard.dto.OrganizationRef;
import io.skala.backend.domain.dashboard.dto.OrganizationVersionsResponse;
import io.skala.backend.domain.dashboard.service.ArchiveQueryService;
import io.skala.backend.domain.dashboard.service.HomePnlQueryService;
import io.skala.backend.domain.decisionworkflow.service.DecisionWorkflowService;
import io.skala.backend.domain.report.service.ReportAiClient;
import io.skala.backend.domain.review.dto.VersionComparisonResponse;
import io.skala.backend.domain.review.service.VersionComparisonService;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.repository.SubmissionRepository;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 실제 DB 없이 동적 Tool의 권한·고정 기준·관찰·인용 경계를 검증한다. */
class DecisionAssistantTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DecisionAssistantJson json = new DecisionAssistantJson(mapper);
    private final DecisionReportTools reports = mock(DecisionReportTools.class);
    private final DecisionExplanationTools explanationOverview = mock(DecisionExplanationTools.class);
    private final HomePnlQueryService home = mock(HomePnlQueryService.class);
    private final ArchiveQueryService archive = mock(ArchiveQueryService.class);
    private final VersionComparisonService comparison = mock(VersionComparisonService.class);
    private final DecisionWorkflowService workflow = mock(DecisionWorkflowService.class);
    private final DecisionResearchStore research = mock(DecisionResearchStore.class);
    private final DecisionDiscoveryService discovery = mock(DecisionDiscoveryService.class);
    private final DecisionBaselineLoader baselines = mock(DecisionBaselineLoader.class);
    private final DecisionAnalysisStore analyses = mock(DecisionAnalysisStore.class);
    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AppUser user = mock(AppUser.class);
    private final Clock clock = mock(Clock.class);
    private final DecisionScenarioCalculator calculator = spy(new DecisionScenarioCalculator());
    private final AuthenticatedUser actor = new AuthenticatedUser(7L, UserRole.PLANNER, null);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-26T00:00:00Z");
    private DecisionAssistantTools tools;
    private DecisionAssistantGateway gateway;
    private DecisionAssistantResponseValidator validator;
    private String token;

    /** 목적: 권한과 확정 전망을 메모리 대역으로 준비한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: mock 구성만. */
    @BeforeEach
    void prepare() {
        when(clock.instant()).thenReturn(now.toInstant());
        when(users.findById(7L)).thenReturn(Optional.of(user)); when(user.getRole()).thenReturn(UserRole.PLANNER); when(user.getActive()).thenReturn(true);
        when(baselines.load(3L)).thenReturn(snapshot());
        when(home.pnl(actor, "YEAR", 2026, 11, 3L)).thenReturn(new HomePnlResponse("READY", List.of(new HomePnlResponse.Kpi("OP", "-15000000000", null, "0")), List.of(), null));
        tools = new DecisionAssistantTools(home, archive, comparison, calculator, workflow, research, discovery, baselines, analyses, submissions, users, json, reports, explanationOverview);
        gateway = new DecisionAssistantGateway(tools, json, clock); validator = new DecisionAssistantResponseValidator(json);
        token = gateway.grant(actor, snapshot(), null);
    }

    /** 목적: 분석 없이 선택한 master와 기존 손익 서비스를 사용한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회만. */
    @Test
    void reusesFixedHomeServiceWithoutCreatingResearchOrAnalysis() {
        Observation value = call("company_pnl", Map.of());
        assertThat(value.status()).isEqualTo("SUCCESS");
        assertThat(value.data().at("/kpis/0/actual").textValue()).isEqualTo("-15000000000");
        assertThat(value.basis().comparisonBasis()).contains("조회 시점");
        verify(home).pnl(actor, "YEAR", 2026, 11, 3L);
        verify(research).assertSameAmounts(snapshot(), snapshot());
        verify(research, never()).capture(any()); verify(analyses, never()).create(anyLong(), any());
    }

    /** 목적: 전사 상세 행을 보존하면서 비용 증가와 매출 감소의 불리한 부호를 정확히 표시한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회만. */
    @Test
    void summarizesCompanyKpisWithoutReversingCostAndRevenueMeaning() {
        when(home.pnl(actor, "YEAR", 2026, 11, 3L)).thenReturn(new HomePnlResponse("READY", List.of(
                new HomePnlResponse.Kpi("REVENUE", "100.123456", "-10", "-20.123456"),
                new HomePnlResponse.Kpi("COST", "70", "10", "15"),
                new HomePnlResponse.Kpi("OP", "30.123456", "0", "0")),
                List.of(new HomePnlResponse.Row("1", "ITEM", "합성 항목", 1, "100", null, null, null, null, false, null)), null));
        var data = call("company_pnl", Map.of()).data();
        assertThat(data.get("rows")).hasSize(1); assertThat(data.at("/summary/detailRowCount").intValue()).isEqualTo(1);
        assertThat(data.at("/rows/0/name").textValue()).isEqualTo("합성 항목");
        assertThat(data.at("/kpis/0/actual").textValue()).isEqualTo("100.123456");
        assertThat(data.at("/summary/metrics/0/planDifferenceWon").textValue()).isEqualTo("-20.123456");
        assertThat(data.at("/summary/metrics/0/direction").textValue()).isEqualTo("DECREASE");
        assertThat(data.at("/summary/metrics/0/favorability").textValue()).isEqualTo("UNFAVORABLE");
        assertThat(data.at("/summary/metrics/1/direction").textValue()).isEqualTo("INCREASE");
        assertThat(data.at("/summary/metrics/1/favorability").textValue()).isEqualTo("UNFAVORABLE");
        assertThat(data.at("/summary/metrics/2/favorability").textValue()).isEqualTo("NEUTRAL");
    }

    /** 목적: 계획 결측을 영 또는 유리한 차이로 대체하지 않고 비용 감소를 유리하게 판정한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회만. */
    @Test
    void marksMissingPlanAndFavorableCostReduction() {
        when(home.pnl(actor, "YEAR", 2026, 11, 3L)).thenReturn(new HomePnlResponse("READY", List.of(
                new HomePnlResponse.Kpi("REVENUE", "100", null, null),
                new HomePnlResponse.Kpi("COST", "70", "-10", "-15")), List.of(), null));
        var data = call("company_pnl", Map.of()).data();
        assertThat(data.at("/summary/metrics/0/missing").booleanValue()).isTrue();
        assertThat(data.at("/summary/metrics/0/planDifferenceWon").isNull()).isTrue();
        assertThat(data.at("/summary/metrics/0/direction").textValue()).isEqualTo("MISSING");
        assertThat(data.at("/summary/metrics/0/favorability").textValue()).isEqualTo("UNKNOWN");
        assertThat(data.at("/summary/metrics/1/favorability").textValue()).isEqualTo("FAVORABLE");
    }

    /** 목적: 조직 코드를 ID로 혼동하지 않고 개선 순위·차이 절댓값 순위·계획과 행 누락을 구분한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회만. */
    @Test
    void ranksOrganizationDeltasBySignedAndAbsoluteValuesAndKeepsMissing() {
        var base = snapshot();
        var fixed = new Snapshot(base.masterId(), base.masterLabel(), base.year(), base.baseMonth(), base.asOf(), base.sourceSubmissionIds(), List.of(
                new Organization("1", "ORG-A", "합성 가", "100", "80", "20"), new Organization("2", "ORG-B", "합성 나", "100", "95", "5"),
                new Organization("3", "ORG-C", "합성 다", "100", "80", "20"), new Organization("4", "ORG-D", "합성 라", "100", "80", "20")), base.baselineProfitWon());
        token = gateway.grant(actor, fixed, null); when(baselines.load(3L)).thenReturn(fixed);
        when(home.divisions(actor, "YEAR", 2026, 11, 3L)).thenReturn(new HomeDivisionsResponse("READY", List.of(
                new HomeDivisionsResponse.Column(new OrganizationRef("1", "ORG-A", "합성 가"), "100", "20", null, null, "2"),
                new HomeDivisionsResponse.Column(new OrganizationRef("2", "ORG-B", "합성 나"), "100", "5", null, null, "-10"),
                new HomeDivisionsResponse.Column(new OrganizationRef("3", "ORG-C", "합성 다"), "100", "20", null, null, null),
                new HomeDivisionsResponse.Column(new OrganizationRef("99", "OUTSIDE", "범위 밖"), "100", "20", null, null, "999")), null));
        var data = call("organization_pnl", Map.of()).data();
        assertThat(data.get("columns")).hasSize(3);
        assertThat(data.at("/summary/scopeOrganizationCount").intValue()).isEqualTo(4);
        assertThat(data.at("/summary/returnedOrganizationCount").intValue()).isEqualTo(3);
        assertThat(data.at("/summary/rankedByOpPlanDifference/0/organization/id").textValue()).isEqualTo("1");
        assertThat(data.at("/summary/rankedByAbsoluteOpPlanDifference/0/organization/id").textValue()).isEqualTo("2");
        assertThat(data.at("/summary/rankedByOpPlanDifference/0/rank").intValue()).isEqualTo(1);
        assertThat(data.at("/summary/rankedByOpPlanDifference/1/rank").intValue()).isEqualTo(2);
        assertThat(data.at("/summary/rankedByOpPlanDifference/2/rank").isNull()).isTrue();
        assertThat(data.at("/summary/rankedByOpPlanDifference/2/planDifferenceWon").isNull()).isTrue();
        assertThat(data.at("/summary/missingOrganizationIds/0").textValue()).isEqualTo("4");
        assertThat(data.toString()).doesNotContain("OUTSIDE");
        assertThat(call("organization_pnl", Map.of("organizationId", "2")).data().get("columns")).hasSize(1);
    }

    /** 목적: 월간 도구가 연간 서비스를 혼용하지 않고 고정 기준월·전체 합계와 조직 범위를 구분한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회·메모리 원장만. */
    @Test
    void readsFixedMonthlyActualsWithoutChangingAnnualReportScope() {
        when(home.pnl(actor, "MONTH", 2026, 11, 3L)).thenReturn(new HomePnlResponse("CONFIRMED",
                List.of(new HomePnlResponse.Kpi("OP", "70", null, "-5")), List.of(), null));
        when(home.divisions(actor, "MONTH", 2026, 11, 3L)).thenReturn(new HomeDivisionsResponse("CONFIRMED", List.of(
                new HomeDivisionsResponse.Column(new OrganizationRef("1", "A", "A사업부"), "100", "70", null, null, "-5"),
                new HomeDivisionsResponse.Column(new OrganizationRef("99", "OUTSIDE", "범위 밖"), "50", "20", null, null, "2")),
                new HomeDivisionsResponse.Total("150", "90", null, "-3")));
        Observation observation = call("monthly_pnl", Map.of("organizationId", "1"));
        var data = observation.data();
        assertThat(observation.status()).isEqualTo("SUCCESS");
        assertThat(data.path("period").textValue()).isEqualTo("MONTH");
        assertThat(data.path("year").intValue()).isEqualTo(2026); assertThat(data.path("month").intValue()).isEqualTo(11);
        assertThat(data.at("/company/kpis/0/actual").textValue()).isEqualTo("70");
        assertThat(data.path("companyScope").textValue()).isEqualTo("ALL_MASTER_ORGANIZATIONS");
        assertThat(data.at("/organizations/columns")).hasSize(1);
        assertThat(data.at("/organizations/total/op").textValue()).isEqualTo("90");
        assertThat(data.at("/organizations/summary/rankedByOpPlanDifference/0/planDifferenceWon").textValue()).isEqualTo("-5");
        assertThat(data.toString()).doesNotContain("OUTSIDE");
        verify(home, never()).pnl(actor, "YEAR", 2026, 11, 3L); verify(home, never()).divisions(actor, "YEAR", 2026, 11, 3L);
        assertThat(call("monthly_pnl", Map.of("organizationId", "99")).status()).isEqualTo("ERROR");
        for (String key : List.of("month", "year", "masterId", "period"))
            assertThatThrownBy(() -> tools.normalize("monthly_pnl", json.tree(Map.of(key, "1")))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 목적: 항목 상세가 고정 조직·보고 항목만 공개하고 전역 상한·결측 계획·서로 다른 계획 기준을 보존한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회·메모리 원장만. */
    @Test
    void filtersReportItemEvidenceWithoutClaimingCompleteAccountCoverage() {
        List<Evidence> evidence = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            Map<String, Object> facts = new LinkedHashMap<>(); facts.put("reportItemId", String.valueOf(index + 1));
            facts.put("kind", "COST"); facts.put("forecastWon", "70.123456"); facts.put("planWon", null);
            facts.put("varianceWon", null); facts.put("profitImpactWon", null); facts.put("sourceSubmissionIds", List.of("20"));
            facts.put("comparison", "SAME_SUBMISSION_PLAN_VS_ANNUAL_OUTLOOK");
            evidence.add(new Evidence("pnl-" + index, "PNL", index == 0 ? "99" : "1", "합성 항목", "계획 비교는 미확인입니다.", facts));
        }
        evidence.add(new Evidence("proof-1", "APPROVED_EXPLANATION", "1", "승인 소명", "별도 도구 자료", Map.of()));
        when(research.loadEvidence(snapshot())).thenReturn(evidence);
        var data = call("report_item_breakdown", Map.of("organizationId", "1", "limit", 2)).data();
        assertThat(data.path("comparison").textValue()).isEqualTo("SAME_SUBMISSION_PLAN_VS_ANNUAL_OUTLOOK");
        assertThat(data.path("availableCount").intValue()).isEqualTo(99);
        assertThat(data.path("sourceLimit").intValue()).isEqualTo(100); assertThat(data.path("sourceLimitMayApply").booleanValue()).isTrue();
        assertThat(data.path("items")).hasSize(2); assertThat(data.at("/items/0/facts/planWon").isNull()).isTrue();
        assertThat(data.at("/items/0/facts/forecastWon").textValue()).isEqualTo("70.123456");
        assertThat(data.toString()).doesNotContain("APPROVED_EXPLANATION", "pnl-0");
        var filtered = call("report_item_breakdown", Map.of("reportItemId", "7")).data();
        assertThat(filtered.path("items")).hasSize(1); assertThat(filtered.at("/items/0/facts/reportItemId").textValue()).isEqualTo("7");
        assertThat(call("report_item_breakdown", Map.of("organizationId", "99")).status()).isEqualTo("ERROR");
        verify(research, never()).capture(any()); verify(analyses, never()).create(anyLong(), any());
        for (Object limit : List.of(0, 51, "2", 1.5))
            assertThatThrownBy(() -> tools.normalize("report_item_breakdown", json.tree(Map.of("limit", limit)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.normalize("report_item_breakdown", json.tree(Map.of("reportItemId", "../1")))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 목적: 저장 분석의 최초 항목 근거가 있으면 현재 자료를 섞거나 조사 저장을 새로 수행하지 않는다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회·메모리 원장만. */
    @Test
    void reusesCapturedReportItemEvidenceWithoutNewResearchWrite() {
        selectAnalysis();
        var evidence = new Evidence("pnl-1-7", "PNL", "1", "합성 항목", "저장한 근거", Map.of("reportItemId", "7", "forecastWon", "80"));
        when(research.find("5")).thenReturn(new Research("5", 2, now, List.of(evidence), null));
        var data = call("report_item_breakdown", Map.of()).data();
        assertThat(data.at("/items/0/text").textValue()).isEqualTo("저장한 근거");
        assertThat(data.path("capturedAt").isNull()).isFalse(); assertThat(data.path("sourceLimitMayApply").booleanValue()).isFalse();
        verify(research, never()).loadEvidence(any()); verify(research, never()).capture(any());
    }

    /** 목적: 저장 분석 없이 억원 목표를 정확한 원으로 바꾸고 전망 초과액을 실적 달성과 구분한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회와 메모리 원장만. */
    @Test
    void previewsGoalWithoutAnalysisAndDoesNotSaveOrClaimAchievement() {
        Observation result = call("goal_preview", Map.of("targetProfitEok", "900", "targetYear", 2026));
        var data = result.data();
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(data.get("targetProfitWon").textValue()).isEqualTo("90000000000");
        assertThat(data.get("gapWon").textValue()).isEqualTo("0");
        assertThat(data.get("surplusWon").textValue()).isEqualTo("2000000000");
        assertThat(data.get("signedGapWon").textValue()).isEqualTo("-2000000000");
        assertThat(data.get("forecastMeetsTarget").booleanValue()).isTrue(); assertThat(data.get("saved").booleanValue()).isFalse();
        assertThat(data.has("targetAlreadyMet")).isFalse(); assertThat(data.get("year").intValue()).isEqualTo(2026);
        assertThat(data.get("unit").textValue()).isEqualTo("KRW");
        assertThat(result.warnings()).anyMatch(item -> item.contains("실적 달성"));
        assertThat(call("goal_preview", Map.of("targetProfitEok", "900.00000000", "targetYear", 2026))).isSameAs(result);
        verifyNoInteractions(analyses, workflow, discovery, home);
    }

    /** 목적: 부족·일치 목표와 일 원·기존 최대 원 금액을 반올림 없이 계산한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회와 메모리 원장만. */
    @Test
    void previewsGoalGapEqualityAndExactWonBounds() {
        var below = call("goal_preview", Map.of("targetProfitEok", "1050", "targetYear", 2026)).data();
        assertThat(below.get("gapWon").textValue()).isEqualTo("13000000000");
        assertThat(below.get("surplusWon").textValue()).isEqualTo("0"); assertThat(below.get("forecastMeetsTarget").booleanValue()).isFalse();
        var equal = call("goal_preview", Map.of("targetProfitEok", "920", "targetYear", 2026)).data();
        assertThat(equal.get("gapWon").textValue()).isEqualTo("0"); assertThat(equal.get("surplusWon").textValue()).isEqualTo("0");
        assertThat(equal.get("forecastMeetsTarget").booleanValue()).isTrue();
        assertThat(call("goal_preview", Map.of("targetProfitEok", "0.00000001", "targetYear", 2026)).data().get("targetProfitWon").textValue()).isEqualTo("1");
        assertThat(call("goal_preview", Map.of("targetProfitEok", "9999999999999999.99999999", "targetYear", 2026)).data().get("targetProfitWon").textValue()).isEqualTo("999999999999999999999999");
    }

    /** 목적: 목표의 음수·영·정밀도 초과·범위 초과·지수·숫자 JSON과 기준 덮어쓰기를 거부한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void rejectsInvalidGoalFormatsAndInjectedYearWithoutDatabaseAccess() {
        for (Object value : List.of("0", "-900", "+900", "0900", "900 ", "9e2", "9,00", "0.000000001", "10000000000000000", 900)) {
            assertThatThrownBy(() -> tools.normalize("goal_preview", json.tree(Map.of("targetProfitEok", value, "targetYear", 2026))))
                    .as(String.valueOf(value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> tools.normalize("goal_preview", json.tree(Map.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.normalize("goal_preview", json.tree(Map.of("targetProfitEok", "900", "targetYear", 2026, "year", 2027)))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(home, research, analyses, workflow, discovery);
    }

    /** 목적: 목표 연도 누락·형식·범위 오류를 기본 연도로 대체하지 않고 거부한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 원장만, 업무 조회·계산 없음. */
    @Test
    void rejectsMissingOrInvalidTargetYearBeforeCalculation() {
        for (Object year : List.of(1999, 10000, "2026", 2026.0, true, Long.MAX_VALUE)) {
            assertThatThrownBy(() -> tools.normalize("goal_preview", json.tree(Map.of("targetProfitEok", "900", "targetYear", year))))
                    .as(String.valueOf(year)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> tools.normalize("goal_preview", mapper.createObjectNode().put("targetProfitEok", "900").putNull("targetYear")))
                .isInstanceOf(IllegalArgumentException.class);
        Observation missing = call("goal_preview", Map.of("targetProfitEok", "900"));
        assertThat(missing.status()).isEqualTo("ERROR");
        assertThat(missing.data()).isNull();
        verifyNoInteractions(calculator, baselines, home, research, analyses, workflow, discovery);
    }

    /** 목적: 기준과 다른 목표 연도는 원천 조회와 계산 전에 차단하고 기간 확인 사유를 남긴다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 원장만. */
    @Test
    void rejectsMismatchedTargetYearBeforeBaselineReadOrCalculation() {
        token = gateway.grant(actor, snapshot(), null, "2027년 영업이익 목표 900억을 이 기준과 비교하고 다르면 알려 줘");
        Observation result = call("goal_preview", Map.of("targetProfitEok", "900", "targetYear", 2027));
        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.data()).isNull();
        assertThat(result.warnings()).singleElement().asString().contains("2027년", "2026년", "목표 연도", "마스터");
        verifyNoInteractions(calculator, baselines, home, research, analyses, workflow, discovery);
    }

    /** 목적: 기본 인자가 같은 조회는 한 번 실행하고 권한은 매번 검사한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회만. */
    @Test
    void normalizesDefaultsAndCachesOnlyWithinCapability() {
        Observation first = call("profit_drivers", Map.of());
        Observation second = call("profit_drivers", Map.of("limit", 5));
        assertThat(second).isSameAs(first); assertThat(gateway.require(token).observations).hasSize(1);
        verify(home, times(1)).opDrivers(actor, "YEAR", 2026, 11, 3L, 5);
        verify(users, atLeast(2)).findById(7L);
    }

    /** 목적: 고정 범위를 덮어쓰는 모델 인자를 거부한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void rejectsInjectedMasterSqlUrlAndWriteTool() {
        for (String field : List.of("masterId", "userId", "sql", "url")) {
            assertThat(call("company_pnl", Map.of(field, "untrusted")).status()).isEqualTo("ERROR");
        }
        assertThat(call("issue_request", Map.of()).status()).isEqualTo("ERROR");
        verifyNoInteractions(home, workflow, discovery);
    }

    /** 목적: 고정 조직 밖 조회를 막는다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회만. */
    @Test
    void rejectsOrganizationOutsideSnapshot() {
        assertThat(call("organization_pnl", Map.of("organizationId", "999")).status()).isEqualTo("ERROR");
        verifyNoInteractions(home);
    }

    /** 목적: 분석이 필요한 도구는 명확한 선택 안내를 남긴다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회만. */
    @Test
    void explainsMissingAnalysisInsteadOfInventingCandidates() {
        Observation result = call("scenario_preview", Map.of());
        assertThat(result.status()).isEqualTo("ERROR"); assertThat(result.warnings().getFirst()).contains("분석을 생성하거나 선택");
        assertThat(result.data()).isNull();
    }

    /** 목적: 저장 후보만 읽기 계산하고 사용자가 입력하지 않은 새 금액을 거부한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회만. */
    @Test
    void simulatesStoredCandidatesPreciselyWithoutPersistence() {
        selectAnalysis();
        Observation result = call("scenario_preview", Map.of("candidateIds", List.of("c1")));
        assertThat(result.status()).isEqualTo("SUCCESS"); assertThat(result.data().get("conditionalEffectWon").asText()).isEqualTo("3500000000");
        assertThat(call("scenario_preview", Map.of("candidateIds", List.of("unknown"))).status()).isEqualTo("ERROR");
        assertThat(call("scenario_preview", Map.of("amountWon", "999999999")).status()).isEqualTo("ERROR");
        verify(analyses, never()).update(anyLong(), anyInt(), any());
    }

    /** 목적: 요청 초안은 저장된 후보만 사용하고 자동 발행하지 않는다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회만. */
    @Test
    void producesManualDraftFromStoredCandidateOnly() {
        selectAnalysis();
        Observation result = call("review_request_draft", Map.of("sourceKind", "candidate", "sourceId", "c1"));
        assertThat(result.data().get("needsConfirmation").booleanValue()).isTrue();
        assertThat(result.data().get("expectedAnalysisRevision").intValue()).isEqualTo(2);
        assertThat(result.data().get("sourceId").asText()).isEqualTo("c1");
        assertThat(result.warnings().getFirst()).contains("저장·발송되지 않았습니다");
        verifyNoInteractions(workflow);
    }

    /** 목적: 역할 변경 뒤 기존 캐시도 읽지 못하게 한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 구성만. */
    @Test
    void revokedRoleCannotReuseCachedObservation() {
        call("company_pnl", Map.of()); when(user.getActive()).thenReturn(false);
        assertThatThrownBy(() -> call("company_pnl", Map.of())).isInstanceOf(BusinessException.class);
        verify(home, times(1)).pnl(actor, "YEAR", 2026, 11, 3L);
    }

    /** 목적: 분석 수정 후 오래된 capability로 조회하지 못하게 한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 구성만. */
    @Test
    void changedAnalysisRevisionStopsNextToolCall() {
        selectAnalysis(); Analysis current = analysis();
        when(analyses.get(7L, 5L, false)).thenReturn(new Analysis(current.id(), current.title(), current.prompt(), 3, current.status(), now, now,
                current.targetProfitWon(), current.protectedCategories(), current.snapshot(), current.candidates(), null, null));
        assertThatThrownBy(() -> call("company_pnl", Map.of())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(home);
    }

    /** 목적: 고정 원천 수치가 바뀌면 조회 결과를 내보내지 않는다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 구성만. */
    @Test
    void changedSourceAmountsNeverReachHomeQuery() {
        doThrow(new BusinessException(ErrorCode.RESOURCE_CHANGED)).when(research).assertSameAmounts(any(), any());
        assertThat(call("company_pnl", Map.of()).status()).isEqualTo("ERROR"); verifyNoInteractions(home);
    }

    /** 목적: 만료·폐기·추측 capability를 모두 거부한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리만. */
    @Test
    void rejectsExpiredRevokedAndUnknownTokens() {
        assertThatThrownBy(() -> gateway.call("guess", request("company_pnl", Map.of()))).isInstanceOf(BusinessException.class);
        gateway.revoke(token); assertThatThrownBy(() -> call("company_pnl", Map.of())).isInstanceOf(BusinessException.class);
        token = gateway.grant(actor, snapshot(), null); when(clock.instant()).thenReturn(now.plusSeconds(181).toInstant());
        assertThatThrownBy(() -> call("company_pnl", Map.of())).isInstanceOf(BusinessException.class);
    }

    /** 목적: 실제 고유 호출을 제한하고 미허용 인자 반복도 예산에 포함한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리만. */
    @Test
    void limitsUniqueCallsToEight() {
        for (int index = 0; index < 8; index++) call("company_pnl", Map.of("unknown", index));
        assertThatThrownBy(() -> call("company_pnl", Map.of("unknown", 9))).isInstanceOf(BusinessException.class);
        assertThat(gateway.require(token).observations).hasSize(8);
    }

    /** 목적: 과다 관찰은 저장하거나 파일로 내보내지 않고 작은 오류로 남긴다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 구성만. */
    @Test
    void boundsLedgerBeforeExposingOversizedObservation() {
        when(home.pnl(actor, "YEAR", 2026, 11, 3L)).thenReturn(new HomePnlResponse("가".repeat(100000), List.of(), List.of(), null));
        Observation result = call("company_pnl", Map.of());
        assertThat(result.status()).isEqualTo("ERROR"); assertThat(result.data()).isNull();
        assertThat(json.size(gateway.require(token).observations)).isLessThan(192 * 1024);
    }

    /** 목적: 발행 이후·미승인 버전과 파일 경로를 제외하고 허용 버전만 비교한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회만. */
    @Test
    void requiresPreviouslyReturnedApprovedVersionBeforeComparison() {
        when(archive.versions(actor, 1L, 2026, 11)).thenReturn(new OrganizationVersionsResponse(new OrganizationVersionsResponse.OrganizationRef("1", "A", "A사업부"), List.of(), List.of(
                new OrganizationVersionsResponse.History("10", "이전", "APPROVED", now.minusDays(1), new OrganizationVersionsResponse.FileRef("secret.xlsx", "https://private/file")),
                new OrganizationVersionsResponse.History("11", "새 버전", "APPROVED", now.plusDays(1), null),
                new OrganizationVersionsResponse.History("12", "검토 중", "PENDING", now.minusDays(2), null))));
        assertThat(call("submission_comparison", Map.of("organizationId", "1", "baseSubmissionId", "999")).status()).isEqualTo("ERROR");
        Observation versions = call("submission_versions", Map.of("organizationId", "1"));
        assertThat(versions.data().get("history")).hasSize(1); assertThat(versions.data().toString()).doesNotContain("private", "secret.xlsx", "file");
        Submission target = mock(Submission.class); when(target.getId()).thenReturn(20L); when(target.getOrganizationId()).thenReturn(1L);
        when(submissions.findAllById(List.of(20L))).thenReturn(List.of(target));
        when(comparison.compare(20L, 10L, actor)).thenReturn(new VersionComparisonResponse(null, null, List.of()));
        assertThat(call("submission_comparison", Map.of("organizationId", "1", "baseSubmissionId", "11")).status()).isEqualTo("ERROR");
        assertThat(call("submission_comparison", Map.of("organizationId", "1", "baseSubmissionId", "10")).status()).isEqualTo("SUCCESS");
        verify(comparison).compare(20L, 10L, actor);
    }

    /** 목적: 원장과 동일한 응답과 전체 금액 인용만 수락한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void acceptsExactLedgerAndFullSignedAmountCitation() {
        call("company_pnl", Map.of());
        Response result = validator.validate(proposed("-15000000000"), gateway.require(token));
        assertThat(result.mode()).isEqualTo("MODEL"); assertThat(result.citations().getFirst().quote()).isEqualTo("-15000000000");
    }

    /** 목적: 일반 조직·조건 설명을 한글 금액으로 오인하지 않는다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회만. */
    @Test
    void acceptsQualitativeOrganizationAndConditionWords() {
        call("company_pnl", Map.of());
        for (String answer : List.of("전사 조직의 손익을 비교했습니다.", "이 조직의 전망은 근거에서 확인할 수 있습니다.",
                "전사 조직별 손익은 선택 기준을 따릅니다.", "이 조건을 바탕으로 근거를 확인해 주세요.", "회사 조직의 손익을 확인해 주세요.")) {
            Response result = validator.validate(proposed("-15000000000").put("answer", answer), gateway.require(token));
            assertThat(result.answer()).as(answer).isEqualTo(answer);
        }
    }

    /** 목적: 조사·의존 명사를 금액으로 오인하지 않으면서 구·신규 답변에서 실제 한글 금액을 차단한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 원장·mock 조회만. */
    @Test
    void distinguishesKoreanParticlesFromAmountsInLegacyAndBriefResponses() {
        token = gateway.grant(actor, snapshot(), null, "손익을 확인해 줘"); call("company_pnl", Map.of());
        for (String description : List.of("현재 전망과 목표의 차이만 확인했습니다.", "전사만 조회했습니다.",
                "주어진 변수만 확인했습니다.", "확인된 자료에서 알 수만 있습니다.", "이만 정리하겠습니다.")) {
            assertThat(validator.validate(proposed("-15000000000").put("answer", description), gateway.require(token)).answer()).isEqualTo(description);
            ObjectNode brief = proposedBrief(); brief.put("answer", description);
            ((ObjectNode) brief.get("brief")).put("summary", description);
            ((ObjectNode) brief.at("/brief/findings/0")).put("text", description);
            assertThat(validator.validate(brief, gateway.require(token)).brief().summary()).isEqualTo(description);
        }
        for (String description : List.of("이만 원이 필요합니다.", "수만 원 규모입니다.", "삼십만 원이 필요합니다.",
                "수십만 규모입니다.", "비용이삼십억증가", "이 조 원 규모입니다.", "영업이익은 35억입니다.", "비율은 ½입니다.")) {
            assertThatThrownBy(() -> validator.validate(proposed("-15000000000").put("answer", description), gateway.require(token)))
                    .as(description).isInstanceOf(IllegalArgumentException.class);
            ObjectNode brief = proposedBrief(); brief.put("answer", description);
            ((ObjectNode) brief.get("brief")).put("summary", description);
            assertThatThrownBy(() -> validator.validate(brief, gateway.require(token))).as(description).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 목적: 제한·실패 종료에서도 검증된 설명과 미해결 요구를 보존하되 실제 원장·근거 조건을 완화하지 않는다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 원장·mock 조회만. */
    @Test
    void preservesGroundedPartialBriefWithoutMarkingUnresolvedRequirementsComplete() {
        token = gateway.grant(actor, snapshot(), null, "손익을 확인해 줘"); call("company_pnl", Map.of());
        for (String status : List.of("LIMIT_REACHED", "FAILED")) {
            ObjectNode proposed = proposedBrief(); proposed.put("status", status);
            String notice = "시스템 안내: 최종 요약의 표현 검증을 마치지 못해, 검증을 통과한 개별 설명만 부분 결과로 표시합니다.";
            proposed.put("answer", notice); ((ObjectNode) proposed.get("brief")).put("summary", notice);
            Response answeredPartial = validator.validate(proposed, gateway.require(token));
            assertThat(answeredPartial.status()).isEqualTo(status);
            assertThat(answeredPartial.brief().requirements().getFirst().status()).isEqualTo("ANSWERED");
            var requirement = (ObjectNode) proposed.at("/brief/requirements/0");
            requirement.put("status", "NEEDS_INPUT").put("missingInformation", "설명 검토가 완료되지 않았습니다.")
                    .put("followUpQuestion", "현재 조회 자료에서 우선 확인할 항목을 알려 주세요.");
            Response partial = validator.validate(proposed, gateway.require(token));
            assertThat(partial.status()).isEqualTo(status);
            assertThat(partial.brief().findings()).hasSize(1);
            assertThat(partial.brief().requirements().getFirst().status()).isEqualTo("NEEDS_INPUT");
            assertThat(partial.observations()).containsExactlyElementsOf(gateway.require(token).observations);
            proposed.put("status", "COMPLETED");
            assertThatThrownBy(() -> validator.validate(proposed, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
            proposed.put("status", status); proposed.putArray("observations");
            assertThatThrownBy(() -> validator.validate(proposed, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 목적: 단어 경계 오탐을 고쳐도 실제 숫자·한국어 금액·비율 주장을 계속 차단한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회만. */
    @Test
    void rejectsActualNumericClaimsIncludingKoreanAmountsWithParticles() {
        call("company_pnl", Map.of());
        for (String answer : List.of("영업이익은 35억입니다.", "목표 비율은 ９５%입니다.", "목표 비율은 ½입니다.",
                "삼십억 절감", "이 조 원 규모", "삼십억원이 필요합니다.", "이조원을 검토해 주세요.",
                "수억 원 규모", "십만원 수준", "백만 원 규모", "영업이익 십억원입니다.", "비용이삼십억증가", "두 배 수준", "million 규모")) {
            assertThatThrownBy(() -> validator.validate(proposed("-15000000000").put("answer", answer), gateway.require(token)))
                    .as(answer).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 목적: 마이너스 금액의 일부 숫자 인용을 거부한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void rejectsPartialCitationOfNumericString() {
        call("company_pnl", Map.of());
        assertThatThrownBy(() -> validator.validate(proposed("150"), gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 목적: 모델이 추가·변조한 관찰과 가짜 호출을 거부한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void rejectsInventedObservationAndTrace() {
        call("company_pnl", Map.of());
        ObjectNode fakeData = proposed("-15000000000"); ((ObjectNode) fakeData.at("/observations/0/data/kpis/0")).put("actual", "15000000000");
        assertThatThrownBy(() -> validator.validate(fakeData, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
        ObjectNode fakeTrace = proposed("-15000000000"); ((ObjectNode) fakeTrace.at("/toolCalls/0")).put("tool", "scenario_preview");
        assertThatThrownBy(() -> validator.validate(fakeTrace, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 목적: 모델 요약의 새 금액·미지 경로·임의 동작을 거부한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void rejectsUnsupportedClaimsPathsAndActions() {
        call("company_pnl", Map.of());
        ObjectNode claim = proposed("-15000000000").put("answer", "목표를 100% 달성합니다.");
        assertThatThrownBy(() -> validator.validate(claim, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
        ObjectNode path = proposed("-15000000000"); ((ObjectNode) path.at("/citations/0")).put("path", "/data/missing");
        assertThatThrownBy(() -> validator.validate(path, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
        ObjectNode action = proposed("-15000000000"); action.putArray("nextActions").add("APPROVE");
        assertThatThrownBy(() -> validator.validate(action, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 목적: 공개 응답에 파일·URL·인증 토큰과 부동소수 손실이 남지 않게 한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void sanitizesSensitiveFieldsAndPreservesDecimalStrings() {
        var value = json.safe(Map.of("amount", new BigDecimal("12345678901234567890.123456"), "stateToken", "secret", "file", Map.of("url", "https://private"), "note", "증빙 https://private/test 참고"));
        assertThat(value.get("amount").textValue()).isEqualTo("12345678901234567890.123456");
        assertThat(value.toString()).doesNotContain("secret", "https://", "stateToken", "file");
    }

    /** 목적: AI 연결 실패를 완료 답변으로 꾸미지 않고 capability를 폐기한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 호출만. */
    @Test
    void assistantFailureIsReadOnlyUnavailableAndRevokesCapability() {
        ReportAiClient ai = mock(ReportAiClient.class); when(ai.post(anyString(), any(), anyLong())).thenThrow(new IllegalStateException("secret"));
        var service = new DecisionAssistantService(analyses, baselines, research, gateway, tools, validator, ai);
        Response result = service.ask(actor, new Request("3", null, null, "손익을 확인해 줘", List.of()));
        assertThat(result.mode()).isEqualTo("UNAVAILABLE"); assertThat(result.status()).isEqualTo("FAILED"); assertThat(result.observations()).isEmpty();
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(io.skala.backend.domain.decisionagent.dto.DecisionAssistantDtos.ComposeRequest.class);
        verify(ai).post(eq("/v1/decision-agent/assistant"), requestCaptor.capture(), eq(7L));
        assertThatThrownBy(() -> gateway.require(requestCaptor.getValue().toolAccessToken())).isInstanceOf(BusinessException.class);
        verify(analyses, never()).create(anyLong(), any()); verify(analyses, never()).update(anyLong(), anyInt(), any());
    }

    /** 목적: 사용자·분석 버전·master 경계를 AI 호출 전에 검증한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: mock 조회만. */
    @Test
    void checksOwnershipRevisionAndMasterBeforeAiCall() {
        ReportAiClient ai = mock(ReportAiClient.class); var service = new DecisionAssistantService(analyses, baselines, research, gateway, tools, validator, ai);
        assertThatThrownBy(() -> service.ask(actor, new Request("3", "5", null, "질문", List.of()))).isInstanceOf(BusinessException.class);
        when(analyses.get(7L, 5L, false)).thenReturn(analysis());
        assertThatThrownBy(() -> service.ask(actor, new Request("3", "5", 1, "질문", List.of()))).isInstanceOf(BusinessException.class);
        when(analyses.get(7L, 5L, false)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.ask(actor, new Request("3", "5", 2, "질문", List.of()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.ask(new AuthenticatedUser(8L, UserRole.ORG_MANAGER, 1L), new Request("3", null, null, "질문", List.of()))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(ai);
    }

    /** 목적: JSON HTTP 왕복에서 기준의 시간대와 정밀 문자열을 그대로 검증한다. Args: 없음. Returns: 없음. Raises: 직렬화/검증 실패. Side Effects: 없음. */
    @Test
    void preservesOffsetContextThroughHttpJsonRoundtrip() throws Exception {
        Snapshot source = snapshot();
        Snapshot offset = new Snapshot(source.masterId(), source.masterLabel(), source.year(), source.baseMonth(),
                source.asOf().withOffsetSameInstant(ZoneOffset.ofHours(9)), source.sourceSubmissionIds(), source.organizations(), source.baselineProfitWon());
        token = gateway.grant(actor, offset, null);
        when(baselines.load(3L)).thenReturn(offset);
        call("company_pnl", Map.of());
        var result = validator.validate(mapper.readTree(mapper.writeValueAsBytes(proposed("-15000000000"))), gateway.require(token));
        assertThat(result.context().asOf().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(result.citations().getFirst().quote()).isEqualTo("-15000000000");
    }

    /** 목적: null·boolean은 정확한 JSON 표기로 인용하고 잘못된 pointer escape는 거부한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void supportsExactNullBooleanAndRejectsMalformedPointerEscape() {
        call("company_pnl", Map.of());
        ObjectNode nullQuote = proposed("null"); ((ObjectNode) nullQuote.at("/citations/0")).put("path", "/data/opMargin");
        assertThat(validator.validate(nullQuote, gateway.require(token)).citations().getFirst().quote()).isEqualTo("null");
        ObjectNode wrongEscape = proposed("null"); ((ObjectNode) wrongEscape.at("/citations/0")).put("path", "/data/op~Margin");
        assertThatThrownBy(() -> validator.validate(wrongEscape, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
        var run = gateway.require(token); Observation source = run.observations.getFirst();
        run.observations.set(0, new Observation(source.id(), source.tool(), source.label(), source.status(), source.basis(), json.tree(Map.of("flag", true)), source.warnings()));
        ObjectNode boolQuote = proposed("true"); ((ObjectNode) boolQuote.at("/citations/0")).put("path", "/data/flag");
        assertThat(validator.validate(boolQuote, run).citations().getFirst().quote()).isEqualTo("true");
        ((ObjectNode) boolQuote.at("/citations/0")).put("quote", "tru");
        assertThatThrownBy(() -> validator.validate(boolQuote, run)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 목적: 동시 capability 상한을 넘지 않고 만료 후 공간을 회수한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리만. */
    @Test
    void boundsConcurrentCapabilitiesAndReclaimsExpiredRuns() {
        for (int index = 1; index < 200; index++) gateway.grant(actor, snapshot(), null);
        assertThatThrownBy(() -> gateway.grant(actor, snapshot(), null)).isInstanceOf(BusinessException.class);
        when(clock.instant()).thenReturn(now.plusSeconds(181).toInstant());
        assertThat(gateway.grant(actor, snapshot(), null)).isNotBlank();
    }

    /** 목적: 실제 근거와 질문 인용을 가진 설명·해석을 수락하며 정상 불확실성 문구를 막지 않는다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 대역만. */
    @Test
    void acceptsGroundedBriefAndUncertaintyWithoutTreatingItAsGuarantee() {
        token = gateway.grant(actor, snapshot(), null, "손익을 확인하고 개선 가능성을 검토해 줘");
        call("company_pnl", Map.of());
        ObjectNode proposed = proposedBrief();
        for (String uncertainty : List.of("개선 효과는 미확정이며 조직의 확인이 필요합니다.", "현재 근거로 개선 효과를 확정할 수 없습니다.",
                "승인 소명만으로 개선 효과를 보장할 수 없습니다.", "확정 마스터와 현재 소명의 기준 차이를 확인해야 합니다.")) {
            ((ObjectNode) proposed.at("/brief/findings/0")).put("kind", "INTERPRETATION").put("uncertainty", uncertainty);
            Response result = validator.validate(proposed, gateway.require(token));
            assertThat(result.brief().findings().getFirst().uncertainty()).isEqualTo(uncertainty);
        }
    }

    /** 목적: 미해결 요구 항목을 완료로 포장하지 못하고 구체적인 확인 질문을 보존한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 대역만. */
    @Test
    void requiresUnresolvedBriefToReturnNeedsInputAndActualQuestion() {
        token = gateway.grant(actor, snapshot(), null, "손익을 확인하고 개선 가능성을 검토해 줘");
        call("company_pnl", Map.of()); ObjectNode proposed = proposedBrief();
        var requirement = (ObjectNode) proposed.at("/brief/requirements/0");
        requirement.put("status", "NEEDS_INPUT").put("missingInformation", "조직이 확인한 개선 가능액과 반영 시점이 없습니다.")
                .put("followUpQuestion", "조직 담당자가 확인한 개선 가능액과 반영 시점을 알려 주세요.");
        assertThatThrownBy(() -> validator.validate(proposed, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
        proposed.put("status", "NEEDS_INPUT");
        assertThat(validator.validate(proposed, gateway.require(token)).brief().requirements().getFirst().status()).isEqualTo("NEEDS_INPUT");
        requirement.putNull("followUpQuestion");
        assertThatThrownBy(() -> validator.validate(proposed, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 목적: 보고 내용의 가짜 근거·요구 항목·결과 보장을 거부한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 대역만. */
    @Test
    void rejectsUnlinkedFindingsInventedRequirementsAndDirectNumericClaims() {
        token = gateway.grant(actor, snapshot(), null, "손익을 확인하고 개선 가능성을 검토해 줘");
        call("company_pnl", Map.of());
        for (String statement : List.of("비용을 35억 절감합니다.", "개선 효과를 보장합니다.", "목표는 반드시 달성됩니다.", "추진 승인을 완료했습니다.", "요청을 발송했습니다.")) {
            ObjectNode proposed = proposedBrief(); ((ObjectNode) proposed.at("/brief/findings/0")).put("text", statement);
            assertThatThrownBy(() -> validator.validate(proposed, gateway.require(token))).as(statement).isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode fakeRequirement = proposedBrief(); ((ObjectNode) fakeRequirement.at("/brief/requirements/0")).put("requestQuote", "원문에 없는 요청");
        assertThatThrownBy(() -> validator.validate(fakeRequirement, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
        ObjectNode fakeCitation = proposedBrief(); ((ObjectNode) fakeCitation.at("/brief/findings/0/citations/0")).put("quote", "새 근거");
        assertThatThrownBy(() -> validator.validate(fakeCitation, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
        ObjectNode missingReference = proposedBrief(); ((ObjectNode) missingReference.at("/brief/requirements/0")).putArray("findingIds").add("missing");
        assertThatThrownBy(() -> validator.validate(missingReference, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 목적: 실제 이름 필드 전체 인용만 숫자 이름 예외로 인정한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 대역만. */
    @Test
    void permitsOnlyFullyCitedNumericOrganizationNamesInBriefProse() {
        token = gateway.grant(actor, snapshot(), null, "손익을 확인해 줘");
        call("company_pnl", Map.of()); replaceFirstObservationData(Map.of("name", "사업부1", "note", "사업부2"));
        ObjectNode proposed = proposedBrief();
        var citation = mapper.createObjectNode().put("observationId", "obs-1").put("path", "/data/name").put("quote", "사업부1");
        proposed.putArray("citations").add(citation);
        var finding = (ObjectNode) proposed.at("/brief/findings/0"); finding.putArray("citations").add(citation.deepCopy()); finding.put("text", "사업부1의 계획 차이를 확인했습니다.");
        assertThat(validator.validate(proposed, gateway.require(token)).brief().findings().getFirst().text()).contains("사업부1");
        finding.put("text", "사업부2의 계획 차이를 확인했습니다.");
        assertThatThrownBy(() -> validator.validate(proposed, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
        citation.put("path", "/data/note").put("quote", "사업부2"); finding.putArray("citations").add(citation.deepCopy());
        assertThatThrownBy(() -> validator.validate(proposed, gateway.require(token))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 목적: 새 brief 필드가 없는 이전 계약은 기존 검증으로 계속 처리한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 대역만. */
    @Test
    void acceptsPreviousResponseWithoutBriefField() {
        call("company_pnl", Map.of()); ObjectNode proposed = proposed("-15000000000"); proposed.remove("brief");
        assertThat(validator.validate(proposed, gateway.require(token)).brief()).isNull();
    }

    /** 목적: 참조용 식별자의 AI·프론트 공통 문자열 계약을 한국어와 숫자 ID에도 적용한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 대역만. */
    @Test
    void acceptsSharedStringIdentifiersWithoutUnspecifiedAsciiRestriction() {
        token = gateway.grant(actor, snapshot(), null, "손익을 확인해 줘"); call("company_pnl", Map.of());
        ObjectNode proposed = proposedBrief();
        ((ObjectNode) proposed.at("/brief/findings/0")).put("id", "1");
        var requirement = (ObjectNode) proposed.at("/brief/requirements/0"); requirement.put("id", "요구항목-가"); requirement.putArray("findingIds").add("1");
        Response result = validator.validate(proposed, gateway.require(token));
        assertThat(result.brief().requirements().getFirst().id()).isEqualTo("요구항목-가");
        assertThat(result.brief().findings().getFirst().id()).isEqualTo("1");
    }

    /** 목적: 조회 근거가 없을 때도 사실을 만들지 않은 구체적인 확인 질문을 반환할 수 있다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 대역만. */
    @Test
    void acceptsClarificationWithoutInventingFindingsOrSuccessfulObservations() {
        token = gateway.grant(actor, snapshot(), null, "손익을 확인해 줘"); ObjectNode proposed = proposedBrief();
        String summary = "추가 비교 기준을 확인해야 합니다.";
        proposed.put("status", "NEEDS_INPUT").put("answer", summary); proposed.putArray("citations");
        var brief = (ObjectNode) proposed.get("brief"); brief.put("summary", summary); brief.putArray("findings");
        var requirement = (ObjectNode) proposed.at("/brief/requirements/0"); requirement.put("status", "NEEDS_INPUT")
                .put("missingInformation", "비교할 계획 기준을 선택하지 않았습니다.").put("followUpQuestion", "어느 계획 기준과 비교할까요?");
        requirement.putArray("findingIds");
        Response result = validator.validate(proposed, gateway.require(token));
        assertThat(result.brief().findings()).isEmpty(); assertThat(result.observations()).isEmpty(); assertThat(result.status()).isEqualTo("NEEDS_INPUT");
        verifyNoInteractions(home, research, analyses, workflow, discovery);
    }

    /** 목적: 상세 조회의 경로·위치·개수만으로 사실·요구 해결을 주장하지 못하고 실제 값 인용만 인정한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 대역만. */
    @Test
    void requiresDetailValueInsteadOfTransportMetadataForGroundedFinding() {
        token = gateway.grant(actor, snapshot(), null, "손익을 확인해 줘"); call("company_pnl", Map.of());
        Observation detail = call("observation_detail", Map.of("observationId", "obs-1", "path", "/data/kpis/0/actual"));
        for (String field : List.of("sourceObservationId", "path", "offset", "total", "nextOffset")) {
            ObjectNode proposed = proposedBrief();
            var citation = mapper.createObjectNode().put("observationId", detail.id()).put("path", "/data/" + field).put("quote", detail.data().get(field).asText());
            proposed.putArray("citations").add(citation);
            ((ObjectNode) proposed.at("/brief/findings/0")).putArray("citations").add(citation.deepCopy());
            assertThatThrownBy(() -> validator.validate(proposed, gateway.require(token))).as(field).isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode scalar = proposedBrief();
        var scalarCitation = mapper.createObjectNode().put("observationId", detail.id()).put("path", "/data/value").put("quote", detail.data().get("value").textValue());
        scalar.putArray("citations").add(scalarCitation);
        ((ObjectNode) scalar.at("/brief/findings/0")).putArray("citations").add(scalarCitation.deepCopy());
        assertThat(validator.validate(scalar, gateway.require(token)).brief().findings()).hasSize(1);
        Observation nested = call("observation_detail", Map.of("observationId", "obs-1", "path", "/data/kpis"));
        ObjectNode container = proposedBrief();
        var nestedCitation = mapper.createObjectNode().put("observationId", nested.id()).put("path", "/data/value/0/actual").put("quote", "-15000000000");
        container.putArray("citations").add(nestedCitation);
        ((ObjectNode) container.at("/brief/findings/0")).putArray("citations").add(nestedCitation.deepCopy());
        assertThat(validator.validate(container, gateway.require(token)).brief().findings()).hasSize(1);
    }

    /** 목적: 상세 조회는 현재 성공 원문을 페이지로 복사하고 업무 서비스를 다시 조회하지 않는다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 대역만. */
    @Test
    void pagesSuccessfulObservationWithoutRepeatingBusinessQuery() {
        call("company_pnl", Map.of()); replaceFirstObservationData(Map.of("items", List.of("가", "나", "다"), "text", "가😀".repeat(500)));
        var source = gateway.require(token).observations.getFirst().data().deepCopy();
        Observation first = call("observation_detail", Map.of("observationId", "obs-1", "path", "/data/items", "limit", 2));
        assertThat(first.status()).isEqualTo("SUCCESS"); assertThat(first.data().get("value")).hasSize(2);
        assertThat(first.data().get("nextOffset").intValue()).isEqualTo(2);
        Observation second = call("observation_detail", Map.of("observationId", "obs-1", "path", "/data/items", "offset", 2, "limit", 2));
        assertThat(second.data().get("value").get(0).textValue()).isEqualTo("다"); assertThat(second.data().get("nextOffset").isNull()).isTrue();
        Observation textPage = call("observation_detail", Map.of("observationId", "obs-1", "path", "/data/text", "offset", 399, "limit", 1));
        assertThat(textPage.data().get("value").textValue()).startsWith("😀"); assertThat(textPage.data().get("nextOffset").intValue()).isEqualTo(799);
        assertThat(gateway.require(token).observations.getFirst().data()).isEqualTo(source);
        verify(home, times(1)).pnl(actor, "YEAR", 2026, 11, 3L); verify(baselines, times(1)).load(3L);
    }

    /** 목적: 상세 조회로 다른 실행·실패·재귀 관찰이나 범위 밖 값을 읽지 못하게 한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 메모리 대역만. */
    @Test
    void rejectsUnavailableRecursiveAndOutOfRangeDetailSources() {
        call("company_pnl", Map.of());
        assertThat(call("observation_detail", Map.of("observationId", "obs-99", "path", "/data")).status()).isEqualTo("ERROR");
        assertThat(call("observation_detail", Map.of("observationId", "obs-2", "path", "/data")).status()).isEqualTo("ERROR");
        assertThat(call("observation_detail", Map.of("observationId", "obs-1", "path", "/data/missing")).status()).isEqualTo("ERROR");
        Observation detail = call("observation_detail", Map.of("observationId", "obs-1", "path", "/data"));
        assertThat(detail.status()).isEqualTo("SUCCESS");
        assertThat(call("observation_detail", Map.of("observationId", detail.id(), "path", "/data/value")).status()).isEqualTo("ERROR");
        assertThat(call("observation_detail", Map.of("observationId", "obs-1", "path", "/data/kpis", "offset", 999)).status()).isEqualTo("ERROR");
    }

    /** 목적: 상세 조회의 권한·포인터·범위 우회 입력을 실행 전에 거부한다. Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 없음. */
    @Test
    void rejectsDetailArgumentInjectionAndInvalidRanges() {
        for (String path : List.of("/basis", "https://example.invalid", "/data/~broken"))
            assertThatThrownBy(() -> tools.normalize("observation_detail", json.tree(Map.of("observationId", "obs-1", "path", path)))).isInstanceOf(IllegalArgumentException.class);
        for (Map<String, Object> input : List.<Map<String, Object>>of(
                Map.of("observationId", "obs-1", "path", "/data", "offset", -1),
                Map.of("observationId", "obs-1", "path", "/data", "offset", 10001),
                Map.of("observationId", "obs-1", "path", "/data", "limit", 11),
                Map.of("observationId", "obs-1", "path", "/data", "limit", "1"),
                Map.of("observationId", "obs-1", "path", "/data", "masterId", "7")))
            assertThatThrownBy(() -> tools.normalize("observation_detail", json.tree(input))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(home, research, analyses, workflow, discovery);
    }

    /** 목적: 보고서·소명 Tool을 공통 권한 원장과 캐시에 연결하고 모델의 기준 주입을 막는다.
     * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: mock·메모리만 사용. */
    @Test void routesReportAndExplanationApisWithoutAllowingModelScopeOrSql() {
        when(reports.templates(any(), any())).thenReturn(new DecisionAssistantTools.ToolValue(Map.of("items", List.of()), "저장 틀", List.of()));
        var first = call("report_templates", Map.of());
        assertThat(first.status()).isEqualTo("SUCCESS");
        assertThat(call("report_templates", Map.of("limit", 10))).isEqualTo(first);
        verify(reports, times(1)).templates(any(), eq(json.tree(Map.of("limit", 10))));
        when(explanationOverview.overview(any(), any())).thenReturn(new DecisionAssistantTools.ToolValue(Map.of("gapTotalWon", "0"), "소명 현황", List.of()));
        assertThat(call("explanation_overview", Map.of()).status()).isEqualTo("SUCCESS");
        verify(explanationOverview).overview(any(), eq(json.tree(Map.of("comparison", "ACTUAL_VS_PLAN", "offset", 0, "limit", 10))));
        assertThat(call("report_preview", Map.of("templateId", "1", "version", 1, "sql", "SELECT 1")).status()).isEqualTo("ERROR");
        assertThat(call("explanation_overview", Map.of("masterId", "99")).status()).isEqualTo("ERROR");
        verify(reports, never()).preview(any(), any());
    }

    /** 목적: 조회한 틀 버전·현재 실행 관찰과 유효 페이지 인자만 허용한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 없음. */
    @Test void requiresReportVersionAndValidExportObservationAndPageArguments() {
        assertThatThrownBy(() -> tools.normalize("report_preview", json.tree(Map.of("templateId", "1")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.normalize("report_preview", json.tree(Map.of("templateId", "1", "version", 1.5)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.normalize("report_export", json.tree(Map.of("previewObservationId", "https://example.test")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.normalize("explanation_overview", json.tree(Map.of("comparison", "FREE")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.normalize("explanation_overview", json.tree(Map.of("limit", 21)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(tools.normalize("report_export", json.tree(Map.of("previewObservationId", "obs-3"))).path("previewObservationId").asText()).isEqualTo("obs-3");
    }

    /** 목적: 검증 경계 테스트에 사용할 현재 원장 자료만 메모리에서 교체한다. Args: data 합성 JSON. Returns: 없음. Raises: 없음. Side Effects: 테스트 실행의 첫 관찰만 교체. */
    private void replaceFirstObservationData(Map<String, Object> data) {
        var run = gateway.require(token); Observation source = run.observations.getFirst();
        run.observations.set(0, new Observation(source.id(), source.tool(), source.label(), source.status(), source.basis(), json.tree(data), source.warnings()));
    }

    /** 목적: 실제 인용·현재 질문에 연결한 설명 샘플을 만든다. Args: 없음. Returns: 구조화 응답 JSON. Raises: 없음. Side Effects: 메모리만. */
    private ObjectNode proposedBrief() {
        ObjectNode proposed = proposed("-15000000000"); String summary = "손익 차이와 검토가 필요한 근거를 정리했습니다.";
        proposed.put("answer", summary);
        var brief = proposed.putObject("brief"); brief.put("summary", summary);
        var finding = brief.putArray("findings").addObject(); finding.put("id", "f1").put("kind", "FACT").put("text", "선택 기준의 손익 자료를 확인했습니다.").putNull("uncertainty");
        finding.putArray("citations").add(proposed.at("/citations/0").deepCopy());
        var requirement = brief.putArray("requirements").addObject(); requirement.put("id", "r1").put("requestQuote", "손익을 확인").put("question", "조직별 손익 차이를 확인합니다.").put("status", "ANSWERED")
                .putNull("missingInformation").putNull("followUpQuestion"); requirement.putArray("findingIds").add("f1");
        return proposed;
    }

    /** 목적: 선택 분석 테스트의 capability를 준비한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: mock·메모리만. */
    private void selectAnalysis() { when(analyses.get(7L, 5L, false)).thenReturn(analysis()); token = gateway.grant(actor, snapshot(), analysis()); }
    /** 목적: 실제 도구 호출을 간결하게 구성한다. Args: name 도구, args 인자. Returns: 관찰. Raises: 조회 오류. Side Effects: 메모리 원장만. */
    private Observation call(String name, Map<String, Object> args) { return gateway.call(token, request(name, args)); }
    /** 목적: JSON 인자 DTO를 만든다. Args: name 도구, args 인자. Returns: 요청. Raises: 없음. Side Effects: 없음. */
    private ToolRequest request(String name, Map<String, Object> args) { return new ToolRequest(name, json.tree(args)); }
    /** 목적: 고정 출처·금액 샘플을 메모리에 만든다. Args: 없음. Returns: 기준. Raises: 없음. Side Effects: 없음. */
    private Snapshot snapshot() { return new Snapshot("3", "11월 기준", 2026, 11, now, List.of("20"), List.of(new Organization("1", "A", "A사업부", "100000000000", "8000000000", "92000000000")), "92000000000"); }
    /** 목적: 저장된 분석 샘플을 메모리에 만든다. Args: 없음. Returns: 분석. Raises: 없음. Side Effects: 없음. */
    private Analysis analysis() { return new Analysis("5", "목표 검토", "검토 요청", 2, "DRAFT", now, now, "105000000000", List.of(), snapshot(), List.of(new Candidate("c1", "1", "납품 정상화", EffectType.NET_PROFIT, "3500000000", null, "0", "0", 12, Category.REVENUE, "", EvidenceStatus.ASSUMPTION, "", "")), null, null); }
    /** 목적: 실제 원장에 대응하는 모델 종료 샘플을 만든다. Args: quote 인용. Returns: 모델 JSON. Raises: 없음. Side Effects: 없음. */
    private ObjectNode proposed(String quote) {
        var run = gateway.require(token);
        return (ObjectNode) json.tree(new Response("MODEL", "COMPLETED", "관찰된 손익 차이를 확인하고 관련 조직의 근거를 추가로 검토해 주세요.", run.context,
                run.calls(), List.copyOf(run.observations), List.of(new Citation("obs-1", "/data/kpis/0/actual", quote)), List.of("OPEN_DISCOVERY"), List.of()));
    }
}
