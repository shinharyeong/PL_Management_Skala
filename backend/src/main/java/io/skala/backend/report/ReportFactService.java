package io.skala.backend.report;

import static io.skala.backend.report.ReportAuthoringJson.*;
import static io.skala.backend.submission.ApiData.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.skala.backend.security.Actor;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 기획자 권한 아래 공식·잠정 출처를 선택하고 원 금액으로 보고 블록을 계산한다. */
@Service
public class ReportFactService {
    private final JdbcClient jdbc;
    /** 조회 의존성을 연결한다. Args: jdbc DB. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public ReportFactService(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 각 블록의 기간·범위·비교 기준으로 미리보기 결과를 만든다.
     * Args: actor 기획자, year/month 기준월, blocks 확인할 블록, reasonItems 선택 사유유형 버전.
     * Returns: 블록별 금액·출처·수용소명·사유대사. Raises: 권한·입력·SQL 오류.
     * Side Effects: DB 읽기와 메모리 계산만 수행한다.
     */
    public List<Map<String,Object>> evaluate(Actor actor, int year, int month, JsonNode blocks, JsonNode reasonItems) {
        actor.requireRole("PLANNER"); var results = new ArrayList<Map<String,Object>>();
        for (JsonNode block : blocks) results.add(evaluateBlock(year, month, block, reasonItems));
        return results;
    }

