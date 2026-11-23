package io.skala.backend.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.Actor;
import io.skala.backend.security.Organization;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;

/** 새 PRD의 선정·제외·요청 이력과 기존 소명 정책 보존을 합성 H2에서 검증한다. */
class ReviewWorkflowTests {
    private EmbeddedDatabase database;
    private JdbcClient jdbc;
    private ReviewService service;
    private ReviewComparisonService comparisons;
    private Actor planner;
    private Actor manager;

    /** 과거 정책과 새 정책 검토 항목을 같은 대기 제출본에 준비한다.
     * Args: 없음. Returns: 없음. Raises: DB 오류. Side Effects: 합성 메모리 DB만 생성한다.
     */
    @BeforeEach
    void prepare() {
        database=new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName("review_"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=YEAR,MONTH").build();
        var resources=List.of("V1__submission_parser_foundation.sql","V2__validated_financial_submissions.sql","V3__annual_plan_masters.sql","V4__monthly_masters.sql","V5__submission_reviews_and_gap_thresholds.sql","V6__prd_review_workflow.sql","V9__prd_workflow_settings.sql","V11__review_requirement_policy.sql");
        new ResourceDatabasePopulator(resources.stream().map(name->new ClassPathResource("db/migration/"+name)).toArray(ClassPathResource[]::new)).execute(database);
        jdbc=JdbcClient.create(database);var mapper=new ObjectMapper().findAndRegisterModules();
        comparisons=new ReviewComparisonService(jdbc,mapper);
        service=new ReviewService(jdbc,new CommandExecutor(new JdbcTemplate(database),new DataSourceTransactionManager(database)),comparisons);
        planner=new Actor("1","planner@example.test","기획","PLANNER",null);
        manager=new Actor("2","manager@example.test","담당","ORG_MANAGER",new Organization("1","D100","사업조직"));
        jdbc.sql("INSERT INTO organization(id,code,name) VALUES (1,'D100','사업조직')").update();
        jdbc.sql("INSERT INTO app_user(id,email,name,role,organization_id) VALUES (1,'planner@example.test','기획','PLANNER',NULL),(2,'manager@example.test','담당','ORG_MANAGER',1)").update();
        jdbc.sql("INSERT INTO annual_plan(id,year,status) VALUES (1,2031,'CONFIRMED')").update();
        jdbc.sql("INSERT INTO submission_request(id,annual_plan_id,type,month,status,requested_by,requested_at,period_key) VALUES (1,1,'MONTHLY_ACTUAL',1,'OPEN',1,CURRENT_TIMESTAMP,'2031-1'),(2,1,'MONTHLY_ACTUAL',2,'OPEN',1,CURRENT_TIMESTAMP,'2031-2')").update();
        jdbc.sql("INSERT INTO request_target(request_id,organization_id) VALUES (1,1),(2,1)").update();
        jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES ('source/test','application/octet-stream',?)").param(new byte[]{1}).update();
        seedSubmission(10,1,1,"APPROVED");seedSubmission(11,1,2,"REJECTED");seedSubmission(20,2,1,"PENDING");
        jdbc.sql("INSERT INTO report_item(id,code,name,kind,is_leaf) VALUES (1,'REV','매출','REVENUE',TRUE),(2,'COST','비용','COST',TRUE),(3,'OP','영업이익','DERIVED',FALSE)").update();
        for(int index=0;index<3;index++) {
            jdbc.sql("INSERT INTO review_item(id,submission_id,report_item_id,year,month,scenario,amount_present,current_amount,change_detected,gap_exceeded,source_fingerprint,context_fingerprint,current_round,review_policy_version) VALUES (?,20,?,2031,2,'ACTUAL',TRUE,120,TRUE,?,?,?,1,?)")
                    .params(100+index,index+1,index==2,"a".repeat(64),"b".repeat(64),index==1?"legacy/1.0":"monthly-actual/2.0").update();
            jdbc.sql("INSERT INTO review_explanation(review_item_id,round,answer,answered_by,answered_at,verdict) VALUES (?,1,'최초 소명',2,CURRENT_TIMESTAMP,'PENDING')").param(100+index).update();
        }
    }

    /** 메모리 DB를 해제한다. Args: 없음. Returns: 없음. Raises: DB 오류. Side Effects: 메모리 DB만 종료한다. */
    @AfterEach void close() { if(database!=null)database.shutdown(); }

    /** 새 달은 전월 승인본, 같은 달은 반려된 직전 제출본을 선택한다.
     * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 제출 행만 추가한다.
     */
    @Test void baselineUsesApprovedPreviousMonthAndRejectedSameMonth() {
        assertThat(comparisons.context(20).get("previousSubmissionId")).isEqualTo("10");
        seedSubmission(21,2,2,"REJECTED");
        assertThat(comparisons.context(20).get("previousSubmissionId")).isEqualTo("21");
        assertThat(comparisons.context(20).get("reviewPolicyVersion")).isEqualTo("monthly-actual/2.0");
    }

    /** 새 정책의 기준 미달 변경은 선택 사항이고 과거 정책 필수 항목은 그대로 유지한다.
     * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 제외 결정·행위 이력만 저장한다.
     */
    @Test void exclusionsChangeReadinessWithoutRewritingLegacyReasonsOrAmounts() {
        assertThat(service.counts(20).get("requiredCount")).isEqualTo(2);
        assertThat(service.get(planner,"100").get("required")).isEqualTo(false);
        assertThat(service.get(planner,"101").get("required")).isEqualTo(true);
        var input=new ReviewService.BulkActionInput("EXCLUDE",List.of(item("102")),"계약 정산의 확인된 차이",null);
        var first=service.actions(planner,"exclude-test-001",input);
        assertThat(service.actions(planner,"exclude-test-001",input).body()).isEqualTo(first.body());
        assertThat(service.counts(20).get("requiredCount")).isEqualTo(1);
        assertThat(service.get(planner,"102").get("workflowState")).isEqualTo("EXCLUDED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM review_workflow_event").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT current_amount FROM review_item WHERE id=102").query(java.math.BigDecimal.class).single()).isEqualByComparingTo("120");
        assertThat(service.list(planner,Map.of("workflowState","EXCLUDED")).get("items")).asList().hasSize(1);
    }

    /** 일괄 소명 요청은 기존 답변을 보존하고 기한을 가진 새 회차로 조직의 답변과 검토를 연결한다.
     * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 소명 회차·감사 이력을 저장한다.
     */
    @Test void batchRequestCreatesPreservedRoundAndPersistsDeadline() {
        service.actions(planner,"select-test-001",new ReviewService.BulkActionInput("SELECT",List.of(item("102")),null,null));
        assertThat(service.get(planner,"102").get("workflowState")).isEqualTo("SELECTED");
        var due=OffsetDateTime.parse("2031-02-28T18:00:00+09:00");
        service.actions(planner,"request-test-001",new ReviewService.BulkActionInput("REQUEST_EXPLANATION",List.of(item("102")),"원인과 향후 대응을 보완해 주세요.",due));
        var row=service.get(planner,"102");
        assertThat(row.get("workflowState")).isEqualTo("IN_PROGRESS");assertThat(row.get("currentRound")).isEqualTo(2);
        assertThat(row.get("requestDueAt")).isEqualTo("2031-02-28T09:00:00Z");
        assertThat(row.get("explanations")).asList().hasSize(2);assertThat(row.get("workflowHistory")).asList().hasSize(2);
        assertThatThrownBy(()->service.actions(planner,"select-cannot-cancel-request",new ReviewService.BulkActionInput("SELECT",List.of(item("102")),null,null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("REVIEW_STAGE_CONFLICT");
        service.answer(manager,"102","answer-test-002",new ReviewService.AnswerInput(row.get("stateToken").toString(),2,"계약 이연에 따른 변동입니다."));
        row=service.get(planner,"102");assertThat(row.get("workflowState")).isEqualTo("RE_REVIEW");
        service.decide(planner,"102","accept-test-002",new ReviewService.DecisionInput(row.get("stateToken").toString(),2,"ACCEPT",null));
        assertThat(service.get(planner,"102").get("workflowState")).isEqualTo("ACCEPTED");
        assertThat(jdbc.sql("SELECT answer FROM review_explanation WHERE review_item_id=102 AND round=1").query(String.class).single()).isEqualTo("최초 소명");
    }

    /** 한 항목의 오래된 토큰·권한 오류·확정 상태는 일괄 명령 전체를 차단한다.
     * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 실패 명령은 모두 롤백하고 마지막에 합성 제출 상태만 변경한다.
     */
    @Test void bulkActionsAreAtomicAuthorizedAndCannotMutateApprovedSubmissions() {
        var input=new ReviewService.BulkActionInput("EXCLUDE",List.of(item("100"),new ReviewService.ActionItem("101","stale")),"검증한 제외 근거",null);
        assertThatThrownBy(()->service.actions(planner,"stale-batch-001",input)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("RESOURCE_CHANGED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM review_workflow_event").query(Integer.class).single()).isZero();
        assertThat(service.get(planner,"100").get("workflowState")).isEqualTo("NEEDS_REVIEW");
        assertThatThrownBy(()->service.actions(manager,"wrong-role-001",input)).isInstanceOf(AccessDeniedException.class);
        jdbc.sql("UPDATE submission SET status='APPROVED' WHERE id=20").update();
        var locked=new ReviewService.BulkActionInput("EXCLUDE",List.of(item("102")),"확정본 변경 시도",null);
        assertThatThrownBy(()->service.actions(planner,"locked-batch-001",locked)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("REVIEW_LOCKED");
    }

    /** 전역 모드는 권한·멱등·낙관적 잠금을 적용하며 과거 검토의 필수 여부를 소급하지 않는다.
     * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 정책 이력·멱등 결과만 생성한다.
     */
    @Test void policyChangesAreAuthorizedIdempotentAndPreserveCompletedReviews() throws Exception {
        var policies=policies();var current=policies.current(planner);
        assertThat(current.get("requirementMode")).isEqualTo("GAP_ONLY");
        var input=new ReviewPolicyService.ApplyInput(current.get("stateToken").toString(),"ALL_CHANGES");
        assertThatThrownBy(()->policies.apply(manager,"policy-role-check",input)).isInstanceOf(AccessDeniedException.class);
        var first=policies.apply(planner,"policy-all-changes",input);
        assertThat(policies.apply(planner,"policy-all-changes",input).replayed()).isTrue();
        assertThat(new ObjectMapper().readTree(first.body()).path("requirementMode").asText()).isEqualTo("ALL_CHANGES");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM review_policy").query(Integer.class).single()).isEqualTo(2);
        assertThatThrownBy(()->policies.apply(planner,"policy-stale-check",new ReviewPolicyService.ApplyInput(input.expectedStateToken(),"GAP_ONLY")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RESOURCE_CHANGED");
        assertThatThrownBy(()->policies.apply(planner,"policy-invalid-mode",new ReviewPolicyService.ApplyInput(input.expectedStateToken(),"ALL")))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_REQUEST");
        assertThat(service.counts(20).get("requiredCount")).isEqualTo(2);
        assertThat(service.get(planner,"100").get("requirementMode")).isEqualTo("GAP_ONLY");
        assertThat(service.get(planner,"101").get("requirementMode")).isEqualTo("ALL_CHANGES");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM review_explanation").query(Integer.class).single()).isEqualTo(3);
    }

    /** 후보 영향은 변경·Gap의 중복을 제거하고 명시 요청과 제외 결정을 유지한다.
     * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 테스트 조건의 검토 상태만 변경하며 정책 미리보기는 읽기만 한다.
     */
    @Test void policyPreviewCountsUnionAndKeepsExplicitRequestsAndExclusions() {
        var policies=policies();
        var all=policies.preview(planner,new ReviewPolicyService.PreviewInput("2","ALL_CHANGES"));
        var gap=policies.preview(planner,new ReviewPolicyService.PreviewInput("2","GAP_ONLY"));
        assertThat(all.get("requiredItemCount")).isEqualTo(3);assertThat(gap.get("requiredItemCount")).isEqualTo(1);
        jdbc.sql("UPDATE review_item SET workflow_state='REQUESTED' WHERE id=100").update();
        jdbc.sql("UPDATE review_item SET workflow_state='EXCLUDED' WHERE id=102").update();
        gap=policies.preview(planner,new ReviewPolicyService.PreviewInput("2","GAP_ONLY"));
        assertThat(gap).containsEntry("requiredItemCount",1).containsEntry("explicitlyRequestedItemCount",1).containsEntry("excludedItemCount",1).containsEntry("requiredOrganizationCount",1);
        assertThat(gap.get("sourceSubmissionIds")).asList().containsExactly("20");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM review_policy").query(Integer.class).single()).isEqualTo(1);
    }

    /** 연간 변경도 선택 모드로 고정되고 모드 변경 후 초안 재검증은 새 회차를 남긴다.
     * Args: 없음. Returns: 없음. Raises: 검증 실패. Side Effects: 합성 연간 초안·검토·정책 이력만 생성하며 비교본 완전성만 대역으로 지정한다.
     */
    @Test void annualValidationSnapshotsModeAndRevalidationPreservesOldRounds() {
        jdbc.sql("INSERT INTO submission_request(id,annual_plan_id,type,status,requested_by,requested_at,period_key) VALUES (3,1,'ANNUAL_PLAN','OPEN',1,CURRENT_TIMESTAMP,'2031-PLAN')").update();
        jdbc.sql("INSERT INTO request_target(request_id,organization_id) VALUES (3,1)").update();
        seedSubmission(30,3,1,"APPROVED");seedSubmission(31,3,2,"DRAFT");
        var compared=spy(comparisons);
        var before=new ReviewComparisonService.Cell(1,"REV","REVENUE",2031,1,"PLAN",new java.math.BigDecimal("100"),"c".repeat(64),List.of());
        var after=new ReviewComparisonService.Cell(1,"REV","REVENUE",2031,1,"PLAN",new java.math.BigDecimal("110"),"d".repeat(64),List.of());
        doReturn(Map.of("1:2031:1:PLAN",before)).when(compared).cells(30);
        doReturn(Map.of("1:2031:1:PLAN",after)).when(compared).cells(31);
        doReturn(true).when(compared).complete(30);
        var oldContext=compared.context(31);assertThat(oldContext.get("requirementMode")).isEqualTo("GAP_ONLY");
        assertThat(compared.prepare(31,oldContext,true)).isEmpty();assertThat(service.counts(31).get("requiredCount")).isEqualTo(0);
        var oldItem=jdbc.sql("SELECT * FROM review_item WHERE submission_id=31").query().singleRow();
        jdbc.sql("UPDATE review_explanation SET answer='과거 선택 소명' WHERE review_item_id=?").param(oldItem.get("id")).update();
        var policies=policies();policies.apply(planner,"annual-policy-all",new ReviewPolicyService.ApplyInput(policies.current(planner).get("stateToken").toString(),"ALL_CHANGES"));
        var newContext=compared.context(31);assertThat(newContext).isNotEqualTo(oldContext);
        assertThat(service.counts(31).get("requiredCount")).isEqualTo(0);
        assertThat(compared.prepare(31,newContext,true)).isEmpty();
        assertThat(service.counts(31)).containsEntry("requiredCount",1).containsEntry("unansweredCount",1);
        assertThat(service.ids(31,true)).containsExactly(oldItem.get("id").toString());
        assertThat(jdbc.sql("SELECT requirement_mode FROM review_item WHERE submission_id=31").query(String.class).single()).isEqualTo("ALL_CHANGES");
        assertThat(jdbc.sql("SELECT answer FROM review_explanation WHERE review_item_id=? AND round=1").param(oldItem.get("id")).query(String.class).single()).isEqualTo("과거 선택 소명");
        assertThat(jdbc.sql("SELECT current_round FROM review_item WHERE submission_id=31").query(Integer.class).single()).isEqualTo(2);
    }

    /** 같은 합성 DB의 실제 정책 서비스를 구성한다. Args: 없음. Returns: 정책 서비스. Raises: 없음. Side Effects: 없음. */
    private ReviewPolicyService policies() { return new ReviewPolicyService(jdbc,new CommandExecutor(new JdbcTemplate(database),new DataSourceTransactionManager(database)),comparisons,service); }

    /** 현재 토큰으로 대상 참조를 만든다. Args: id 검토 ID. Returns: 명령 항목. Raises: 조회 오류. Side Effects: H2 읽기. */
    private ReviewService.ActionItem item(String id) { return new ReviewService.ActionItem(id,service.get(planner,id).get("stateToken").toString()); }

    /** 합성 제출 헤더를 추가한다. Args: id 제출, request 요청, sequence 회차, status 상태. Returns: 없음. Raises: DB 오류. Side Effects: 메모리 DB INSERT. */
    private void seedSubmission(long id,long request,int sequence,String status) {
        jdbc.sql("INSERT INTO submission(id,request_id,organization_id,seq,status,original_filename,source_object_key,source_sha256,uploaded_by,uploaded_at) VALUES (?,?,1,?,?,'synthetic.xlsx','source/test',?,2,CURRENT_TIMESTAMP)")
                .params(id,request,"DRAFT".equals(status)?null:sequence,status,"a".repeat(64)).update();
    }
}
