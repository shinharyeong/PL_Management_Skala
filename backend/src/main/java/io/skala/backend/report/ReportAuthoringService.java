package io.skala.backend.report;

import static io.skala.backend.report.ReportAuthoringJson.*;
import static io.skala.backend.submission.ApiData.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.skala.backend.common.ApiException;
import io.skala.backend.security.Actor;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 자료틀·보고자료·사유계층을 불변 버전으로 저장하고 명시 확인 후에만 제안을 적용한다. */
@Service
public class ReportAuthoringService {
    private final JdbcClient jdbc;
    private final ReportFactService facts;
    private final ReportAiClient ai;
    /** 의존성을 연결한다. Args: jdbc 저장, facts 숫자 조회, ai 선택 AI. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public ReportAuthoringService(JdbcClient jdbc,ReportFactService facts,ReportAiClient ai) { this.jdbc=jdbc; this.facts=facts; this.ai=ai; }

    /** SQL 지표·기간·글꼴·연결 상태를 조회한다. Args: actor 기획자. Returns: 작성 카탈로그. Raises: 권한 오류. Side Effects: DB 읽기만 수행. */
    @Transactional(readOnly=true)
    public Map<String,Object> catalog(Actor actor) {
        actor.requireRole("PLANNER");
        var metrics=new ArrayList<Map<String,Object>>(List.of(object("code","REVENUE","name","매출","sql",ReportFactService.defaultSql("REVENUE")),object("code","COST","name","영업비용","sql",ReportFactService.defaultSql("COST")),object("code","OP","name","영업이익","sql",ReportFactService.defaultSql("OP"))));
        for (var row:jdbc.sql("SELECT * FROM report_metric ORDER BY id").query().listOfRows()) metrics.add(object("code","CUSTOM:"+row.get("id"),"name",row.get("name"),"baseMetricCode",row.get("base_metric_code"),"sql",row.get("sql_text")));
        return object("metrics",metrics,
                "periodRules",List.of("CURRENT_MONTH","PREVIOUS_MONTH","NEXT_MONTH","YTD","FULL_YEAR","OFFSET","FIXED","ROLLING"),"fonts",List.of("system-ui","sans-serif","serif","monospace"),"aiConfigured",ai.configured(),
                "periods",jdbc.sql("SELECT r.id,a.year,r.month,r.type,a.status AS plan_status,CASE WHEN r.type='ANNUAL_PLAN' THEN (SELECT COUNT(*) FROM annual_master m WHERE m.request_id=r.id) ELSE (SELECT COUNT(*) FROM master_version m WHERE m.request_id=r.id) END AS master_count FROM submission_request r JOIN annual_plan a ON a.id=r.annual_plan_id ORDER BY a.year DESC,r.month").query().listOfRows().stream().map(row -> object("requestId",row.get("id").toString(),"year",row.get("year"),"month",row.get("month"),"type",row.get("type"),"planStatus",row.get("plan_status"),"hasOfficialMaster",((Number)row.get("master_count")).longValue()>0)).toList(),
                "sqlSchema",object("view","report_view","columns",List.of("year","month","scenario","organization_id","organization_name","report_item_id","report_item_name","metric_code","amount")),
                "sqlHelp","SELECT 차원, SUM/AVG/MIN/MAX(amount) AS 별칭 FROM report_view WHERE 차원 = '값' AND ... GROUP BY 차원 ORDER BY 조회열 ASC|DESC LIMIT 1~1000");
    }
    /** 자료 또는 틀 목록을 조회한다. Args: actor 기획자, template 틀 여부. Returns: items 목록. Raises: 권한 오류. Side Effects: DB 읽기. */
    @Transactional(readOnly=true)
    public Map<String,Object> list(Actor actor,boolean template) {
        actor.requireRole("PLANNER"); String table = template ? "report_template" : "report_material";
        var items = new ArrayList<Map<String,Object>>();
        String query=template ? "SELECT id,name,current_revision,created_at FROM report_template ORDER BY id DESC LIMIT 200" : "SELECT m.id,m.name,m.current_revision,m.created_at,v.base_year,v.base_month FROM report_material m JOIN report_material_version v ON v.material_id=m.id AND v.revision=m.current_revision ORDER BY m.id DESC LIMIT 200";
        for (var row : jdbc.sql(query).query().listOfRows()) items.add(object("id",row.get("id").toString(),"name",row.get("name"),"revision",row.get("current_revision"),"baseYear",row.get("base_year"),"baseMonth",row.get("base_month"),"createdAt",instant(row.get("created_at"))));
        return object("items",items);
    }
    /** 자료 또는 틀의 특정 불변 버전을 읽는다. Args: actor 기획자, value ID, revision 선택버전, template 틀 여부. Returns: 정의·저장결과·버전목록. Raises: 권한·404. Side Effects: DB 읽기. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> get(Actor actor,String value,Integer revision,boolean template) {
        actor.requireRole("PLANNER"); long identity = id(value); String table = template ? "report_template" : "report_material";
        var current = one("SELECT * FROM " + table + " WHERE id=?",identity); int selected = revision == null ? ((Number)current.get("current_revision")).intValue() : revision;
        var row = one("SELECT * FROM " + table + "_version WHERE " + (template ? "template_id" : "material_id") + "=? AND revision=?",identity,selected);
        var result = object("id",value,"name",row.get("name"),"revision",selected,"currentRevision",current.get("current_revision"),"blocks",read(row.get("blocks_json")),"createdAt",instant(row.get("created_at")));
        result.put("versions",jdbc.sql("SELECT revision,created_at FROM " + table + "_version WHERE " + (template ? "template_id" : "material_id") + "=? ORDER BY revision DESC").param(identity).query().listOfRows().stream().map(version -> object("revision",version.get("revision"),"createdAt",instant(version.get("created_at")))).toList());
        if (!template) {
            result.put("baseYear",row.get("base_year")); result.put("baseMonth",row.get("base_month")); result.put("results",read(row.get("results_json"))); result.put("reasonVersion",row.get("reason_version")); result.put("templateId",row.get("template_id") == null ? null : row.get("template_id").toString()); result.put("templateRevision",row.get("template_revision")); result.put("action",row.get("action")); result.put("restoredRevision",row.get("restored_revision"));
        }
        return result;
    }
    /** 사용자 정의로 새 자료 또는 틀을 만든다. Args: actor 기획자, input 정의, template 틀 여부. Returns: 첫 버전. Raises: 권한·입력 오류. Side Effects: DB 새 헤더·불변버전 저장. */
    @Transactional
    public Map<String,Object> create(Actor actor,JsonNode input,boolean template) {
        actor.requireRole("PLANNER"); String name=text(input,"name",200); JsonNode blocks=input.path("blocks"); Long templateId=null; Integer templateRevision=null;
        if (!template && input.hasNonNull("templateId")) {
            templateId=id(input.path("templateId").asText()); var source=get(actor,templateId.toString(),null,true); templateRevision=((Number)source.get("revision")).intValue();
            if (number(input,"expectedTemplateRevision",1,Integer.MAX_VALUE,0)!=templateRevision) throw conflict();
            if (blocks.isMissingNode()) blocks=JSON.valueToTree(source.get("blocks"));
        }
        blocks=template ? templateBlocks(blocks) : validateBlocks(blocks); int year=number(input,"baseYear",2000,9999,YearMonth.now(ZoneId.of("Asia/Seoul")).getYear()),month=number(input,"baseMonth",1,12,1);
        String table=template ? "report_template" : "report_material"; OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC); var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO " + table + "(name,current_revision,created_by,created_at) VALUES (?,1,?,?)").params(name,id(actor.id()),now).update(key,"id"); long identity=Objects.requireNonNull(key.getKey()).longValue();
        if (template) jdbc.sql("INSERT INTO report_template_version(template_id,revision,name,blocks_json,created_by,created_at) VALUES (?,1,?,?,?,?)").params(identity,name,write(blocks),id(actor.id()),now).update();
        else insertMaterial(identity,1,name,year,month,templateId,templateRevision,null,blocks,JSON.createArrayNode(),"CREATE",null,actor);
        return get(actor,Long.toString(identity),null,template);
    }
    /** 틀 수정안을 새 버전으로 저장한다. Args: actor 기획자, value 틀ID, input 기대회차·이름·블록. Returns: 새 버전. Raises: 동시수정·입력. Side Effects: 기존버전 불변, DB 추가. */
    @Transactional
    public Map<String,Object> templateVersion(Actor actor,String value,JsonNode input) {
        actor.requireRole("PLANNER"); var header=lock(value,true); int expected=number(input,"expectedRevision",1,Integer.MAX_VALUE,0); if (((Number)header.get("current_revision")).intValue()!=expected) throw conflict();
        String name=text(input,"name",200); JsonNode blocks=templateBlocks(input.path("blocks")); int next=expected+1;
        jdbc.sql("INSERT INTO report_template_version(template_id,revision,name,blocks_json,created_by,created_at) VALUES (?,?,?,?,?,?)").params(id(value),next,name,write(blocks),id(actor.id()),OffsetDateTime.now(ZoneOffset.UTC)).update();
        jdbc.sql("UPDATE report_template SET name=?,current_revision=? WHERE id=?").params(name,next,id(value)).update(); return get(actor,value,null,true);
    }
    /** 편집 초안을 계산하고 사용자별 만료 제안으로 보존한다. Args: actor 기획자, value 자료ID, input 기대회차·편집정의. Returns: proposalId·정의·결과. Raises: 입력·동시수정. Side Effects: 보고 금액 원본은 읽고 미확정 제안 한 건만 추가. */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(Actor actor,String value,JsonNode input) {
        actor.requireRole("PLANNER"); var current=get(actor,value,null,false); int expected=number(input,"expectedRevision",1,Integer.MAX_VALUE,0); if (((Number)current.get("revision")).intValue()!=expected) throw conflict();
        ObjectNode definition=JSON.valueToTree(current); for (String field:List.of("name","baseYear","baseMonth","blocks")) if (input.has(field)) definition.set(field,input.get(field));
        String name=text(definition,"name",200); int year=number(definition,"baseYear",2000,9999,0),month=number(definition,"baseMonth",1,12,0); JsonNode blocks=validateBlocks(definition.path("blocks")); definition.set("blocks",blocks);
        var reasons=reasons(actor,null); JsonNode reasonItems=JSON.valueToTree(reasons.get("items")); var results=facts.evaluate(actor,year,month,blocks,reasonItems);
        String proposal=UUID.randomUUID().toString(); OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC),expires=now.plusMinutes(30); Integer reasonVersion=((Number)reasons.get("version")).intValue(); if (reasonVersion==0) reasonVersion=null;
        jdbc.sql("INSERT INTO report_authoring_proposal(id,material_id,expected_revision,definition_json,results_json,reason_version,actor_id,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?)")
                .params(proposal,id(value),expected,write(definition),write(results),reasonVersion,id(actor.id()),now,expires).update();
        return object("proposalId",proposal,"expectedRevision",expected,"expiresAt",expires.toString(),"name",name,"baseYear",year,"baseMonth",month,"blocks",blocks,"results",results,"reasonVersion",reasonVersion,"warnings",List.of("미리보기는 적용하기 전까지 현재 자료를 바꾸지 않습니다."));
    }
    /** 본인 미리보기 제안을 명시 적용해 새 불변 버전을 만든다. Args: actor 기획자, value 자료ID, input proposalId·기대회차. Returns: 저장버전. Raises: 만료·타인제안·동시수정. Side Effects: 제안소비·새 버전·헤더를 원자 저장. */
    @Transactional
    public Map<String,Object> apply(Actor actor,String value,JsonNode input) {
        actor.requireRole("PLANNER"); var header=lock(value,false); String proposal=text(input,"proposalId",36); int expected=number(input,"expectedRevision",1,Integer.MAX_VALUE,0);
        var row=one("SELECT * FROM report_authoring_proposal WHERE id=? AND material_id=? AND actor_id=? FOR UPDATE",proposal,id(value),id(actor.id()));
        if (row.get("applied_revision")!=null) return get(actor,value,((Number)row.get("applied_revision")).intValue(),false);
        if (((Number)header.get("current_revision")).intValue()!=expected || ((Number)row.get("expected_revision")).intValue()!=expected) throw conflict();
        if (Instant.parse(instant(row.get("expires_at"))).isBefore(Instant.now())) throw new ApiException(HttpStatus.CONFLICT,"REPORT_PROPOSAL_EXPIRED","미리보기가 만료되었습니다. 다시 확인해 주세요.");
        JsonNode definition=read(row.get("definition_json")); int next=expected+1; String name=text(definition,"name",200);
        insertMaterial(id(value),next,name,definition.path("baseYear").asInt(),definition.path("baseMonth").asInt(),nullableLong(definition.path("templateId")),nullableInt(definition.path("templateRevision")),row.get("reason_version")==null ? null : ((Number)row.get("reason_version")).intValue(),definition.path("blocks"),read(row.get("results_json")),"APPLY",null,actor);
        jdbc.sql("UPDATE report_material SET name=?,current_revision=? WHERE id=?").params(name,next,id(value)).update(); jdbc.sql("UPDATE report_authoring_proposal SET applied_revision=? WHERE id=?").params(next,proposal).update(); return get(actor,value,null,false);
    }
    /** 직전 버전 내용을 새 버전으로 복원한다. Args: actor 기획자, value 자료ID, input 기대회차. Returns: 복원버전. Raises: 첫버전·동시수정. Side Effects: 과거버전 삭제 없이 DB 새 버전 추가. */
    @Transactional
    public Map<String,Object> undo(Actor actor,String value,JsonNode input) {
        actor.requireRole("PLANNER"); var header=lock(value,false); int expected=number(input,"expectedRevision",2,Integer.MAX_VALUE,0); if (((Number)header.get("current_revision")).intValue()!=expected) throw conflict();
        var previous=get(actor,value,expected-1,false); JsonNode definition=JSON.valueToTree(previous); int next=expected+1; String name=previous.get("name").toString();
        insertMaterial(id(value),next,name,((Number)previous.get("baseYear")).intValue(),((Number)previous.get("baseMonth")).intValue(),nullableLong(definition.path("templateId")),nullableInt(definition.path("templateRevision")),nullableInt(definition.path("reasonVersion")),definition.path("blocks"),definition.path("results"),"UNDO",expected-1,actor);
        jdbc.sql("UPDATE report_material SET name=?,current_revision=? WHERE id=?").params(name,next,id(value)).update(); return get(actor,value,null,false);
    }
    /** 사유계층의 현재 또는 지정 버전을 읽는다. Args: actor 기획자, version 선택버전. Returns: version/items/versions. Raises: 권한·404. Side Effects: DB 읽기. */
    @Transactional(readOnly=true)
    public Map<String,Object> reasons(Actor actor,Integer version) {
        actor.requireRole("PLANNER"); var versions=jdbc.sql("SELECT version,created_at FROM report_reason_version ORDER BY version DESC").query().listOfRows();
        if (versions.isEmpty()) { if (version!=null) throw missing(); return object("version",0,"items",List.of(),"versions",List.of()); }
        int selected=version==null ? ((Number)versions.getFirst().get("version")).intValue() : version;
        var row=one("SELECT items_json FROM report_reason_version WHERE version=?",selected); return object("version",selected,"items",read(row.get("items_json")),"versions",versions.stream().map(item -> object("version",item.get("version"),"createdAt",instant(item.get("created_at")))).toList());
    }
    /** 사유유형의 유효한 전체 계층을 새 버전으로 추가한다. Args: actor 기획자, input expectedVersion/items. Returns: 새 계층. Raises: 순환·중복·동시수정. Side Effects: DB 버전 추가, 소명 원문 변경 없음. */
    @Transactional
    public Map<String,Object> saveReasons(Actor actor,JsonNode input) {
        actor.requireRole("PLANNER"); jdbc.sql("SELECT id FROM report_authoring_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        int current=((Number)reasons(actor,null).get("version")).intValue(),expected=number(input,"expectedVersion",0,Integer.MAX_VALUE,-1); if (expected!=current) throw conflict();
        JsonNode items=input.path("items"); if (!items.isArray() || items.size()>200) throw invalid("사유유형은 최대 200개입니다."); var parents=new HashMap<String,String>(); var normalized=JSON.createArrayNode();
        for (JsonNode item:items) { String identity=text(item,"id",100),name=text(item,"name",200),parent=item.path("parentId").isNull() ? null : item.path("parentId").asText(null); if (parents.containsKey(identity)) throw invalid("사유유형 ID가 중복됩니다."); parents.put(identity,parent);
            var keywords=new ArrayList<String>(); if (item.has("keywords")) { if (!item.path("keywords").isArray() || item.path("keywords").size()>30) throw invalid("키워드 규칙은 유형별 30개 이하입니다."); for (JsonNode keyword:item.path("keywords")) { if (!keyword.isTextual() || keyword.asText().isBlank() || keyword.asText().length()>100) throw invalid("키워드는 1~100자 문자열입니다."); keywords.add(keyword.asText().strip()); } }
            normalized.add(JSON.valueToTree(object("id",identity,"name",name,"parentId",parent,"active",item.path("active").asBoolean(true),"keywords",keywords))); }
        var enabled=new HashMap<String,Boolean>(); for (JsonNode item:normalized) enabled.put(item.path("id").asText(),item.path("active").asBoolean());
        for (String identity:parents.keySet()) { var visited=new HashSet<String>(); String cursor=identity; while (cursor!=null) { if (!parents.containsKey(cursor) || !visited.add(cursor)) throw invalid("사유유형 부모가 없거나 순환합니다."); if (Boolean.TRUE.equals(enabled.get(identity)) && !Boolean.TRUE.equals(enabled.get(cursor))) throw invalid("활성 사유의 상위 유형도 활성 상태여야 합니다."); cursor=parents.get(cursor); } }
        jdbc.sql("INSERT INTO report_reason_version(version,items_json,created_by,created_at) VALUES (?,?,?,?)").params(current+1,write(normalized),id(actor.id()),OffsetDateTime.now(ZoneOffset.UTC)).update(); return reasons(actor,null);
    }
    /** 블록을 정규화하고 금액 입력·실행 가능한 서식을 차단한다. Args: blocks 편집 JSON. Returns: 검증한 독립 배열. Raises: 입력·SQL 오류. Side Effects: 없음. */
    public JsonNode validateBlocks(JsonNode blocks) {
        if (!blocks.isArray() || blocks.isEmpty() || blocks.size()>30) throw invalid("자료에는 1~30개 블록을 넣어 주세요."); var result=JSON.createArrayNode(); var identities=new HashSet<String>();
        for (JsonNode block:blocks) {
            if (!block.isObject()) throw invalid("블록 형식이 올바르지 않습니다."); String identity=text(block,"id",100); if (!identities.add(identity)) throw invalid("블록 ID가 중복됩니다.");
            String type=block.path("type").asText("TABLE"),metric=block.path("metricCode").asText("OP"),scenario=block.path("scenario").asText("ACTUAL"),rule=block.path("periodRule").asText("CURRENT_MONTH"),basis=block.path("comparisonBasis").asText("PLAN");
            if (!Set.of("TEXT","TABLE","CHART","GAP","LIST").contains(type) || !Set.of("PLAN","ACTUAL","ESTIMATE").contains(scenario) || !Set.of("CURRENT_MONTH","PREVIOUS_MONTH","NEXT_MONTH","YTD","FULL_YEAR","OFFSET","FIXED","ROLLING").contains(rule) || !Set.of("PLAN","PREVIOUS","YEAR_AGO").contains(basis)) throw invalid("블록 종류·지표·기간·시나리오를 확인해 주세요.");
            String sql=block.path("sql").asText("").strip(); var metricDefinition=facts.metric(metric); var filters=ReportQueryEngine.filters(sql.isEmpty() ? metricDefinition.get("sql") : sql);
            if (!metricDefinition.get("baseMetricCode").equals(filters.get("metric_code"))) throw invalid("SQL은 선택한 원지표의 metric_code를 명시해야 합니다.");
            ObjectNode copy=JSON.createObjectNode(); for (String field:List.of("id","title","text","organizationIds","style","reasonMappings","sourcePage","sourceBounds","offsetMonths","fixedYear","fixedMonth","windowMonths")) if (block.has(field)) copy.set(field,block.get(field));
            copy.put("title",text(block,"title",200)); copy.put("type",type); copy.put("metricCode",metric); copy.put("scenario",scenario); copy.put("periodRule",rule); copy.put("comparisonBasis",basis); copy.put("sql",sql);
            String chart=block.path("chartKind").asText("BAR"); if (!Set.of("BAR","PIE").contains(chart)) throw invalid("차트는 BAR 또는 PIE입니다."); copy.put("chartKind",chart);
            if (copy.path("text").asText("").length()>20000) throw invalid("블록 본문은 20,000자 이하입니다.");
            if (!copy.has("organizationIds")) copy.set("organizationIds",JSON.createArrayNode()); if (!copy.path("organizationIds").isArray() || copy.path("organizationIds").size()>200) throw invalid("조직 목록을 확인해 주세요.");
            if (!copy.has("reasonMappings")) copy.set("reasonMappings",JSON.createArrayNode()); if (!copy.path("reasonMappings").isArray() || copy.path("reasonMappings").size()>1000) throw invalid("사유 분류 목록을 확인해 주세요.");
            if (!copy.has("style")) copy.set("style",JSON.createObjectNode()); JsonNode style=copy.path("style"); if (!style.isObject() || write(style).length()>3000) throw invalid("블록 서식 값을 확인해 주세요.");
            for (String color:List.of("color","backgroundColor")) if (style.hasNonNull(color) && !style.path(color).asText().matches("#[0-9a-fA-F]{6}")) throw invalid("색상은 #RRGGBB 형식입니다.");
            if (style.hasNonNull("fontFamily") && !Set.of("system-ui","sans-serif","serif","monospace").contains(style.path("fontFamily").asText())) throw invalid("지원하는 글꼴을 선택해 주세요.");
            if (style.has("fontSize")) number(style,"fontSize",8,72,14); if (style.has("width")) number(style,"width",25,100,100);
            if (copy.has("sourcePage")) number(copy,"sourcePage",1,30,1);
            if (copy.has("sourceBounds")) { JsonNode bounds=copy.path("sourceBounds"); if (!bounds.isArray() || bounds.size()!=4) throw invalid("PDF 위치는 x,y,width,height 배열입니다."); for (JsonNode coordinate:bounds) if (!coordinate.isNumber() || coordinate.asDouble()<0 || coordinate.asDouble()>1) throw invalid("PDF 위치는 0~1 상대값입니다."); if (bounds.get(0).asDouble()+bounds.get(2).asDouble()>1.000001 || bounds.get(1).asDouble()+bounds.get(3).asDouble()>1.000001) throw invalid("PDF 블록 위치가 페이지 범위를 벗어납니다."); }
            result.add(copy);
        }
        return result;
    }
    /** 자료별 소명 분류를 제거하고 재사용할 틀의 정의만 남긴다. Args: blocks 틀로 저장할 구성. Returns: 분류가 빈 독립 블록배열. Raises: 블록·SQL 검증오류. Side Effects: 입력과 기존자료를 변경하지 않는다. */
    private JsonNode templateBlocks(JsonNode blocks) {
        JsonNode validated=validateBlocks(blocks); for (JsonNode block:validated) ((ObjectNode)block).set("reasonMappings",JSON.createArrayNode()); return validated;
    }
    /** 확인한 지표 SQL을 불변 사전에 등록한다. Args: actor 기획자, input 이름·SQL·원지표. Returns: 등록 지표. Raises: SQL·지표 오류. Side Effects: DB 한 행 추가. */
    @Transactional
    public Map<String,Object> saveMetric(Actor actor,JsonNode input) {
        actor.requireRole("PLANNER"); String name=text(input,"name",200),sql=text(input,"sql",4000); ReportQueryEngine.execute(sql,List.of());
        var matcher=java.util.regex.Pattern.compile("(?i)metric_code\\s*=\\s*'(REVENUE|COST|OP)'").matcher(sql); if (!matcher.find()) throw invalid("SQL은 metric_code를 REVENUE/COST/OP 중 하나로 제한해야 합니다.");
        String base=matcher.group(1).toUpperCase(Locale.ROOT); if (input.hasNonNull("baseMetricCode") && !base.equals(input.path("baseMetricCode").asText())) throw invalid("SQL 지표와 원지표가 다릅니다.");
        var key=new GeneratedKeyHolder(); jdbc.sql("INSERT INTO report_metric(name,base_metric_code,sql_text,created_by,created_at) VALUES (?,?,?,?,?)").params(name,base,sql,id(actor.id()),OffsetDateTime.now(ZoneOffset.UTC)).update(key,"id");
        return object("code","CUSTOM:"+Objects.requireNonNull(key.getKey()).longValue(),"name",name,"baseMetricCode",base,"sql",sql);
    }
    /** 저장 없는 읽기 전용 보고뷰 질의를 실행한다. Args: actor 기획자, input 기준월·block 또는 blocks. Returns: 결과목록. Raises: 입력·권한·SQL 오류. Side Effects: DB 읽기만 수행. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> query(Actor actor,JsonNode input) {
        actor.requireRole("PLANNER"); int year=number(input,"baseYear",2000,9999,0),month=number(input,"baseMonth",1,12,0);
        JsonNode blocks=input.path("blocks"); if (blocks.isMissingNode() && input.path("block").isObject()) { var array=JSON.createArrayNode(); array.add(input.get("block")); blocks=array; }
        blocks=validateBlocks(blocks); var reasons=reasons(actor,null); return object("results",facts.evaluate(actor,year,month,blocks,JSON.valueToTree(reasons.get("items"))),"readOnly",true);
    }
    /** 새 보고 버전을 추가한다. Args: identity/revision/name/기간/틀참조/사유버전/정의/결과/행위/복원회차/actor. Returns: 없음. Raises: DB 제약. Side Effects: 불변 버전 한 건 추가. */
    private void insertMaterial(long identity,int revision,String name,int year,int month,Long templateId,Integer templateRevision,Integer reasonVersion,JsonNode blocks,JsonNode results,String action,Integer restored,Actor actor) {
        jdbc.sql("INSERT INTO report_material_version(material_id,revision,name,base_year,base_month,template_id,template_revision,reason_version,blocks_json,results_json,action,restored_revision,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .params(identity,revision,name,year,month,templateId,templateRevision,reasonVersion,write(blocks),write(results),action,restored,id(actor.id()),OffsetDateTime.now(ZoneOffset.UTC)).update();
    }
    /** 수정할 헤더를 잠근다. Args: value ID, template 틀 여부. Returns: 헤더. Raises: 404. Side Effects: 트랜잭션 행잠금. */
    private Map<String,Object> lock(String value,boolean template) { return one("SELECT * FROM " + (template ? "report_template" : "report_material") + " WHERE id=? FOR UPDATE",id(value)); }
    /** 정확히 한 DB 행을 읽는다. Args: sql 서버상수, params 값. Returns: 행. Raises: 404. Side Effects: DB 읽기. */
    private Map<String,Object> one(String sql,Object... params) { var found=jdbc.sql(sql).params(params).query().listOfRows(); if (found.isEmpty()) throw missing(); return found.getFirst(); }
    /** nullable ID를 변환한다. Args: value JSON. Returns: ID 또는 null. Raises: 입력 오류. Side Effects: 없음. */
    private Long nullableLong(JsonNode value) { return value.isMissingNode() || value.isNull() ? null : id(value.asText()); }
    /** nullable 회차를 변환한다. Args: value JSON. Returns: 정수 또는 null. Raises: 없음. Side Effects: 없음. */
    private Integer nullableInt(JsonNode value) { return value.isMissingNode() || value.isNull() ? null : value.asInt(); }
    /** 조회 부재 오류를 만든다. Args: 없음. Returns: 404 예외. Raises: 없음. Side Effects: 없음. */
    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"REPORT_NOT_FOUND","보고 자료 또는 버전을 찾을 수 없습니다."); }
}