    /** 한 블록을 정확한 십진수와 출처로 평가한다. Args: year/month 기준, block 정의, reasonItems 사유계층. Returns: 미리보기. Raises: 입력 오류. Side Effects: DB 읽기. */
    private Map<String,Object> evaluateBlock(int year, int month, JsonNode block, JsonNode reasonItems) {
        var periods = periods(year, month, block); String scenario = block.path("scenario").asText("ACTUAL"), requestedMetric = block.path("metricCode").asText("OP");
        var metricDefinition = metric(requestedMetric); String metric = metricDefinition.get("baseMetricCode");
        String basis = block.path("comparisonBasis").asText("PLAN"); var organizations = organizationIds(block.path("organizationIds"));
        String sql = block.path("sql").asText("").strip(); if (sql.isEmpty()) sql = metricDefinition.get("sql"); var filters=ReportQueryEngine.filters(sql);
        if (!metric.equals(filters.get("metric_code"))) throw invalid("SQL은 선택한 원지표의 metric_code를 명시해야 합니다.");
        if (filters.containsKey("organization_id")) { long filtered=id(filters.get("organization_id")); if (!organizations.isEmpty() && !organizations.contains(filtered)) throw invalid("SQL 조직은 블록의 선택 범위 안에 있어야 합니다."); organizations=Set.of(filtered); }
        if (filters.containsKey("year")) periods=periods.stream().filter(period -> Integer.toString(period.getYear()).equals(filters.get("year"))).toList();
        if (filters.containsKey("month")) periods=periods.stream().filter(period -> Integer.toString(period.getMonthValue()).equals(filters.get("month"))).toList();
        var facts = new ArrayList<Map<String,Object>>(); var baseline = new ArrayList<Map<String,Object>>(); var sources = new ArrayList<Map<String,Object>>();
        var explanations = new LinkedHashMap<String,Map<String,Object>>(); var warnings = new ArrayList<String>(); boolean provisional = false, missing = false;
        for (YearMonth period : periods) {
            Bundle current = facts(period, scenario, organizations, YearMonth.of(year, month)); facts.addAll(current.rows()); sources.addAll(current.sources());
            provisional |= current.provisional(); missing |= current.missing();
            YearMonth previous = "YEAR_AGO".equals(basis) ? period.minusYears(1) : period;
            String previousScenario = "PLAN".equals(basis) ? "PLAN" : "PREVIOUS".equals(basis) ? "ESTIMATE" : scenario;
            YearMonth comparisonRequest = "PREVIOUS".equals(basis) ? YearMonth.of(year, month).minusMonths(1) : YearMonth.of(year, month);
            Bundle compared = facts(previous, previousScenario, organizations, comparisonRequest, "PREVIOUS".equals(basis)); baseline.addAll(compared.rows()); provisional |= compared.provisional();
            for (var source : compared.sources()) sources.add(object("role","BASELINE","period",source.get("period"),"scenario",source.get("scenario"),"submissionId",source.get("submissionId"),"organizationId",source.get("organizationId"),"masterId",source.get("masterId"),"status",source.get("status")));
            if (compared.missing()) warnings.add(previous + " 비교 자료가 없어 해당 비교금액을 확정할 수 없습니다.");
            for (var explanation : explanations(current.sources(), period, scenario, metric, basis)) {
                String item="OP".equals(explanation.get("reportItemCode")) ? "OP" : explanation.get("reportItemId").toString();
                BigDecimal currentValue=itemAmount(current.rows(),explanation.get("organizationId").toString(),item),baselineValue=itemAmount(compared.rows(),explanation.get("organizationId").toString(),item);
                explanation.put("reviewGapAmount",explanation.get("gapAmount")); explanation.put("gapAmount",currentValue==null || baselineValue==null ? null : currentValue.subtract(baselineValue).multiply(BigDecimal.valueOf(((Number)explanation.get("contributionSign")).longValue())).toPlainString());
                explanation.put("comparisonBasis",basis); explanation.put("baselinePeriod",previous.toString());
                if (!Objects.equals(explanation.get("reviewGapAmount"),explanation.get("gapAmount"))) explanation.put("comparisonWarning","제출 소명 당시 Gap과 보고자료의 비교 기준이 다릅니다. 보고 기준 금액으로 대사합니다.");
                explanations.put(explanation.get("explanationId").toString(), explanation);
            }
        }
        List<Map<String,Object>> selected=ReportQueryEngine.selectedFacts(sql,facts); String aggregation=ReportQueryEngine.aggregation(sql);
        missing |= selected.isEmpty();
        var selectedKeys=new HashSet<String>(); for (var row:selected) selectedKeys.add(factKey(row,0));
        List<Map<String,Object>> comparedRows=baseline.stream().filter(row -> selectedKeys.contains(factKey(row,"YEAR_AGO".equals(basis) ? 1 : 0))).toList();
        explanations.values().removeIf(explanation -> !selectedKeys.contains(explanation.get("organizationId")+":"+("OP".equals(metric) ? "OP" : explanation.get("reportItemId"))+":"+explanation.get("year")+":"+explanation.get("month")));
        Map<String,Object> queried = ReportQueryEngine.execute(sql, facts);
        BigDecimal total = aggregateAmount(selected,aggregation), plan = aggregateAmount(comparedRows,aggregation);
        boolean baselineMissing = !warnings.isEmpty() || comparedRows.size()!=selected.size(); BigDecimal gap = missing || baselineMissing || total==null || plan==null ? null : total.subtract(plan);
        if (!"SUM".equals(aggregation)) warnings.add("비합산 SQL 지표는 개별 소명 금액을 더해 대사하지 않습니다. 사유 분류는 SUM 지표에서 제공됩니다.");
        if (Boolean.TRUE.equals(queried.get("truncated"))) warnings.add("표의 행 제한을 초과했습니다. 합계는 표시된 행만이 아닌 전체 선택 범위를 기준으로 합니다.");
        var reasonNames = new HashMap<String,String>(); var parents=new HashMap<String,String>(); var branches=new HashSet<String>();
        for (JsonNode reason : reasonItems) if (reason.path("active").asBoolean(true)) { String identity=reason.path("id").asText(); reasonNames.put(identity,reason.path("name").asText()); String parent=reason.path("parentId").asText(null); parents.put(identity,parent); if (parent!=null) branches.add(parent); }
        suggestReasons(year,month,explanations,reasonItems,reasonNames,branches);
        var mappings = new HashMap<String,String>(); var reasonTotals = new LinkedHashMap<String,BigDecimal>(); BigDecimal mapped = BigDecimal.ZERO;
        for (JsonNode mapping : block.path("reasonMappings")) {
            if (!"SUM".equals(aggregation)) throw invalid("사유 금액 분류는 SUM 지표에서만 사용할 수 있습니다.");
            String explanationId = text(mapping,"explanationId",40), reasonId = text(mapping,"reasonTypeId",100);
            Map<String,Object> explanation = explanations.get(explanationId);
            if (explanation == null || !reasonNames.containsKey(reasonId) || branches.contains(reasonId) || mappings.putIfAbsent(explanationId,reasonId) != null) throw invalid("현재 자료의 수용 소명과 활성 말단 사유유형을 한 번씩 연결해 주세요.");
            Object amount = explanation.get("gapAmount");
            if (amount == null) throw invalid("비교 근거가 없는 소명은 금액 사유로 분류할 수 없습니다.");
            BigDecimal value = new BigDecimal(amount.toString());
            if (mapping.hasNonNull("amount")) {
                try { BigDecimal allocated = new BigDecimal(mapping.path("amount").asText()); if (allocated.scale() > 6 || allocated.signum() != value.signum() || allocated.abs().compareTo(value.abs()) > 0) throw new NumberFormatException(); value = allocated; }
                catch (NumberFormatException error) { throw invalid("분류 금액은 확인된 소명 Gap 범위를 넘을 수 없습니다."); }
            }
            mapped = mapped.add(value); reasonTotals.merge(reasonId,value,BigDecimal::add); explanation.put("reasonTypeId",reasonId); explanation.put("allocatedAmount",value.toPlainString());
        }
        if (missing) warnings.add("일부 조직·기간 자료가 없습니다. 누락을 0으로 채우지 않았습니다.");
        if (provisional) warnings.add("공식 마스터가 없는 기간의 검증된 제출본을 사용한 잠정 자료입니다.");
        var rolled=new LinkedHashMap<String,BigDecimal>(reasonTotals);
        for (var entry:reasonTotals.entrySet()) { String parent=parents.get(entry.getKey()); while (parent!=null) { rolled.merge(parent,entry.getValue(),BigDecimal::add); parent=parents.get(parent); } }
        var totals = new ArrayList<Map<String,Object>>(); for (var entry : rolled.entrySet()) totals.add(object("reasonTypeId",entry.getKey(),"name",reasonNames.get(entry.getKey()),"parentId",parents.get(entry.getKey()),"aggregate",branches.contains(entry.getKey()),"amount",entry.getValue().toPlainString()));
        return object("blockId",block.path("id").asText(),"title",block.path("title").asText(),"type",block.path("type").asText(),"status",missing ? "NO_DATA" : provisional ? "PROVISIONAL" : "FINAL",
                "columns",queried.get("columns"),"rows",missing ? List.of() : queried.get("rows"),"valueColumn",queried.get("valueColumn"),"partial",missing,"truncated",queried.get("truncated"),"sources",sources,"explanations",new ArrayList<>(explanations.values()),
                "total",missing || total==null ? null : total.toPlainString(),"baselineTotal",baselineMissing || plan==null ? null : plan.toPlainString(),"gapTotal",gap == null ? null : gap.toPlainString(),"mappedTotal",mapped.toPlainString(),"reasonAllocationAvailable","SUM".equals(aggregation),
                "unclassifiedTotal",gap == null ? null : gap.subtract(mapped).toPlainString(),"reconciled",gap != null && gap.compareTo(mapped) == 0,"reasonTotals",totals,"warnings",warnings,"sql",sql,
                "periods",periods.stream().map(YearMonth::toString).toList(),"scenario",scenario,"comparisonBasis",basis,"text",block.path("text").asText(""),"style",block.path("style"));
    }

