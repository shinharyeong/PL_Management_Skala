package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.skala.backend.domain.dashboard.dto.HomeDivisionsResponse;
import io.skala.backend.domain.dashboard.dto.OrganizationRef;
import io.skala.backend.domain.dashboard.service.ArchiveQueryService;
import io.skala.backend.domain.dashboard.service.HomePnlQueryService;
import io.skala.backend.domain.decisionworkflow.service.DecisionWorkflowService;
import io.skala.backend.domain.review.service.VersionComparisonService;
import io.skala.backend.domain.submission.repository.SubmissionRepository;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 허용된 읽기 도구를 기존 업무 서비스에 연결한다. 새로운 집계 SQL이나 상태 변경 경로는 없다. */
@Service
@RequiredArgsConstructor
public class DecisionAssistantTools {
    private static final String LIVE_COMPARISON = "현재 전망은 고정 마스터, 계획·전월 비교값과 소명은 조회 시점 기준";
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("analysis_context", "분석 기준 조회"), Map.entry("company_pnl", "전사 손익 조회"),
            Map.entry("profit_drivers", "계획 대비 손익 차이 조회"), Map.entry("organization_pnl", "조직 손익 조회"),
            Map.entry("pnl_waterfall", "손익 워터폴 조회"), Map.entry("approved_explanations", "승인 소명 조회"),
            Map.entry("submission_versions", "승인 제출 버전 조회"), Map.entry("submission_comparison", "제출 버전 비교"),
            Map.entry("scenario_preview", "시나리오 미리보기"), Map.entry("goal_preview", "저장하지 않는 목표 검토"), Map.entry("work_items", "검토·실행 상태 조회"),
            Map.entry("review_request_draft", "조직 확인 요청 초안"), Map.entry("observation_detail", "조회 근거 상세 확인"),
            Map.entry("monthly_pnl", "기준월 손익 조회"), Map.entry("report_item_breakdown", "조직별 손익 항목 상세"));
    private final HomePnlQueryService home;
    private final ArchiveQueryService archive;
    private final VersionComparisonService comparison;
    private final DecisionScenarioCalculator calculator;
    private final DecisionWorkflowService workflow;
    private final DecisionResearchStore research;
    private final DecisionDiscoveryService discovery;
    private final DecisionBaselineLoader baselines;
    private final DecisionAnalysisStore analyses;
    private final SubmissionRepository submissions;
    private final AppUserRepository users;
    private final DecisionAssistantJson json;

    /** 목적: 공개 호출 이름을 고정 표시명으로 매핑한다. Args: tool 도구 이름. Returns: 표시명. Raises: 없음. Side Effects: 없음. */
    public String label(String tool) { return LABELS.getOrDefault(tool, "허용되지 않은 조회"); }

    /** 목적: 역할 철회·탈퇴·분석 수정 후 capability 재사용을 차단한다. Args: run 고정 실행. Returns: 없음. Raises: 403 권한, 404 소유권, 409 버전. Side Effects: DB 읽기만. */
    @Transactional(readOnly = true)
    public void assertCurrent(DecisionAssistantGateway.Run run) {
        run.actor.requireRole(UserRole.PLANNER);
        var user = users.findById(run.actor.id()).orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        if (!Boolean.TRUE.equals(user.getActive()) || user.getRole() != UserRole.PLANNER) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (run.analysis != null) {
            Analysis current = analyses.get(run.actor.id(), Long.parseLong(run.analysis.id()), false);
            if (current.revision() != run.analysis.revision() || !current.snapshot().masterId().equals(run.snapshot.masterId()))
                throw new BusinessException(ErrorCode.RESOURCE_CHANGED);
        }
    }

    /** 목적: 모델 인자의 허용 목록·유형·기본값을 정규화한다. Args: tool 도구, input 인자. Returns: 결정적 정규 인자. Raises: IllegalArgumentException 금지/범위 오류. Side Effects: 없음. */
    public JsonNode normalize(String tool, JsonNode input) {
        if (!LABELS.containsKey(tool) || input == null || !input.isObject() || json.size(input) > 16384)
            throw new IllegalArgumentException("허용 인자 형식이 아닙니다.");
        Set<String> allowed = switch (tool) {
            case "profit_drivers" -> Set.of("limit");
            case "organization_pnl", "submission_versions", "monthly_pnl" -> Set.of("organizationId");
            case "report_item_breakdown" -> Set.of("organizationId", "reportItemId", "limit");
            case "approved_explanations" -> Set.of("organizationId", "limit");
            case "submission_comparison" -> Set.of("organizationId", "baseSubmissionId");
            case "scenario_preview" -> Set.of("candidateIds");
            case "goal_preview" -> Set.of("targetProfitEok", "targetYear");
            case "review_request_draft" -> Set.of("sourceKind", "sourceId");
            case "observation_detail" -> Set.of("observationId", "path", "offset", "limit");
            default -> Set.of();
        };
        input.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field) || input.get(field).isNull()) throw new IllegalArgumentException("허용하지 않은 인자입니다."); });
        ObjectNode args = (ObjectNode) input.deepCopy();
        if (tool.equals("observation_detail")) {
            identifier(args.get("observationId"), false);
            JsonNode path = args.get("path");
            if (!args.get("observationId").textValue().matches("obs-[1-9][0-9]*") || path == null || !path.isTextual()
                    || path.textValue().length() > 1000 || !(path.textValue().equals("/data") || path.textValue().startsWith("/data/"))
                    || java.util.regex.Pattern.compile("~(?![01])").matcher(path.textValue()).find())
                throw new IllegalArgumentException("현재 관찰의 자료 경로만 조회할 수 있습니다.");
            boundedInteger(args, "offset", 0, 0, 10000);
            boundedInteger(args, "limit", 5, 1, 10);
        }
        if (tool.equals("goal_preview")) {
            JsonNode targetYear = args.get("targetYear");
            if (targetYear == null || !targetYear.isIntegralNumber() || !targetYear.canConvertToInt()
                    || targetYear.intValue() < 2000 || targetYear.intValue() > 9999)
                throw new IllegalArgumentException("목표 연도는 2000~9999의 정수로 명시해야 합니다.");
            JsonNode target = args.get("targetProfitEok");
            if (target == null || !target.isTextual() || !target.textValue().matches("(?:0|[1-9][0-9]{0,15})(?:\\.[0-9]{1,8})?")
                    || new BigDecimal(target.textValue()).signum() <= 0) throw new IllegalArgumentException("목표는 정수부 16자리·소수부 8자리 이내 양수 억원 문자열이어야 합니다.");
            args.put("targetProfitEok", DecisionScenarioCalculator.decimal(new BigDecimal(target.textValue())));
        }
        if (args.has("organizationId")) identifier(args.get("organizationId"), true);
        if (tool.equals("report_item_breakdown")) {
            if (args.has("reportItemId")) identifier(args.get("reportItemId"), true);
            boundedInteger(args, "limit", 20, 1, 50);
        }
        if (tool.equals("submission_versions") || tool.equals("submission_comparison")) identifier(args.get("organizationId"), true);
        if (tool.equals("submission_comparison")) identifier(args.get("baseSubmissionId"), true);
        if (tool.equals("profit_drivers") || tool.equals("approved_explanations")) {
            int fallback = tool.equals("profit_drivers") ? 5 : 10;
            int max = tool.equals("profit_drivers") ? 10 : 20;
            if (args.has("limit") && (!args.get("limit").isIntegralNumber() || !args.get("limit").canConvertToInt()
                    || args.get("limit").intValue() < 1 || args.get("limit").intValue() > max)) throw new IllegalArgumentException("조회 상한이 잘못되었습니다.");
            if (!args.has("limit")) args.put("limit", fallback);
        }
        if (args.has("candidateIds")) {
            JsonNode ids = args.get("candidateIds");
            if (!ids.isArray() || ids.size() > 100) throw new IllegalArgumentException("후보 범위가 잘못되었습니다.");
            TreeSet<String> selected = new TreeSet<>();
            ids.forEach(item -> { identifier(item, false); if (!selected.add(item.textValue())) throw new IllegalArgumentException("중복 후보입니다."); });
            var normalized = args.putArray("candidateIds"); selected.forEach(normalized::add);
        }
        if (tool.equals("review_request_draft")) {
            identifier(args.get("sourceId"), false);
            if (!Set.of("candidate", "hypothesis").contains(args.path("sourceKind").asText())) throw new IllegalArgumentException("초안 원천이 잘못되었습니다.");
        }
        return json.canonical(args);
    }

    /** 목적: 권한·목표 연도 일치 확인 후 기존 업무 조회 또는 현재 원장 상세 읽기를 실행한다. Args: run 사용자·기준, tool 허용 이름, args 정규 인자. Returns: 조회 자료와 비교 한계. Raises: ToolFailure 목표·기준 연도 불일치 또는 업무/조회 오류. Side Effects: DB 읽기만; 목표 연도가 다르면 원천 재조회·계산을 하지 않으며 상세 읽기도 업무 자료 재조회·저장·발송 없음. */
    @Transactional(readOnly = true)
    public ToolValue execute(DecisionAssistantGateway.Run run, String tool, JsonNode args) {
        assertCurrent(run);
        if (tool.equals("observation_detail")) return observationDetail(run, args);
        if (tool.equals("goal_preview") && args.get("targetYear").intValue() != run.snapshot.year())
            throw new ToolFailure("목표 연도 " + args.get("targetYear").intValue() + "년과 선택한 기준 " + run.snapshot.year()
                    + "년이 다릅니다. 목표 연도에 맞는 마스터를 선택하거나 목표 연도를 확인해 주세요.");
        research.assertSameAmounts(run.snapshot, baselines.load(Long.parseLong(run.snapshot.masterId())));
        String org = args.has("organizationId") ? args.get("organizationId").textValue() : null;
        if (org != null && run.snapshot.organizations().stream().noneMatch(item -> item.id().equals(org)))
            throw new ToolFailure("선택한 마스터에 포함된 조직만 조회할 수 있습니다.");
        long master = Long.parseLong(run.snapshot.masterId()); int year = run.snapshot.year(); int month = run.snapshot.baseMonth();
        return switch (tool) {
            case "analysis_context" -> value(contextData(run), "고정 마스터와 선택 분석 revision의 저장 값", List.of("저장 후보의 가정은 개선 효과가 실현되었다는 뜻이 아닙니다."));
            case "company_pnl" -> value(company(run, "YEAR"), LIVE_COMPARISON, List.of("계획·전월 기준과 Gap 소명은 조회 시점 값이며 고정 마스터 발행 시점 자료와 다를 수 있습니다."));
            case "profit_drivers" -> value(home.opDrivers(run.actor, "YEAR", year, month, master, args.get("limit").intValue()), LIVE_COMPARISON,
                    List.of("계획 대비 영업이익 차이 순위이며 변동의 인과 원인이나 검증된 개선 가능액이 아닙니다."));
            case "organization_pnl" -> value(divisions(run, org, "YEAR"), LIVE_COMPARISON, List.of("total은 전체 조직 합계이며 선택 조직 합계가 아닙니다.", "조직별 계획 차이는 영업이익 기준입니다. 매출·비용의 조직별 계획 차이는 기존 조회가 제공하지 않아 추정하지 않습니다."));
            case "monthly_pnl" -> monthly(run, org);
            case "report_item_breakdown" -> reportItems(run, org, args);
            case "pnl_waterfall" -> value(home.waterfall(run.actor, "YEAR", year, month, master), "고정 마스터 원천의 연간 전망 구성", List.of());
            case "approved_explanations" -> explanations(run, org, args.get("limit").intValue());
            case "submission_versions" -> versions(run, org);
            case "submission_comparison" -> compare(run, org, args.get("baseSubmissionId").textValue());
            case "scenario_preview" -> scenario(run, args);
            case "goal_preview" -> goalPreview(run, args.get("targetProfitEok").textValue());
            case "work_items" -> workItems(run);
            case "review_request_draft" -> draft(run, args);
            default -> throw new ToolFailure("허용되지 않은 도구입니다.");
        };
    }

    /** 목적: 현재 실행 원장의 성공 관찰을 페이지 단위로 읽는다. Args: run 실행 원장, args 검증한 관찰·경로·범위. Returns: 원문 부분과 다음 위치. Raises: ToolFailure 원천·경로·범위 오류. Side Effects: 없음; 업무 DB 재조회·수정과 원장 변형을 하지 않는다. */
    private ToolValue observationDetail(DecisionAssistantGateway.Run run, JsonNode args) {
        var source = run.observations.stream().filter(item -> item.id().equals(args.get("observationId").textValue())
                && item.status().equals("SUCCESS") && !item.tool().equals("observation_detail")).findFirst()
                .orElseThrow(() -> new ToolFailure("이번 실행에서 성공한 원본 조회를 선택해 주세요."));
        JsonNode original;
        try { original = json.tree(source).at(args.get("path").textValue()); }
        catch (IllegalArgumentException exception) { throw new ToolFailure("조회 자료의 상세 경로가 올바르지 않습니다."); }
        if (original.isMissingNode()) throw new ToolFailure("해당 상세 경로가 조회 자료에 없습니다.");
        int offset = args.get("offset").intValue(), limit = args.get("limit").intValue();
        int total = original.isTextual() ? original.textValue().codePointCount(0, original.textValue().length()) : original.isContainerNode() ? original.size() : 1;
        if (offset > total || (!original.isContainerNode() && !original.isTextual() && offset != 0))
            throw new ToolFailure("상세 조회 시작 위치가 자료 범위를 벗어났습니다.");
        int end = Math.min(total, offset + (original.isTextual() ? limit * 400 : limit));
        ObjectNode result = (ObjectNode) json.tree(new LinkedHashMap<>());
        result.put("sourceObservationId", source.id()); result.put("path", args.get("path").textValue());
        result.put("offset", offset); result.put("total", total);
        if (end < total) result.put("nextOffset", end); else result.putNull("nextOffset");
        if (original.isArray()) {
            var page = result.putArray("value");
            for (int index = offset; index < end; index++) page.add(original.get(index).deepCopy());
        } else if (original.isObject()) {
            var page = result.putObject("value"); var fields = original.fields(); int index = 0;
            while (fields.hasNext()) { var field = fields.next(); if (index >= offset && index < end) page.set(field.getKey(), field.getValue().deepCopy()); index++; }
        } else if (original.isTextual()) {
            String text = original.textValue();
            result.put("value", text.substring(text.offsetByCodePoints(0, offset), text.offsetByCodePoints(0, end)));
        } else result.set("value", original.deepCopy());
        return value(result, source.basis().comparisonBasis(), source.warnings());
    }

    /** 목적: 상세 조회의 정수 범위와 기본값을 검사한다. Args: args 사본, field 필드, fallback 기본값, min 최소, max 최대. Returns: 없음. Raises: IllegalArgumentException 형식·범위 오류. Side Effects: args 사본에 기본값만 설정. */
    private void boundedInteger(ObjectNode args, String field, int fallback, int min, int max) {
        if (args.has(field) && (!args.get(field).isIntegralNumber() || !args.get(field).canConvertToInt()
                || args.get(field).intValue() < min || args.get(field).intValue() > max))
            throw new IllegalArgumentException("상세 조회 범위가 올바르지 않습니다.");
        if (!args.has(field)) args.put(field, fallback);
    }

    /** 목적: 고정 전망과 선택 분석의 입력·결과를 읽는다. Args: run 실행. Returns: 제한된 분석 JSON. Raises: 변환 실패. Side Effects: 없음. */
    private JsonNode contextData(DecisionAssistantGateway.Run run) {
        ObjectNode result = json.project(Map.of("snapshot", run.snapshot), "snapshot");
        result.set("analysis", run.analysis == null ? json.tree(null) : json.project(run.analysis,
                "id", "title", "revision", "targetProfitWon", "protectedCategories", "candidates", "result"));
        return result;
    }

    /** 목적: 기존 전사 손익 원문을 보존하면서 핵심 지표의 방향·유불리를 정밀 원값으로 요약한다. Args: run 고정 실행, period 서버가 선택한 YEAR 또는 MONTH. Returns: 기존 전사 손익 응답과 정성 판정. Raises: 기존 조회/금액 형식 오류. Side Effects: DB 읽기만. */
    private JsonNode company(DecisionAssistantGateway.Run run, String period) {
        var response = home.pnl(run.actor, period, run.snapshot.year(), run.snapshot.baseMonth(), Long.parseLong(run.snapshot.masterId()));
        ObjectNode result = (ObjectNode) json.safe(response);
        var metrics = response.kpis().stream().map(item -> comparisonMetric(item.key(), item.actual(), item.vsPlanAmount())).toList();
        result.set("summary", json.tree(Map.of("metrics", metrics, "detailRowCount", response.rows().size())));
        return result;
    }

    /** 목적: 기존 차이값의 부호와 지표 성격을 구분하며 결측을 영으로 바꾸지 않는다. Args: key 지표 성격, actual 현재 전망 원값, difference 계획 대비 차이 원값. Returns: 정확한 값·증감·손익 유불리·누락 표시. Raises: 금액 문자열 형식 오류. Side Effects: 없음. */
    private ObjectNode comparisonMetric(String key, String actual, String difference) {
        ObjectNode metric = json.project(Map.of("key", key), "key");
        metric.put("actualWon", actual); metric.put("planDifferenceWon", difference);
        boolean missing = actual == null || difference == null;
        int sign = missing ? 0 : new BigDecimal(difference).signum();
        metric.put("missing", missing);
        metric.put("direction", missing ? "MISSING" : sign > 0 ? "INCREASE" : sign < 0 ? "DECREASE" : "UNCHANGED");
        String favorability = "UNKNOWN";
        if (!missing && Set.of("REVENUE", "COST", "OP").contains(key)) {
            boolean favorable = "COST".equals(key) ? sign < 0 : sign > 0;
            favorability = sign == 0 ? "NEUTRAL" : favorable ? "FAVORABLE" : "UNFAVORABLE";
        }
        metric.put("favorability", favorability);
        return metric;
    }

    /** 목적: 기존 조직 손익 응답에서 고정 범위를 선택하고 영업이익 계획 차이의 순위·누락을 밝힌다. Args: run 실행, org 선택 조직 ID, period 서버가 선택한 YEAR 또는 MONTH. Returns: 기존 columns/total과 정확한 OP 요약. Raises: 기존 조회/금액 오류. Side Effects: DB 읽기만. */
    private JsonNode divisions(DecisionAssistantGateway.Run run, String org, String period) {
        var response = home.divisions(run.actor, period, run.snapshot.year(), run.snapshot.baseMonth(), Long.parseLong(run.snapshot.masterId()));
        ObjectNode result = json.project(response, "dataStatus", "total");
        var scope = run.snapshot.organizations().stream().filter(item -> org == null || item.id().equals(org)).toList();
        Set<String> allowed = new HashSet<>(); scope.forEach(item -> allowed.add(item.id()));
        var columns = response.columns().stream().filter(item -> allowed.contains(item.organization().id())).toList();
        result.set("columns", json.safe(columns));
        Map<String, HomeDivisionsResponse.Column> byId = new HashMap<>(); columns.forEach(item -> byId.put(item.organization().id(), item));
        List<ObjectNode> ranked = new ArrayList<>(); List<String> missingIds = new ArrayList<>();
        for (Organization item : scope) {
            var column = byId.get(item.id());
            ObjectNode metric = comparisonMetric("OP", column == null ? null : column.op(), column == null ? null : column.vsPlanAmount());
            metric.set("organization", json.safe(new OrganizationRef(item.id(), item.code(), item.name())));
            if (column == null) missingIds.add(item.id());
            ranked.add(metric);
        }
        result.set("summary", json.tree(Map.of("scopeOrganizationCount", scope.size(), "returnedOrganizationCount", columns.size(),
                "missingOrganizationIds", missingIds, "rankedByAbsoluteOpPlanDifference", rankMetrics(ranked, true),
                "rankedByOpPlanDifference", rankMetrics(ranked, false), "absoluteRankingBasis", "OP_DIFF_ABSOLUTE_DESC", "signedRankingBasis", "OP_DIFF_DESC",
                "unavailableComparisons", List.of("REVENUE_VS_PLAN", "COST_VS_PLAN"))));
        return result;
    }

    /** 목적: 연간 전망과 구분한 고정 기준월의 실적·계획 비교를 기존 업무 서비스로 조회한다. Args: run 고정 실행, org 선택 조직. Returns: 월별 전사 지표와 선택 조직 지표·범위 경고. Raises: 기존 조회 오류. Side Effects: DB 읽기만; 다른 월·마스터로 범위를 확장하지 않는다. */
    private ToolValue monthly(DecisionAssistantGateway.Run run, String org) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", "MONTH"); data.put("year", run.snapshot.year()); data.put("month", run.snapshot.baseMonth()); data.put("unit", "KRW");
        data.put("companyScope", "ALL_MASTER_ORGANIZATIONS"); data.put("selectedOrganizationId", org);
        data.put("company", company(run, "MONTH")); data.put("organizations", divisions(run, org, "MONTH"));
        return value(data, "고정 마스터 기준월의 실적과 조회 시점의 확정 계획·전월 추정 비교",
                List.of("연간 전망이 아니라 고정 기준월의 월간 실적입니다. 다른 월은 해당 기준의 마스터를 선택해야 합니다.",
                        "company와 organizations.total은 전체 마스터 조직 합계이며 선택 조직 합계가 아닙니다. 조직 선택은 organizations.columns와 summary에만 적용됩니다.",
                        "계획·전월 기준과 소명은 조회 시점 값입니다. 조직별 매출·비용 계획 차이는 기존 조회가 제공하지 않습니다."));
    }

    /** 목적: 고정 제출본의 조직·보고 항목별 기존 손익 근거를 한도 내에서 조회한다. Args: run 실행, org 선택 조직, args 항목 ID·상한. Returns: 연간 전망과 동일 제출본 계획의 비교 근거·전역 원천 제한. Raises: 기존 근거 조회 오류. Side Effects: DB 읽기만; 조사 캡처·원장 행 생성·계산식 변경 없음. */
    private ToolValue reportItems(DecisionAssistantGateway.Run run, String org, JsonNode args) {
        var saved = run.analysis == null ? null : research.find(run.analysis.id());
        var evidence = saved == null ? research.loadEvidence(run.snapshot) : saved.evidence();
        var source = evidence.stream().filter(item -> item.kind().equals("PNL")).toList();
        Set<String> organizations = new HashSet<>(); run.snapshot.organizations().forEach(item -> organizations.add(item.id()));
        String reportItemId = args.has("reportItemId") ? args.get("reportItemId").textValue() : null;
        var selected = source.stream().filter(item -> organizations.contains(item.organizationId())
                && (org == null || org.equals(item.organizationId()))
                && (reportItemId == null || reportItemId.equals(item.facts().get("reportItemId")))).toList();
        int limit = args.get("limit").intValue();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", "YEAR"); data.put("year", run.snapshot.year()); data.put("baseMonth", run.snapshot.baseMonth()); data.put("unit", "KRW");
        data.put("comparison", "SAME_SUBMISSION_PLAN_VS_ANNUAL_OUTLOOK"); data.put("capturedAt", saved == null ? null : saved.capturedAt());
        data.put("availableCount", selected.size()); data.put("sourceLimit", 100); data.put("sourceLimitMayApply", source.size() >= 100);
        data.put("limit", limit); data.put("items", selected.stream().limit(limit).toList());
        return value(data, saved == null ? "고정 제출본의 연간 전망과 동일 제출본 계획을 현재 조회" : "선택 분석에 최초 포착한 보고 항목별 근거",
                List.of("조직과 보고 항목(report_item) 단위 집계이며 원천 계정(account_item)·전표별 상세가 아닙니다.",
                        "계획은 동일 제출본의 PLAN입니다. HOME의 확정 연간 계획 비교와 다른 기준이므로 직접 혼합하지 않습니다. 계획이 불완전하면 차이는 null입니다.",
                        "원천 손익 근거는 전체 조직에서 최대 100개를 읽은 뒤 조직·항목 필터를 적용합니다. availableCount는 확보한 근거 수이며 전체 계정 수가 아닙니다.",
                        "차이와 이익 영향은 개선 가능액이나 인과 원인을 입증하지 않습니다."));
    }

    /** 목적: 계획 대비 개선이 큰 순서와 차이 절댓값이 큰 순서를 분리한다. Args: metrics 원본 조직 지표, absolute 절댓값 정렬 여부. Returns: 원본을 변경하지 않는 순위 목록. Raises: 금액 문자열 형식 오류. Side Effects: 없음. */
    private List<ObjectNode> rankMetrics(List<ObjectNode> metrics, boolean absolute) {
        List<ObjectNode> ranked = new ArrayList<>(); metrics.forEach(item -> ranked.add(item.deepCopy()));
        ranked.sort(Comparator.comparing((ObjectNode item) -> item.path("missing").booleanValue())
                .thenComparing(item -> {
                    BigDecimal difference = item.path("missing").booleanValue() ? BigDecimal.ZERO : new BigDecimal(item.path("planDifferenceWon").textValue());
                    return absolute ? difference.abs() : difference;
                }, Comparator.reverseOrder())
                .thenComparing(item -> item.path("organization").path("code").asText()));
        for (int index = 0; index < ranked.size(); index++) {
            if (ranked.get(index).path("missing").booleanValue()) ranked.get(index).putNull("rank");
            else ranked.get(index).put("rank", index + 1);
        }
        return List.copyOf(ranked);
    }

    /** 목적: 입력한 억원 목표를 같은 연도의 고정 전망과 비교하며 저장하거나 실적 달성을 선언하지 않는다. Args: run 실행, targetEok 검증·정규화된 양수 억원 문자열. Returns: 원 단위 목표·부족·초과액과 전망 비교. Raises: 기존 계산기의 금액 검증 오류. Side Effects: 없음. */
    private ToolValue goalPreview(DecisionAssistantGateway.Run run, String targetEok) {
        String targetWon = DecisionScenarioCalculator.decimal(new BigDecimal(targetEok).movePointRight(8));
        Result calculated = calculator.calculate(run.snapshot, targetWon, List.of(), List.of());
        BigDecimal signedGap = new BigDecimal(calculated.targetProfitWon()).subtract(new BigDecimal(calculated.baselineProfitWon()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("year", run.snapshot.year()); data.put("baseMonth", run.snapshot.baseMonth()); data.put("unit", "KRW");
        data.put("baselineProfitWon", calculated.baselineProfitWon()); data.put("targetProfitEok", targetEok); data.put("targetProfitWon", calculated.targetProfitWon());
        data.put("gapWon", calculated.initialGapWon()); data.put("surplusWon", DecisionScenarioCalculator.decimal(signedGap.negate().max(BigDecimal.ZERO)));
        data.put("signedGapWon", DecisionScenarioCalculator.decimal(signedGap)); data.put("forecastMeetsTarget", signedGap.signum() <= 0);
        data.put("saved", false); data.put("basis", "FIXED_ANNUAL_OUTLOOK");
        return value(data, "고정 마스터의 같은 연도 전망과 입력 목표의 읽기 전용 비교",
                List.of("목표는 저장하지 않았습니다. 현재 전망이 목표 이상이어도 실적 달성이나 미래 실현을 의미하지 않습니다.",
                        "개선 후보의 효과를 추가하지 않은 고정 기준 전망만 비교했습니다."));
    }

    /** 목적: 이미 고정한 소명 또는 기존 읽기 추출기를 사용한다. Args: run 실행, org 선택 조직, limit 상한. Returns: 소명 근거와 포착 시점. Raises: 조회 오류. Side Effects: DB 읽기만, research 생성 없음. */
    private ToolValue explanations(DecisionAssistantGateway.Run run, String org, int limit) {
        var saved = run.analysis == null ? null : research.find(run.analysis.id());
        var evidence = saved == null ? research.loadEvidence(run.snapshot) : saved.evidence();
        var selected = evidence.stream().filter(item -> item.kind().equals("APPROVED_EXPLANATION")
                && (org == null || item.organizationId().equals(org))).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("capturedAt", saved == null ? null : saved.capturedAt());
        data.put("total", selected.size()); data.put("items", selected.stream().limit(limit).toList());
        return value(data, saved == null ? "고정 제출본에 대해 최초 Tool 관찰 시점까지 승인된 소명" : "선택 분석에 최초 포착하여 저장한 승인 소명",
                List.of("소명 승인은 과거 설명의 검토 기록이며 새로운 개선안의 검증이나 추진 승인이 아닙니다.",
                        "기존 근거 추출기의 최대 소명 수는 50개이며 응답 items는 요청 상한까지 제공합니다."));
    }

    /** 목적: 고정 마스터 발행 이전 승인 버전만 공개한다. Args: run 실행, org 조직. Returns: 파일 없는 승인 목록. Raises: 조회 오류. Side Effects: DB 읽기. */
    private ToolValue versions(DecisionAssistantGateway.Run run, String org) {
        var response = archive.versions(run.actor, Long.parseLong(org), run.snapshot.year(), run.snapshot.baseMonth());
        var items = response.history().stream().filter(item -> "APPROVED".equals(item.status()) && item.lastModifiedAt() != null
                && !item.lastModifiedAt().isAfter(run.snapshot.asOf())).map(item -> json.project(item, "submissionId", "label", "status", "lastModifiedAt")).toList();
        return value(Map.of("organization", response.organization(), "history", items), "고정 기준월의 승인 버전 중 마스터 발행 시점 이전", List.of("비교 기준은 이 도구에서 실제 반환한 승인 제출본으로 제한됩니다."));
    }

    /** 목적: 앞선 허용 목록의 제출본과 고정 원천만 비교한다. Args: run 실행, org 조직, baseId 기준 제출본. Returns: 파일·상태 토큰을 제거한 비교표. Raises: 미조회 기준/조직 불일치/조회 오류. Side Effects: DB 읽기. */
    private ToolValue compare(DecisionAssistantGateway.Run run, String org, String baseId) {
        if (!run.allowedVersions.getOrDefault(org, Set.of()).contains(baseId)) throw new ToolFailure("먼저 해당 조직의 승인 제출 버전을 조회하고 반환된 기준 제출본을 선택해 주세요.");
        List<Long> ids = run.snapshot.sourceSubmissionIds().stream().map(Long::parseLong).toList();
        var target = submissions.findAllById(ids).stream().filter(item -> String.valueOf(item.getOrganizationId()).equals(org)).findFirst()
                .orElseThrow(() -> new ToolFailure("고정 마스터의 조직 제출본을 찾을 수 없습니다."));
        if (String.valueOf(target.getId()).equals(baseId)) throw new ToolFailure("현재 고정 제출본과 다른 승인 기준 제출본을 선택해 주세요.");
        var response = comparison.compare(target.getId(), Long.parseLong(baseId), run.actor);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("base", json.project(response.base(), "id", "status", "label", "approvedLabel", "period", "organization", "reviewedAt"));
        data.put("target", json.project(response.target(), "id", "status", "label", "approvedLabel", "period", "organization", "reviewedAt"));
        data.put("rows", response.rows());
        return value(data, "목표는 고정 마스터 원천, 기준은 발행 이전 승인 버전", List.of("plan 열은 비교 기준 제출 값이며 actual 열은 고정 대상 제출 값입니다."));
    }

    /** 목적: 선택 분석의 저장 후보만 정확한 기존 계산기로 비교한다. Args: run 실행, args 후보 선택. Returns: 저장하지 않은 시나리오 결과. Raises: 분석 미선택/미지 후보. Side Effects: 없음. */
    private ToolValue scenario(DecisionAssistantGateway.Run run, JsonNode args) {
        Analysis analysis = requireAnalysis(run);
        List<Candidate> selected = analysis.candidates();
        if (args.has("candidateIds")) {
            Set<String> ids = new HashSet<>(); args.get("candidateIds").forEach(id -> ids.add(id.textValue()));
            selected = selected.stream().filter(item -> ids.contains(item.id())).toList();
            if (selected.size() != ids.size()) throw new ToolFailure("선택 분석에 저장된 후보만 시나리오로 조회할 수 있습니다.");
        }
        return value(calculator.calculate(run.snapshot, analysis.targetProfitWon(), analysis.protectedCategories(), selected),
                "선택 분석 revision의 저장 후보만 임시 계산", List.of("미리보기는 저장하거나 승인하지 않으며 조건부 효과와 검토 효과를 구분해야 합니다."));
    }

    /** 목적: 자기 분석의 요청·회신·결정·추적 상태를 제한된 필드로 읽는다. Args: run 실행. Returns: 상태 목록. Raises: 분석 미선택/조회 오류. Side Effects: DB 읽기. */
    private ToolValue workItems(DecisionAssistantGateway.Run run) {
        Analysis analysis = requireAnalysis(run);
        var items = workflow.list(run.actor, Long.parseLong(analysis.id())).items().stream().map(item -> json.project(item,
                "id", "analysisId", "candidateId", "hypothesisId", "organizationId", "organizationName", "title", "revision", "state",
                "round", "dueAt", "updatedAt", "reply", "review", "decision", "progress")).toList();
        return value(Map.of("items", items), "조회 시점의 요청·회신·외부 결정 기록", List.of("추진 결정은 외부 의사결정의 기록이며 이 대화가 승인하거나 발송하지 않습니다.", "후속 전망 차이는 과제의 실현 성과를 증명하지 않습니다."));
    }

    /** 목적: 저장된 후보·가설만 사용자 확인용 요청 초안으로 바꾼다. Args: run 실행, args 원천 유형·식별자. Returns: 저장·발송되지 않은 초안. Raises: 분석 미선택/원천 부재. Side Effects: 가설 조회만, 저장·발송 없음. */
    private ToolValue draft(DecisionAssistantGateway.Run run, JsonNode args) {
        Analysis analysis = requireAnalysis(run); String kind = args.get("sourceKind").textValue(); String id = args.get("sourceId").textValue();
        String organizationId; String title; String message;
        if (kind.equals("candidate")) {
            Candidate candidate = analysis.candidates().stream().filter(item -> item.id().equals(id)).findFirst()
                    .orElseThrow(() -> new ToolFailure("선택 분석에 저장된 후보를 찾을 수 없습니다."));
            organizationId = candidate.organizationId(); title = candidate.title();
            message = title + "에 대해 개선 가능액, 반영 시점, 추가 비용, 이미 전망에 반영된 효과와 증빙을 확인해 주세요. 보호 조건과 다른 과제의 효과 중복도 검토해 주세요.";
        } else {
            var hypothesis = discovery.findHypothesis(run.actor, Long.parseLong(analysis.id()), id);
            organizationId = hypothesis.organizationId(); title = hypothesis.title();
            message = title + "에 대한 확인 요청 초안입니다. " + String.join(" ", hypothesis.questions());
        }
        return value(Map.of("analysisId", analysis.id(), "expectedAnalysisRevision", analysis.revision(), "sourceKind", kind, "sourceId", id,
                "organizationId", organizationId, "title", title, "message", message, "needsConfirmation", true),
                "선택 분석의 저장 후보 또는 저장 가설", List.of("기한과 요청 내용을 사용자가 확인한 뒤 업무 화면에서 별도로 생성·발행해야 합니다. 이 초안은 저장·발송되지 않았습니다."));
    }

    /** 목적: 분석이 필요한 조회의 선택 여부를 검증한다. Args: run 실행. Returns: 선택 분석. Raises: ToolFailure 분석 미선택. Side Effects: 없음. */
    private Analysis requireAnalysis(DecisionAssistantGateway.Run run) {
        if (run.analysis == null) throw new ToolFailure("이 조회에는 저장 분석이 필요합니다. 분석을 생성하거나 선택한 뒤 다시 질문해 주세요.");
        return run.analysis;
    }

    /** 목적: 도구 결과의 메타데이터를 묶는다. Args: data 자료, basis 비교 기준, warnings 한계. Returns: 결과 값. Raises: 없음. Side Effects: 없음. */
    private ToolValue value(Object data, String basis, List<String> warnings) { return new ToolValue(data, basis, warnings); }

    /** 목적: 서버가 허용하는 식별자 형식을 검증한다. Args: node 값, numeric 양수 long 여부. Returns: 없음. Raises: IllegalArgumentException 오류. Side Effects: 없음. */
    private void identifier(JsonNode node, boolean numeric) {
        if (node == null || !node.isTextual() || node.textValue().isBlank() || node.textValue().length() > 100) throw new IllegalArgumentException("식별자가 잘못되었습니다.");
        if (numeric) {
            if (!node.textValue().matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("식별자가 잘못되었습니다.");
            try { Long.parseLong(node.textValue()); } catch (NumberFormatException exception) { throw new IllegalArgumentException("식별자가 잘못되었습니다."); }
        }
    }

    record ToolValue(Object data, String comparisonBasis, List<String> warnings) { }
    static final class ToolFailure extends RuntimeException {
        /** 목적: 노출해도 되는 조회 안내를 전달한다. Args: message 고정된 안전 문장. Returns: 예외. Raises: 없음. Side Effects: 없음. */
        ToolFailure(String message) { super(message); }
    }
}
