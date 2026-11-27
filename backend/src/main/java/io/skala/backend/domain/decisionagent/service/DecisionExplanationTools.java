package io.skala.backend.domain.decisionagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.skala.backend.domain.report.dto.ExplanationCategoryResponse;
import io.skala.backend.domain.report.dto.ReportExplanationResponse;
import io.skala.backend.domain.report.service.ReportExplanationService;
import io.skala.backend.domain.review.service.GapRules;
import io.skala.backend.domain.review.value.GapComparisonType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 기존 보고서 소명 API의 전사 집계와 페이지 상세를 같은 기준의 Agent 관찰로 제공한다. */
@Service
@RequiredArgsConstructor
public class DecisionExplanationTools {
    private final ReportExplanationService explanations;
    private final DecisionReportBasisGuard basisGuard;
    private final DecisionAssistantJson json;

    /** 목적: 전사 소명 분류·수락 현황과 선택 조직의 상세 페이지를 읽는다.
     * Args: run 권한·고정 기준, args 정규 비교·조직·페이지 인자.
     * Returns: 기존 API 금액을 재계산하지 않은 전사 집계·소명·누락 정보.
     * Raises: ToolFailure 원천 변경·범위 밖 조직·잘못된 응답, 기존 조회 오류.
     * Side Effects: DB 읽기만. 분류 저장·회신·승인·추가 LLM 호출 없음. */
    public DecisionAssistantTools.ToolValue overview(DecisionAssistantGateway.Run run, JsonNode args) {
        var before = basisGuard.check(run.snapshot);
        var comparison = GapComparisonType.valueOf(args.path("comparison").asText());
        var itemPeriod = ReportExplanationService.itemMonthOf(run.snapshot.year(), run.snapshot.baseMonth(), comparison);
        var response = explanations.explanations(run.actor, run.snapshot.year(), run.snapshot.baseMonth(), comparison);
        var categories = explanations.categories(run.actor, null);
        if (!before.equals(basisGuard.check(run.snapshot)))
            throw new DecisionAssistantTools.ToolFailure("조회 중 보고서 원천이 변경되었습니다. 기준을 확인하고 다시 조회해 주세요.");
        Set<String> allowed = run.snapshot.organizations().stream().map(item -> item.id()).collect(Collectors.toSet());
        if (response.items().stream().anyMatch(item -> !allowed.contains(item.organization().id()))
                || response.reviewing().stream().anyMatch(item -> !allowed.contains(item.organization().id())))
            throw new DecisionAssistantTools.ToolFailure("소명 원천의 조직 범위가 선택한 마스터와 다릅니다.");
        String organizationId = args.has("organizationId") ? args.get("organizationId").textValue() : null;
        if (organizationId != null && !allowed.contains(organizationId))
            throw new DecisionAssistantTools.ToolFailure("선택한 마스터에 포함된 조직만 조회할 수 있습니다.");
        var items = response.items().stream().filter(item -> organizationId == null || organizationId.equals(item.organization().id())).toList();
        var reviewing = response.reviewing().stream().filter(item -> organizationId == null || organizationId.equals(item.organization().id())).toList();
        int offset = args.path("offset").asInt(), limit = args.path("limit").asInt();
        int available = Math.max(items.size(), reviewing.size());
        if (offset > available && available != 0)
            throw new DecisionAssistantTools.ToolFailure("소명 상세 페이지 범위를 벗어났습니다. 첫 페이지부터 다시 확인해 주세요.");
        // List 크기 범위의 건수를 int로 유지해야 HTTP JSON 왕복 후에도 관찰 원장의 노드 타입이 같다.
        int unclassified = Math.toIntExact(response.items().stream().filter(item -> item.categoryId() == null).count());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("masterId", run.snapshot.masterId()); data.put("sourceVersion", before.version());
        data.put("year", run.snapshot.year()); data.put("month", run.snapshot.baseMonth());
        data.put("period", "MONTH"); data.put("unit", "KRW"); data.put("comparison", comparison.name());
        data.put("itemYear", itemPeriod == null ? null : itemPeriod[0]);
        data.put("itemMonth", itemPeriod == null ? null : itemPeriod[1]);
        data.put("itemScenario", GapRules.itemScenarioOf(comparison).name());
        data.put("hasApplicablePeriod", itemPeriod != null);
        data.put("scope", "ALL_MASTER_ORGANIZATIONS"); data.put("selectedOrganizationId", organizationId);
        data.put("summary", Map.of("acceptedCount", response.items().size(), "reviewingCount", response.reviewing().size(),
                "unclassifiedAcceptedCount", unclassified, "fullyClassified", itemPeriod != null && unclassified == 0 && response.reviewing().isEmpty() && response.matchesGapTotal()));
        data.put("gapTotalWon", response.gapTotal()); data.put("classifiedTotalWon", response.classifiedTotal());
        data.put("matchesGapTotal", response.matchesGapTotal()); data.put("categoryTotals", totals(response, categories));
        data.put("categories", categories); data.put("acceptedCount", items.size()); data.put("reviewingCount", reviewing.size());
        data.put("items", items.stream().skip(offset).limit(limit).toList());
        data.put("reviewing", reviewing.stream().skip(offset).limit(limit).toList());
        data.put("offset", offset); data.put("limit", limit); data.put("hasMore", offset + limit < available);
        data.put("nextOffset", offset + limit < available ? offset + limit : null);
        return new DecisionAssistantTools.ToolValue(json.safe(data),
                "고정 마스터와 일치하는 보고서 원천의 선택 비교 기준·조회 시점 소명과 분류",
                List.of("전사 집계는 summary·categoryTotals·gapTotalWon·classifiedTotalWon입니다. 조직 선택은 items·reviewing 상세와 그 건수에만 적용됩니다.",
                        "Gap 합계는 항목의 원래 부호를 합한 비교 차액입니다. 영업이익 영향·추가 개선 가능액이 아니며 양수·음수 상쇄가 가능합니다.",
                        "categoryTotals의 subtotal=true는 하위 분류 합계입니다. 상위·하위 금액을 다시 합산하면 중복됩니다. 미분류 소명은 기존 API의 기타 합계에 포함될 수 있습니다.",
                        "실적 비교는 기준월, 추정 비교는 다음 월만 대상으로 합니다. 12월 기준 추정에는 대상 기간이 없으며 이를 소명 완료로 판단하지 않습니다.",
                        "matchesGapTotal만으로 분류 완료를 판단하지 않습니다. 미분류 수락 소명과 수락 소명 없는 항목도 확인해야 합니다.",
                        "reviewing은 수락된 소명이 없는 항목입니다. 아직 요청하지 않았거나 검토 중일 수 있으므로 미회신으로 단정하지 않습니다.",
                        "수락 소명은 담당자의 설명이며 인과 원인·실현 가능한 개선 효과를 독립적으로 증명하지 않습니다. 다기간 반복 원인·위험 추세는 이 조회만으로 확인할 수 없습니다.",
                        "상세는 offset·limit에 따라 페이지로 제공합니다. hasMore이면 나머지 자료를 조회하기 전 전체 원인을 확인했다고 표현하지 않습니다."));
    }

    /** 목적: 기존 API의 분류별 원 금액에 현재 분류명을 연결한다.
     * Args: response 소명 응답, categories 분류표 응답.
     * Returns: 값의 합산·부호 변환 없이 이름을 붙인 목록.
     * Raises: 없음. 알려지지 않은 분류는 식별자를 유지한다.
     * Side Effects: 없음. */
    private List<Map<String, Object>> totals(ReportExplanationResponse response, ExplanationCategoryResponse categories) {
        Map<String, String> names = categories.items().stream().collect(Collectors.toMap(ExplanationCategoryResponse.Item::id,
                ExplanationCategoryResponse.Item::name, (first, second) -> first));
        Set<String> subtotals = categories.items().stream().filter(item -> !item.assignable())
                .map(ExplanationCategoryResponse.Item::id).collect(Collectors.toSet());
        return response.categoryTotals().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.<String, Object>of("categoryId", entry.getKey(), "name", names.getOrDefault(entry.getKey(), "미확인 분류"),
                        "gapAmountWon", Objects.requireNonNull(entry.getValue()), "subtotal", subtotals.contains(entry.getKey()))).toList();
    }
}