    /** 상대·고정 기간을 월 목록으로 해석한다. Args: year/month 기준월, block 기간 정의. Returns: 1~24개월. Raises: 범위 오류. Side Effects: 없음. */
    private List<YearMonth> periods(int year, int month, JsonNode block) {
        YearMonth base = YearMonth.of(year,month); String rule = block.path("periodRule").asText("CURRENT_MONTH"); var result = new ArrayList<YearMonth>();
        switch (rule) {
            case "CURRENT_MONTH" -> result.add(base);
            case "PREVIOUS_MONTH" -> result.add(base.minusMonths(1));
            case "NEXT_MONTH" -> result.add(base.plusMonths(1));
            case "OFFSET" -> result.add(base.plusMonths(number(block,"offsetMonths",-120,120,0)));
            case "FIXED" -> result.add(YearMonth.of(number(block,"fixedYear",2000,9999,year),number(block,"fixedMonth",1,12,month)));
            case "ROLLING" -> { int count = number(block,"windowMonths",1,24,3); for (int index = count - 1; index >= 0; index--) result.add(base.minusMonths(index)); }
            case "YTD" -> { for (int index = 1; index <= month; index++) result.add(YearMonth.of(year,index)); }
            case "FULL_YEAR" -> { for (int index = 1; index <= 12; index++) result.add(YearMonth.of(year,index)); }
            default -> throw invalid("지원하지 않는 기간 규칙입니다.");
        }
        if (result.stream().anyMatch(value -> value.getYear() < 2000 || value.getYear() > 9999)) throw invalid("보고 기간 범위를 확인해 주세요.");
        return result;
    }

