package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.report.dto.ExplanationCategoryResponse;
import io.skala.backend.domain.report.dto.ReportExplanationResponse;
import io.skala.backend.domain.report.dto.ReportExplanationResponse.*;
import io.skala.backend.domain.report.service.ReportExplanationService;
import io.skala.backend.domain.review.value.GapComparisonType;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 실제 DB 없이 기존 소명 API의 집계 범위·분류 누락·페이지·원천 변경을 검증한다. */
class DecisionExplanationToolsTest {
    private final ReportExplanationService service = mock(ReportExplanationService.class);
    private final DecisionReportBasisGuard guard = mock(DecisionReportBasisGuard.class);
    private final DecisionAssistantJson json = new DecisionAssistantJson(new ObjectMapper());
    private final DecisionExplanationTools tools = new DecisionExplanationTools(service, guard, json);
    private final AuthenticatedUser actor = new AuthenticatedUser(7L, UserRole.PLANNER, null);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-27T00:00:00Z");

    /** 목적: 미분류·수락 없는 항목이 상쇄돼 금액이 같아도 완료 판정을 막는다.
     * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 메모리 대역만 사용. */
    @Test void distinguishesUnclassifiedAndReviewingEvenWhenSignedTotalsMatch() {
        var run = setup(11, new ReportExplanationResponse("0", List.of(card("11", "1", null)),
                List.of(new Reviewing(org("2"), "비용", "0")), Map.of("9", "0", "10", "0"), "0", true));
        JsonNode data = data(run, Map.of("comparison", "ACTUAL_VS_PLAN", "offset", 0, "limit", 10));
        assertThat(data.at("/summary/unclassifiedAcceptedCount").asInt()).isEqualTo(1);
        assertThat(data.at("/summary/reviewingCount").asInt()).isEqualTo(1);
        assertThat(data.path("matchesGapTotal").asBoolean()).isTrue();
        assertThat(data.at("/summary/fullyClassified").asBoolean()).isFalse();
        assertThat(data.path("categoryTotals").findValuesAsText("gapAmountWon")).containsOnly("0");
        assertThat(data.path("categoryTotals").findValues("subtotal").stream().anyMatch(JsonNode::asBoolean)).isTrue();
        verify(service).explanations(actor, 2026, 11, GapComparisonType.ACTUAL_VS_PLAN);
        verify(service).categories(actor, null);
        verifyNoMoreInteractions(service);
    }

    /** 목적: 전사 집계는 유지하고 조직 상세만 필터·페이지 처리하며 원문 답변을 바꾸지 않는다.
     * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 메모리만 사용. */
    @Test void preservesGlobalTotalsAndPagesOnlySelectedOrganizationDetails() {
        var run = setup(11, new ReportExplanationResponse("300", List.of(card("11", "1", "10"), card("12", "2", "10"), card("13", "1", "10")),
                List.of(), Map.of("10", "300"), "300", true));
        JsonNode data = data(run, Map.of("comparison", "ACTUAL_VS_PLAN", "organizationId", "1", "offset", 0, "limit", 1));
        assertThat(data.at("/summary/acceptedCount").asInt()).isEqualTo(3);
        assertThat(data.path("acceptedCount").asInt()).isEqualTo(2);
        assertThat(data.path("gapTotalWon").asText()).isEqualTo("300");
        assertThat(data.at("/items/0/answer").asText()).isEqualTo("집행 시점 조정");
        assertThat(data.path("hasMore").asBoolean()).isTrue();
        assertThat(data.path("nextOffset").asInt()).isEqualTo(1);
        var second = data(run, Map.of("comparison", "ACTUAL_VS_PLAN", "organizationId", "1", "offset", 1, "limit", 1));
        assertThat(second.at("/items/0/explanationId").asText()).isEqualTo("13");
        assertThat(second.path("hasMore").asBoolean()).isFalse();
    }

    /** 목적: 조회 중 마스터·근거가 달라지면 비교 결과를 채택하지 않는다.
     * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 대역만 사용. */
    @Test void rejectsChangedSourceAndOrganizationsOutsideMaster() {
        var run = setup(11, empty());
        when(guard.check(run.snapshot)).thenReturn(new DecisionReportBasisGuard.SourceBasis("master-2026-11", "before"),
                new DecisionReportBasisGuard.SourceBasis("master-2026-11", "after"));
        assertThatThrownBy(() -> data(run, Map.of("comparison", "ACTUAL_VS_PLAN", "offset", 0, "limit", 10)))
                .isInstanceOf(DecisionAssistantTools.ToolFailure.class).hasMessageContaining("변경");
        var outside = setup(11, new ReportExplanationResponse("0", List.of(card("11", "99", "10")), List.of(), Map.of(), "0", true));
        assertThatThrownBy(() -> data(outside, Map.of("comparison", "ACTUAL_VS_PLAN", "offset", 0, "limit", 10)))
                .isInstanceOf(DecisionAssistantTools.ToolFailure.class).hasMessageContaining("조직");
    }

