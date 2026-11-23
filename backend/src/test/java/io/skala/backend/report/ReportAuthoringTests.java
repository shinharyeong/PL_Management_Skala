package io.skala.backend.report;

import static io.skala.backend.report.ReportAuthoringJson.*;
import static org.assertj.core.api.Assertions.*;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** SpringBoot·파일 출력 없이 H2와 실제 서비스·명령 경계를 검사한다. */
class ReportAuthoringTests {
    private JdbcClient jdbc;
    private ReportAuthoringService service;
    private ReportAuthoringController controller;
    private TransactionTemplate transactions;
    private Actor planner;
    /** V1~V10을 독립 메모리 DB에 적재하고 인증·명령 경계를 연결한다. Args: 없음. Returns: 없음. Raises: SQL 오류. Side Effects: 메모리 DB만 생성. */
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:reports_"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1","sa","");
        var scripts=new ResourceDatabasePopulator();
        for (String name:List.of("V1__submission_parser_foundation.sql","V2__validated_financial_submissions.sql","V3__annual_plan_masters.sql","V4__monthly_masters.sql","V5__submission_reviews_and_gap_thresholds.sql","V6__prd_review_workflow.sql","V7__workspace_notifications_templates.sql","V8__review_attachments.sql","V9__prd_workflow_settings.sql","V10__report_authoring.sql")) scripts.addScript(new ClassPathResource("db/migration/"+name)); scripts.execute(source);
        jdbc=JdbcClient.create(source); var manager=new DataSourceTransactionManager(source); transactions=new TransactionTemplate(manager);
        jdbc.sql("INSERT INTO organization(id,code,name) VALUES (1,'SYNTH','합성 조직')").update();
        jdbc.sql("INSERT INTO app_user(id,email,name,role,organization_id) VALUES (1,'planner@example.test','기획','PLANNER',NULL),(2,'manager@example.test','조직','ORG_MANAGER',1),(3,'other@example.test','다른 기획','PLANNER',NULL)").update();
        planner=new Actor("1","planner@example.test","기획","PLANNER",null); var ai=new ReportAiClient(new MockEnvironment()); service=new ReportAuthoringService(jdbc,new ReportFactService(jdbc),ai);
        controller=new ReportAuthoringController(service,ai,new ActorResolver(jdbc),new CommandExecutor(new JdbcTemplate(source),manager));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(planner.email(),null,List.of()));
    }
    /** 테스트 인증을 제거한다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 현재스레드 인증 제거. */
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }
    /** 실제 Controller 명령이 멱등이며 과거버전을 덮어쓰지 않음을 확인한다. Args: 없음. Returns: 없음. Raises: 단언 실패. Side Effects: 메모리 DB 저장. */
    @Test void previewApplyUndoAreIdempotentAndImmutable() {
        JsonNode input=JSON.valueToTree(Map.of("name","합성 자료","baseYear",2098,"baseMonth",3,"blocks",List.of(block()))); String key=UUID.randomUUID().toString();
        JsonNode created=read(controller.createMaterial(input,key).getBody()),replay=read(controller.createMaterial(input,key).getBody()); assertThat(replay.path("id")).isEqualTo(created.path("id")); String identity=created.path("id").asText();
        JsonNode proposal=read(controller.preview(identity,JSON.valueToTree(Map.of("expectedRevision",1,"name","확인 제목")),UUID.randomUUID().toString()).getBody());
        assertThat(proposal.path("results").get(0).path("status").asText()).isEqualTo("NO_DATA"); assertThat(proposal.path("results").get(0).path("rows").isEmpty()).isTrue(); assertThat(proposal.path("results").get(0).path("total").isNull()).isTrue();
        JsonNode applied=read(controller.apply(identity,JSON.valueToTree(Map.of("expectedRevision",1,"proposalId",proposal.path("proposalId").asText())),UUID.randomUUID().toString()).getBody()); assertThat(applied.path("revision").asInt()).isEqualTo(2); assertThat(service.get(planner,identity,1,false).get("name")).isEqualTo("합성 자료");
        JsonNode undone=read(controller.undo(identity,JSON.valueToTree(Map.of("expectedRevision",2)),UUID.randomUUID().toString()).getBody()); assertThat(undone.path("revision").asInt()).isEqualTo(3); assertThat(undone.path("name").asText()).isEqualTo("합성 자료");
        assertThat(controller.file(identity,2).getBody()).isNotEmpty(); assertThat(jdbc.sql("SELECT COUNT(*) FROM report_material").query(Integer.class).single()).isEqualTo(1);
    }
    /** 타인제안·오래된회차·조직역할과 순환사유를 거부한다. Args: 없음. Returns: 없음. Raises: 단언 실패. Side Effects: 메모리 DB만 사용. */
    @Test void staleForeignProposalsAndInvalidReasonHierarchyAreRejected() {
        var created=transactions.execute(status -> service.create(planner,JSON.valueToTree(Map.of("name","자료","blocks",List.of(block()))),false)); String identity=created.get("id").toString();
        var proposal=transactions.execute(status -> service.preview(planner,identity,JSON.valueToTree(Map.of("expectedRevision",1)))); Actor other=new Actor("3","other@example.test","다른 기획","PLANNER",null);
        assertThatThrownBy(() -> transactions.execute(status -> service.apply(other,identity,JSON.valueToTree(Map.of("expectedRevision",1,"proposalId",proposal.get("proposalId")))))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> transactions.execute(status -> service.preview(planner,identity,JSON.valueToTree(Map.of("expectedRevision",9))))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list(new Actor("2","manager@example.test","조직","ORG_MANAGER",new Organization("1","SYNTH","합성 조직")),false)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> transactions.execute(status -> service.saveReasons(planner,JSON.valueToTree(Map.of("expectedVersion",0,"items",List.of(Map.of("id","loop","name","순환","parentId","loop"))))))).isInstanceOf(ApiException.class);
    }
    /** 읽기 MCP와 AI 미설정 응답이 저장이나 외부호출을 하지 않는지 확인한다. Args: 없음. Returns: 없음. Raises: 단언 실패. Side Effects: DB 읽기만. */
    @Test void mcpIsReadOnlyAndAiMissingIsExplicit() {
        JsonNode input=JSON.valueToTree(Map.of("jsonrpc","2.0","id",1,"method","tools/call","params",Map.of("name","query_report_view","arguments",Map.of("baseYear",2098,"baseMonth",3,"block",block()))));
        JsonNode result=JSON.valueToTree(controller.mcp(input).getBody()); assertThat(result.path("result").path("structuredContent").path("readOnly").asBoolean()).isTrue(); assertThat(jdbc.sql("SELECT COUNT(*) FROM report_authoring_proposal").query(Integer.class).single()).isZero(); assertThat(controller.ai("sql",JSON.valueToTree(Map.of("question","매출 합계"))).get("configured")).isEqualTo(false);
    }
    /** 정밀 소수·사용자 별칭을 유지하고 SQL주입을 거부한다. Args: 없음. Returns: 없음. Raises: 단언 실패. Side Effects: 메모리만. */
    @Test void decimalQueriesRejectUnboundedOrMutatingSql() {
        var rows=List.of(Map.<String,Object>of("year",2026,"metric_code","REVENUE","amount","9007199254740993.123456"),Map.<String,Object>of("year",2026,"metric_code","REVENUE","amount","0.000001"));
        var result=ReportQueryEngine.execute("SELECT year, SUM(amount) AS total_value FROM report_view WHERE metric_code = 'REVENUE' GROUP BY year",rows); assertThat(((List<Map<String,Object>>)result.get("rows")).getFirst().get("total_value")).isEqualTo("9007199254740993.123457"); assertThat(result.get("valueColumn")).isEqualTo("total_value");
        for (String sql:List.of("SELECT amount FROM report_view; DELETE FROM submission","SELECT amount FROM report_view JOIN app_user ON 1=1","SELECT amount FROM report_view WHERE metric_code = 'REVENUE' OR 1=1","SELECT amount FROM report_view LIMIT 1001")) assertThatThrownBy(() -> ReportQueryEngine.execute(sql,rows)).isInstanceOf(ApiException.class);
    }
    /** 직전승인본의 동일월 추정과 상세소명 기여·상위사유 합계를 검증한다. Args: 없음. Returns: 없음. Raises: SQL/단언 실패. Side Effects: 합성 H2만 변경. */
    @Test void previousSameMonthEstimateAndReasonContributionReconcile() {
        seedFinancialData();
        transactions.execute(status -> service.saveReasons(planner,JSON.valueToTree(Map.of("expectedVersion",0,"items",List.of(Map.of("id","all","name","전체"),Map.of("id","sales","name","매출원인","parentId","all"),Map.of("id","cost","name","비용원인","parentId","all"))))));
        var definition=new LinkedHashMap<String,Object>(block()); definition.put("comparisonBasis","PREVIOUS"); definition.put("organizationIds",List.of("1")); definition.put("reasonMappings",List.of(Map.of("explanationId","501","reasonTypeId","sales"),Map.of("explanationId","502","reasonTypeId","cost")));
        JsonNode result=JSON.valueToTree(service.query(planner,JSON.valueToTree(Map.of("baseYear",2026,"baseMonth",3,"block",definition)))).path("results").get(0);
        assertThat(new java.math.BigDecimal(result.path("total").asText())).isEqualByComparingTo("70"); assertThat(new java.math.BigDecimal(result.path("baselineTotal").asText())).isEqualByComparingTo("60"); assertThat(new java.math.BigDecimal(result.path("gapTotal").asText())).isEqualByComparingTo("10"); assertThat(new java.math.BigDecimal(result.path("mappedTotal").asText())).isEqualByComparingTo("10"); assertThat(result.path("reconciled").asBoolean()).isTrue();
        assertThat(result.path("explanations").size()).isEqualTo(2); assertThat(result.path("status").asText()).isEqualTo("PROVISIONAL");
        var totals=new HashMap<String,java.math.BigDecimal>(); for (JsonNode row:result.path("reasonTotals")) totals.put(row.path("reasonTypeId").asText(),new java.math.BigDecimal(row.path("amount").asText())); assertThat(totals.get("sales")).isEqualByComparingTo("20"); assertThat(totals.get("cost")).isEqualByComparingTo("-10"); assertThat(totals.get("all")).isEqualByComparingTo("10");
        definition.put("reasonMappings",List.of(Map.of("explanationId","501","reasonTypeId","all"))); assertThatThrownBy(() -> service.query(planner,JSON.valueToTree(Map.of("baseYear",2026,"baseMonth",3,"block",definition)))).isInstanceOf(ApiException.class);
    }
    /** 틀은 자료별 소명을 저장하지 않으며 자료생성은 사용자가 확인한 틀 회차를 요구한다. Args: 없음. Returns: 없음. Raises: 단언 실패. Side Effects: 메모리 H2 틀·자료 버전 추가. */
    @Test void templatesDropReasonMappingsAndRequireExpectedSourceRevision() {
        var classified=new LinkedHashMap<String,Object>(block()); classified.put("reasonMappings",List.of(Map.of("explanationId","999","reasonTypeId","old")));
        var template=transactions.execute(status -> service.create(planner,JSON.valueToTree(Map.of("name","재사용 틀","blocks",List.of(classified))),true)); String identity=template.get("id").toString();
        assertThat(JSON.valueToTree(template.get("blocks")).get(0).path("reasonMappings").isEmpty()).isTrue();
        var second=transactions.execute(status -> service.templateVersion(planner,identity,JSON.valueToTree(Map.of("expectedRevision",1,"name","틀 수정","blocks",List.of(classified)))));
        assertThat(JSON.valueToTree(second.get("blocks")).get(0).path("reasonMappings").isEmpty()).isTrue(); assertThat(((List<?>)classified.get("reasonMappings"))).hasSize(1);
        assertThatThrownBy(() -> transactions.execute(status -> service.create(planner,JSON.valueToTree(Map.of("name","지난틀","templateId",identity,"expectedTemplateRevision",1)),false))).isInstanceOf(ApiException.class);
        var material=transactions.execute(status -> service.create(planner,JSON.valueToTree(Map.of("name","확인틀","templateId",identity,"expectedTemplateRevision",2)),false)); assertThat(material.get("templateRevision")).isEqualTo(2); assertThat(JSON.valueToTree(material.get("blocks")).get(0).path("reasonMappings").isEmpty()).isTrue();
    }
    /** 합성 블록을 반환한다. Args: 없음. Returns: 작성정의. Raises: 없음. Side Effects: 없음. */
    private Map<String,Object> block() { return Map.of("id","sample","title","합성 금액","type","GAP","metricCode","OP","scenario","ACTUAL","periodRule","CURRENT_MONTH","organizationIds",List.of()); }
    /** 금융원본 없이 3월실적·전월의3월추정·수용소명을 합성한다. Args: 없음. Returns: 없음. Raises: SQL 오류. Side Effects: 메모리 H2만 쓰며 Data는 읽지 않는다. */
    private void seedFinancialData() {
        jdbc.sql("INSERT INTO annual_plan(id,year,status,contingency_amount,confirmed_by,confirmed_at) VALUES (100,2026,'CONFIRMED',0,1,CURRENT_TIMESTAMP)").update();
        jdbc.sql("INSERT INTO submission_request(id,annual_plan_id,type,month,status,requested_by,requested_at,period_key) VALUES (100,100,'ANNUAL_PLAN',NULL,'CLOSED',1,CURRENT_TIMESTAMP,'2026-PLAN'),(199,100,'MONTHLY_ACTUAL',2,'OPEN',1,CURRENT_TIMESTAMP,'2026-02'),(200,100,'MONTHLY_ACTUAL',3,'OPEN',1,CURRENT_TIMESTAMP,'2026-03')").update();
        jdbc.sql("INSERT INTO request_target(request_id,organization_id) VALUES (100,1),(199,1),(200,1)").update(); jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES ('synthetic/report','application/json',?)").param(new byte[]{123,125}).update();
        for (int[] pair:List.of(new int[]{1000,100},new int[]{2000,200},new int[]{2001,199})) {
            jdbc.sql("INSERT INTO submission(id,request_id,organization_id,seq,status,original_filename,source_object_key,source_sha256,uploaded_by,uploaded_at,submitted_by,submitted_at,reviewed_by,reviewed_at) VALUES (?,?,1,1,'APPROVED','synthetic.xlsx','synthetic/report',?,2,CURRENT_TIMESTAMP,2,CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP)").params(pair[0],pair[1],"a".repeat(64)).update();
            jdbc.sql("INSERT INTO submission_validation(submission_id,attempt_no,source_sha256,parser_result_object_key,policy_version,validation_result_object_key,validated_by,validated_at) VALUES (?,1,?,'synthetic/report','test','synthetic/report',1,CURRENT_TIMESTAMP)").params(pair[0],"a".repeat(64)).update();
        }
        jdbc.sql("UPDATE request_target SET confirmed_plan_submission_id=1000 WHERE request_id=100").update(); jdbc.sql("INSERT INTO report_item(id,code,name,kind,is_leaf) VALUES (11,'SYNTH_REV','매출','REVENUE',TRUE),(12,'SYNTH_COST','비용','COST',TRUE)").update();
        for (int[] item:List.of(new int[]{10001,1000,11,90},new int[]{10002,1000,12,30},new int[]{20001,2000,11,120},new int[]{20002,2000,12,50},new int[]{30001,2001,11,100},new int[]{30002,2001,12,40})) {
            jdbc.sql("INSERT INTO financial_line(id,submission_id,report_item_id,source_sheet,source_row) VALUES (?,?,?,'합성',?)").params(item[0],item[1],item[2],item[2]).update();
            jdbc.sql("INSERT INTO financial_value(line_id,year,month,scenario,amount,source_record_id,source_cell) VALUES (?,2026,3,?,?,?,'A1')").params(item[0],item[1]==1000 ? "PLAN" : item[1]==2000 ? "ACTUAL" : "ESTIMATE",item[3],"row-"+item[0]).update();
        }
        jdbc.sql("INSERT INTO master_version(id,request_id,seq,trigger_submission_id,content_sha256,calculation_version,created_by,created_at) VALUES (3000,200,1,2000,?,'test',1,CURRENT_TIMESTAMP)").param("a".repeat(64)).update(); jdbc.sql("INSERT INTO master_source(master_id,organization_id,submission_id) VALUES (3000,1,2000)").update();
        jdbc.sql("INSERT INTO gap_threshold(id,scenario,percent,amount,applied_by,applied_at) VALUES (100,'ACTUAL',1,1,1,CURRENT_TIMESTAMP)").update();
        for (int[] item:List.of(new int[]{401,11,120,100,501},new int[]{402,12,50,40,502})) {
            jdbc.sql("INSERT INTO review_item(id,submission_id,report_item_id,year,month,scenario,amount_present,current_amount,change_detected,gap_exceeded,source_fingerprint,context_fingerprint,current_round) VALUES (?,2000,?,2026,3,'ACTUAL',TRUE,?,TRUE,TRUE,?,?,1)").params(item[0],item[1],item[2],"a".repeat(64),"b".repeat(64)).update();
            jdbc.sql("INSERT INTO gap_comparison(review_item_id,basis,baseline_submission_id,baseline_scenario,baseline_amount,current_amount,gap_amount,gap_rate,threshold_id,exceeded) VALUES (?,'PREVIOUS',2001,'ESTIMATE',?,?,?,0,100,TRUE)").params(item[0],item[3],item[2],item[2]-item[3]).update();
            jdbc.sql("INSERT INTO review_explanation(id,review_item_id,round,answer,answered_by,answered_at,verdict,reviewed_by,reviewed_at) VALUES (?,?,1,'합성 소명',2,CURRENT_TIMESTAMP,'ACCEPTED',1,CURRENT_TIMESTAMP)").params(item[4],item[0]).update();
        }
    }
}