    /** 선택 조직을 확인한다. Args: values 조직 ID 배열. Returns: 활성·과거 조직 ID 집합. Raises: 없는 조직. Side Effects: DB 읽기. */
    private Set<Long> organizationIds(JsonNode values) {
        var result = new LinkedHashSet<Long>();
        for (JsonNode value : values) { long organization = id(value.asText()); if (!result.add(organization)) throw invalid("조직을 중복 선택할 수 없습니다."); }
        if (!result.isEmpty()) for (long organization : result) if (jdbc.sql("SELECT COUNT(*) FROM organization WHERE id=?").param(organization).query(Long.class).single() != 1) throw invalid("조직을 찾을 수 없습니다.");
        return result;
    }

    /** 기간별 공식 출처 또는 명시 잠정 출처의 원금액을 읽는다. Args: period 대상월, scenario 시나리오, organizations 범위, base 기준요청월. Returns: 보고행·출처·완전성. Raises: DB 오류. Side Effects: DB 읽기. */
    private Bundle facts(YearMonth period, String scenario, Set<Long> organizations, YearMonth base) {
        return facts(period,scenario,organizations,base,false);
    }
    /** 직전 추정 비교에는 전월 승인본만 허용한다. Args: period/scenario/organizations/base 조회범위, approvedOnly 승인본 제한. Returns: 출처 묶음. Raises: DB 오류. Side Effects: DB 읽기. */
    private Bundle facts(YearMonth period, String scenario, Set<Long> organizations, YearMonth base, boolean approvedOnly) {
        var required = jdbc.sql("SELECT t.organization_id,t.confirmed_plan_submission_id FROM request_target t JOIN submission_request r ON r.id=t.request_id JOIN annual_plan a ON a.id=r.annual_plan_id WHERE a.year=? AND r.type='ANNUAL_PLAN' AND t.excluded=FALSE ORDER BY t.organization_id").param(period.getYear()).query().listOfRows();
        if (required.isEmpty()) return new Bundle(List.of(),List.of(),false,true);
        var rows = new ArrayList<Map<String,Object>>(); var sources = new ArrayList<Map<String,Object>>(); boolean provisional = false, missing = false;
        for (var target : required) {
            long organization = ((Number)target.get("organization_id")).longValue(); if (!organizations.isEmpty() && !organizations.contains(organization)) continue;
            Long submission = null; Long master = null; boolean official = false;
            if ("PLAN".equals(scenario) && target.get("confirmed_plan_submission_id") != null) { submission = ((Number)target.get("confirmed_plan_submission_id")).longValue(); official = true; }
            if ("ACTUAL".equals(scenario)) {
                var found = jdbc.sql("SELECT s.submission_id,m.id FROM master_version m JOIN master_source s ON s.master_id=m.id JOIN submission_request r ON r.id=m.request_id JOIN annual_plan a ON a.id=r.annual_plan_id WHERE a.year=? AND r.month=? AND s.organization_id=? ORDER BY m.seq DESC LIMIT 1").params(period.getYear(),period.getMonthValue(),organization).query().listOfRows();
                if (!found.isEmpty()) { submission = ((Number)found.getFirst().get("submission_id")).longValue(); master = ((Number)found.getFirst().get("id")).longValue(); official = true; }
            }
            if (submission == null) {
                String type = "PLAN".equals(scenario) ? "ANNUAL_PLAN" : "MONTHLY_ACTUAL";
                var candidates = jdbc.sql("SELECT s.id,s.status FROM submission s JOIN submission_request r ON r.id=s.request_id JOIN annual_plan a ON a.id=r.annual_plan_id JOIN submission_validation v ON v.submission_id=s.id WHERE a.year=? AND r.type=? AND (?='ANNUAL_PLAN' OR r.month=?) AND s.organization_id=? AND (s.status='APPROVED' OR (?=FALSE AND s.status='PENDING')) ORDER BY CASE WHEN s.status='APPROVED' THEN 0 ELSE 1 END,s.seq DESC LIMIT 1")
                        .params("PLAN".equals(scenario) ? period.getYear() : base.getYear(),type,type,base.getMonthValue(),organization,approvedOnly).query().listOfRows();
                if (!candidates.isEmpty()) submission = ((Number)candidates.getFirst().get("id")).longValue();
            }
            if (submission == null) { missing = true; continue; }
            var sourceRows = jdbc.sql("SELECT o.name AS organization_name,r.id AS report_item_id,r.name AS report_item_name,r.kind,SUM(v.amount) AS amount FROM financial_value v JOIN financial_line l ON l.id=v.line_id JOIN report_item r ON r.id=l.report_item_id JOIN submission s ON s.id=l.submission_id JOIN organization o ON o.id=s.organization_id WHERE s.id=? AND v.year=? AND v.month=? AND v.scenario=? GROUP BY o.name,r.id,r.name,r.kind ORDER BY r.id")
                    .params(submission,period.getYear(),period.getMonthValue(),scenario).query().listOfRows();
            if (sourceRows.isEmpty()) { missing = true; continue; }
            provisional |= !official; BigDecimal revenue = BigDecimal.ZERO, cost = BigDecimal.ZERO;
            for (var sourceRow : sourceRows) {
                String kind = sourceRow.get("kind").toString(); BigDecimal amount = new BigDecimal(sourceRow.get("amount").toString());
                if ("REVENUE".equals(kind)) revenue = revenue.add(amount); else if ("COST".equals(kind)) cost = cost.add(amount); else continue;
                rows.add(fact(period,scenario,organization,sourceRow.get("organization_name"),sourceRow.get("report_item_id").toString(),sourceRow.get("report_item_name"),kind,amount));
            }
            rows.add(fact(period,scenario,organization,sourceRows.getFirst().get("organization_name"),"OP","영업이익","OP",revenue.subtract(cost)));
            sources.add(object("role","CURRENT","period",period.toString(),"scenario",scenario,"organizationId",Long.toString(organization),"submissionId",submission.toString(),"masterId",master == null ? null : master.toString(),"status",official ? "FINAL" : "PROVISIONAL"));
        }
        if (!organizations.isEmpty() && required.stream().map(row -> ((Number)row.get("organization_id")).longValue()).filter(organizations::contains).count() != organizations.size()) missing = true;
        return new Bundle(rows,sources,provisional,missing || rows.isEmpty());
    }