    /** 목적: 기존 추정 소명 API의 다음 월 규칙과 12월 대상 없음 상태를 표시한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError. Side Effects: 메모리 대역만 사용. */
    @Test void exposesEstimateTargetMonthAndDecemberAbsence() {
        var november = setup(11, empty());
        JsonNode data = data(november, Map.of("comparison", "ESTIMATE_VS_PREVIOUS", "offset", 0, "limit", 10));
        assertThat(data.path("itemMonth").asInt()).isEqualTo(12);
        assertThat(data.path("itemScenario").asText()).isEqualTo("ESTIMATE");
        var december = setup(12, empty());
        JsonNode last = data(december, Map.of("comparison", "ESTIMATE_VS_PREVIOUS", "offset", 0, "limit", 10));
        assertThat(last.path("itemMonth").isNull()).isTrue();
        assertThat(last.path("hasApplicablePeriod").asBoolean()).isFalse();
        assertThat(last.at("/summary/fullyClassified").asBoolean()).isFalse();
    }

    /** 목적: HTTP JSON 왕복 뒤에도 서버 관찰의 정수 타입·값이 정확히 일치하는지 확인한다.
     * Args: 없음. Returns: 없음. Raises: JSON 처리 오류·AssertionError.
     * Side Effects: 모의 조회와 메모리 직렬화만. 실제 DB·HTTP·파일 접근 없음. */
    @Test void preservesExactLedgerJsonAfterHttpSerialization() throws Exception {
        ObjectMapper transport = new ObjectMapper();
        for (var response : List.of(empty(), new ReportExplanationResponse("100", List.of(card("11", "1", null)),
                List.of(), Map.of("10", "100"), "0", false))) {
            var run = setup(11, response);
            JsonNode original = data(run, Map.of("comparison", "ACTUAL_VS_PLAN", "offset", 0, "limit", 10));
            JsonNode received = transport.readTree(transport.writeValueAsBytes(original));
            assertThat(received).isEqualTo(original);
            assertThat(received.at("/summary/unclassifiedAcceptedCount").isInt()).isTrue();
        }
    }

    /** 목적: 고정 마스터·분류표와 읽기 응답 대역을 설정한다.
     * Args: month 기준 월, response 기존 API 응답. Returns: 실행 문맥.
     * Raises: 없음. Side Effects: Mockito 메모리 설정만 변경. */
    private DecisionAssistantGateway.Run setup(int month, ReportExplanationResponse response) {
        Snapshot snapshot = new Snapshot("3", "고정 기준", 2026, month, now, List.of("20", "21"), List.of(
                new Organization("1", "A", "A조직", "100", "50", "50"), new Organization("2", "B", "B조직", "100", "50", "50")), "100");
        when(guard.check(snapshot)).thenReturn(new DecisionReportBasisGuard.SourceBasis("master-2026-" + month, "same"));
        when(service.explanations(eq(actor), eq(2026), eq(month), any())).thenReturn(response);
        when(service.categories(actor, null)).thenReturn(new ExplanationCategoryResponse(1, List.of(
                new ExplanationCategoryResponse.Item("9", 9, "상위", null, false, false),
                new ExplanationCategoryResponse.Item("10", 10, "시점", 9, false, true))));
        return new DecisionAssistantGateway.Run(actor, snapshot, null, now.toInstant().plusSeconds(180), "소명 현황");
    }

    /** 목적: 조회 결과의 자료 JSON을 가져온다. Args: run 문맥, args 도구 인자. Returns: 자료.
     * Raises: 도구 오류. Side Effects: 모의 서비스 조회만 수행. */
    private JsonNode data(DecisionAssistantGateway.Run run, Map<String, Object> args) { return json.tree(tools.overview(run, json.tree(args)).data()); }
    /** 목적: 빈 API 응답을 만든다. Args: 없음. Returns: 빈 자료. Raises: 없음. Side Effects: 없음. */
    private ReportExplanationResponse empty() { return new ReportExplanationResponse("0", List.of(), List.of(), Map.of(), "0", true); }
    /** 목적: 합성 조직 참조를 만든다. Args: id 식별자. Returns: 조직. Raises: 없음. Side Effects: 없음. */
    private OrganizationRef org(String id) { return new OrganizationRef(id, "ORG" + id, "조직" + id); }
    /** 목적: 근거 원문을 가진 합성 카드를 만든다. Args: id 소명, org 조직, category 분류. Returns: 카드. Raises: 없음. Side Effects: 없음. */
    private Card card(String id, String org, String category) { return new Card(id, org(org), "비용", "100", "집행 시점 조정", category, null, null); }
}