    /** 보고뷰 행을 만든다. Args: period/scenario/organization/name/item/itemName/metric/amount 확인된 값. Returns: 보고행. Raises: 없음. Side Effects: 없음. */
    private Map<String,Object> fact(YearMonth period,String scenario,long organization,Object name,String item,Object itemName,String metric,BigDecimal amount) {
        return object("year",period.getYear(),"month",period.getMonthValue(),"scenario",scenario,"organization_id",Long.toString(organization),"organization_name",name,"report_item_id",item,"report_item_name",itemName,"metric_code",metric,"amount",amount.toPlainString());
    }
    /** 비교행을 현재 기간의 업무키로 대응한다. Args: row 보고행, yearOffset 비교연도 이동. Returns: 조직·항목·연월키. Raises: 내부 타입 오류. Side Effects: 없음. */
    private String factKey(Map<String,Object> row,int yearOffset) { return row.get("organization_id")+":"+row.get("report_item_id")+":"+(((Number)row.get("year")).intValue()+yearOffset)+":"+row.get("month"); }
    /** SQL과 같은 금액 집계 함수를 사용한다. Args: rows 선택원천, operation 집계종류. Returns: 정밀금액 또는 혼합집계 null. Raises: 금액오류. Side Effects: 없음. */
    private BigDecimal aggregateAmount(List<Map<String,Object>> rows,String operation) {
        if (rows.isEmpty() || "MIXED".equals(operation)) return null; var values=rows.stream().map(row -> new BigDecimal(row.get("amount").toString())).toList(); BigDecimal sum=values.stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        return switch(operation) { case "SUM" -> sum; case "AVG" -> sum.divide(BigDecimal.valueOf(values.size()),6,java.math.RoundingMode.HALF_UP); case "MIN" -> Collections.min(values); case "MAX" -> Collections.max(values); default -> null; };
    }
    /** 한 조직·항목의 보고 금액을 찾는다. Args: rows 보고행, organization 조직, item 항목. Returns: 금액 또는 null. Raises: 내부 금액 오류. Side Effects: 없음. */
    private BigDecimal itemAmount(List<Map<String,Object>> rows,String organization,String item) {
        var found=rows.stream().filter(row -> organization.equals(row.get("organization_id")) && item.equals(row.get("report_item_id"))).toList();
        return found.isEmpty() ? null : found.stream().map(row -> new BigDecimal(row.get("amount").toString())).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    /** 과거 자료 분류와 명시 키워드 규칙을 미확정 후보로 제시한다. Args: year/month 기준, explanations 소명, reasons 계층, names 활성명, branches 상위집합. Returns: 없음. Raises: DB/JSON 오류. Side Effects: 메모리 후보만 추가하고 분류를 자동 적용하지 않는다. */
    private void suggestReasons(int year,int month,Map<String,Map<String,Object>> explanations,JsonNode reasons,Map<String,String> names,Set<String> branches) {
        YearMonth previous=YearMonth.of(year,month).minusMonths(1); var past=new LinkedHashMap<String,Map<String,Object>>();
        for (var saved:jdbc.sql("SELECT v.material_id,v.revision,v.results_json FROM report_material_version v JOIN report_material m ON m.id=v.material_id AND m.current_revision=v.revision WHERE v.base_year=? AND v.base_month=? ORDER BY v.created_at DESC LIMIT 50").params(previous.getYear(),previous.getMonthValue()).query().listOfRows()) {
            for (JsonNode block:read(saved.get("results_json"))) for (JsonNode explanation:block.path("explanations")) {
                String reason=explanation.path("reasonTypeId").asText(""); if (!names.containsKey(reason) || branches.contains(reason)) continue;
                String key=explanation.path("organizationId").asText()+":"+explanation.path("reportItemId").asText();
                past.putIfAbsent(key,object("reasonTypeId",reason,"source","PREVIOUS_MATERIAL","materialId",saved.get("material_id").toString(),"revision",saved.get("revision")));
            }
        }
        for (var explanation:explanations.values()) {
            String key=explanation.get("organizationId")+":"+explanation.get("reportItemId"); Map<String,Object> suggestion=past.get(key);
            if (suggestion==null) { var candidates=new LinkedHashSet<String>(); String answer=explanation.get("answer").toString();
                for (JsonNode reason:reasons) if (names.containsKey(reason.path("id").asText()) && !branches.contains(reason.path("id").asText())) for (JsonNode keyword:reason.path("keywords")) if (!keyword.asText().isBlank() && answer.contains(keyword.asText())) candidates.add(reason.path("id").asText());
                if (candidates.size()==1) suggestion=object("reasonTypeId",candidates.iterator().next(),"source","KEYWORD_RULE");
            }
            if (suggestion!=null) { explanation.put("reasonSuggestion",suggestion); explanation.put("suggestedReasonTypeId",suggestion.get("reasonTypeId")); }
        }
    }
    /** 기본 지표 SQL을 반환한다. Args: metric 지표. Returns: 확인 가능한 SQL. Raises: 400 미지원 지표. Side Effects: 없음. */
    public static String defaultSql(String metric) {
        if (!Set.of("REVENUE","COST","OP").contains(metric)) throw invalid("지원하지 않는 지표입니다.");
        return "SELECT year, month, organization_name, SUM(amount) AS amount FROM report_view WHERE metric_code = '" + metric + "' GROUP BY year, month, organization_name";
    }
    /** 불변 지표사전의 원지표와 SQL을 해석한다. Args: code 기본 또는 CUSTOM ID. Returns: baseMetricCode/sql. Raises: 없는 지표. Side Effects: custom일 때 DB 읽기. */
    public Map<String,String> metric(String code) {
        if (Set.of("REVENUE","COST","OP").contains(code)) return Map.of("baseMetricCode",code,"sql",defaultSql(code));
        if (code==null || !code.startsWith("CUSTOM:")) throw invalid("지원하지 않는 지표입니다.");
        var found=jdbc.sql("SELECT base_metric_code,sql_text FROM report_metric WHERE id=?").param(id(code.substring(7))).query().listOfRows();
        if (found.isEmpty()) throw invalid("지표를 찾을 수 없습니다."); return Map.of("baseMetricCode",found.getFirst().get("base_metric_code").toString(),"sql",found.getFirst().get("sql_text").toString());
    }

    /** 선택 출처에서 해당 지표의 수용 소명과 고정 비교값만 읽는다. Args: sources 출처, period 월, scenario 시나리오, metric 지표, basis 비교. Returns: 수용 소명. Raises: DB 오류. Side Effects: DB 읽기. */
    private List<Map<String,Object>> explanations(List<Map<String,Object>> sources,YearMonth period,String scenario,String metric,String basis) {
        var result = new ArrayList<Map<String,Object>>();
        for (var source : sources) {
            var found = jdbc.sql("SELECT e.id,e.answer,e.round,i.id AS review_id,i.report_item_id,i.current_amount,g.baseline_amount,r.kind,r.code FROM review_item i JOIN review_explanation e ON e.review_item_id=i.id AND e.round=i.current_round JOIN report_item r ON r.id=i.report_item_id LEFT JOIN gap_comparison g ON g.review_item_id=i.id AND g.basis=? WHERE i.submission_id=? AND i.year=? AND i.month=? AND i.scenario=? AND i.active=TRUE AND i.workflow_state<>'EXCLUDED' AND e.verdict='ACCEPTED' AND e.answer IS NOT NULL ORDER BY e.id")
                    .params("PLAN".equals(basis) ? "PLAN" : "PREVIOUS",id(source.get("submissionId").toString()),period.getYear(),period.getMonthValue(),scenario).query().listOfRows();
            boolean detailed="OP".equals(metric) && found.stream().anyMatch(row -> Set.of("REVENUE","COST").contains(row.get("kind").toString()));
            for (var row : found) {
                if ("OP".equals(metric)) { if (detailed ? !Set.of("REVENUE","COST").contains(row.get("kind").toString()) : !"OP".equals(row.get("code"))) continue; }
                else if (!metric.equals(row.get("kind"))) continue;
                BigDecimal gap = "YEAR_AGO".equals(basis) || row.get("current_amount") == null || row.get("baseline_amount") == null ? null : new BigDecimal(row.get("current_amount").toString()).subtract(new BigDecimal(row.get("baseline_amount").toString()));
                result.add(object("explanationId",row.get("id").toString(),"reviewItemId",row.get("review_id").toString(),"organizationId",source.get("organizationId"),"reportItemId",row.get("report_item_id").toString(),"reportItemCode",row.get("code"),"contributionSign","OP".equals(metric) && "COST".equals(row.get("kind")) ? -1 : 1,"contributionBasis",detailed ? "REVENUE_PLUS_COST_MINUS" : "DIRECT","submissionId",source.get("submissionId"),"year",period.getYear(),"month",period.getMonthValue(),"scenario",scenario,"round",row.get("round"),"answer",row.get("answer"),"gapAmount",gap == null ? null : gap.toPlainString()));
            }
        }
        return result;
    }
    private record Bundle(List<Map<String,Object>> rows,List<Map<String,Object>> sources,boolean provisional,boolean missing) {}
}
