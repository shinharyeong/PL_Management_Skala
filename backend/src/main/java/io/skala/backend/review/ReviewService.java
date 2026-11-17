package io.skala.backend.review;

import static io.skala.backend.submission.ApiData.*;

import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 검토 근거 조회와 소명 회차 명령을 제출 잠금 아래 관리한다. */
@Service
public class ReviewService {
    private final JdbcClient jdbc;
    private final CommandExecutor commands;
    private final ReviewComparisonService comparisons;
    private static final String ROW = "SELECT i.*,s.organization_id,s.request_id,s.status AS submission_status,s.previous_submission_id,r.code,r.name,r.kind FROM review_item i JOIN submission s ON s.id=i.submission_id JOIN report_item r ON r.id=i.report_item_id WHERE i.id=? AND i.active=TRUE";

    /** 서비스 의존성을 받는다. Args: jdbc DB, commands 멱등 실행기, comparisons 원천 비교. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public ReviewService(JdbcClient jdbc, CommandExecutor commands, ReviewComparisonService comparisons) { this.jdbc=jdbc; this.commands=commands; this.comparisons=comparisons; }

    /** 필수 항목의 현재 답변·수용 상태를 센다. Args: submissionId 제출. Returns: 준비 수치. Raises: DB 오류. Side Effects: 읽기. */
    public Map<String,Object> counts(long submissionId) {
        var rows=rows("SELECT i.change_detected,i.gap_exceeded,i.review_policy_version,i.workflow_state,e.answer,e.verdict FROM review_item i JOIN review_explanation e ON e.review_item_id=i.id AND e.round=i.current_round WHERE i.submission_id=? AND i.active=TRUE",submissionId);
        int required=0,unanswered=0,pending=0;
        for(var row:rows) if(required(row)) { required++; if(row.get("answer")==null) unanswered++; if(!"ACCEPTED".equals(row.get("verdict"))) pending++; }
        return object("requiredCount",required,"unansweredCount",unanswered,"pendingReviewCount",pending);
    }

    /** 검토 상태를 제출 토큰에 연결할 안정적인 문자열로 만든다. Args: submissionId 제출. Returns: 현재 회차 상태. Raises: DB 오류. Side Effects: 읽기. */
    public String revision(long submissionId) { return comparisons.json(rows("SELECT i.id AS review_item_id,i.context_fingerprint,i.current_round,i.review_policy_version,i.workflow_state,i.workflow_note,e.* FROM review_item i JOIN review_explanation e ON e.review_item_id=i.id AND e.round=i.current_round WHERE i.submission_id=? AND i.active=TRUE ORDER BY i.id",submissionId)); }

    /** 검증 응답의 검토 ID를 읽는다. Args: submissionId 제출, unanswered 미답변만. Returns: ID 목록. Raises: DB 오류. Side Effects: 읽기. */
    public List<String> ids(long submissionId,boolean unanswered) { return rows("SELECT i.id FROM review_item i JOIN review_explanation e ON e.review_item_id=i.id AND e.round=i.current_round WHERE i.submission_id=? AND i.active=TRUE"+(unanswered?" AND "+requiredSql()+" AND e.answer IS NULL":"")+" ORDER BY i.id",submissionId).stream().map(row->row.get("id").toString()).toList(); }

    /** 조직 범위를 먼저 제한하고 검토 목록을 페이지로 읽는다.
     * Args: actor 사용자, query 필터. Returns: 목록·커서. Raises: 입력 오류. Side Effects: DB 읽기.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(Actor actor,Map<String,String> query) {
        checkQuery(query,Set.of("requestId","submissionId","organizationId","reportItemId","year","month","scenario","reason","explanationState","workflowState","cursor","limit"));
        var params=new ArrayList<Object>(); String where=" WHERE i.active=TRUE";
        if(!actor.role().equals("PLANNER")) { where+=" AND s.organization_id=?"; params.add(id(actor.organization().id())); }
        var columns=Map.of("requestId","s.request_id","submissionId","i.submission_id","organizationId","s.organization_id","reportItemId","i.report_item_id","year","i.year","month","i.month","scenario","i.scenario");
        for(var entry:columns.entrySet()) if(query.containsKey(entry.getKey())) { where+=" AND "+entry.getValue()+"=?"; params.add(filter(entry.getKey(),query.get(entry.getKey()))); }
        if(query.containsKey("reason")) where+=switch(query.get("reason")) { case "CHANGE" -> " AND i.change_detected=TRUE"; case "GAP" -> " AND i.gap_exceeded=TRUE"; case "INHERITED" -> " AND e.inherited_from_id IS NOT NULL"; default -> throw invalid("reason을 확인해 주세요."); };
        if(query.containsKey("explanationState")) where+=switch(query.get("explanationState")) { case "UNANSWERED" -> " AND e.answer IS NULL"; case "ACCEPTED" -> " AND e.verdict='ACCEPTED'"; case "ANSWERED" -> " AND e.answer IS NOT NULL AND e.verdict='PENDING' AND s.status='DRAFT'"; case "IN_REVIEW" -> " AND e.answer IS NOT NULL AND e.verdict='PENDING' AND s.status<>'DRAFT'"; default -> throw invalid("소명 상태를 확인해 주세요."); };
        if(query.containsKey("workflowState")) {
            if(!Set.of("NEEDS_REVIEW","SELECTED","IN_PROGRESS","RE_REVIEW","ACCEPTED","EXCLUDED").contains(query.get("workflowState"))) throw invalid("검토 진행 상태를 확인해 주세요.");
            where+=" AND "+workflowSql()+"=?";params.add(query.get("workflowState"));
        }
        String scope=scope(actor,query,"list");int limit=limit(query),start=offset(query.get("cursor"),scope); params.add(limit+1);params.add(start);
        var found=jdbc.sql("SELECT i.id FROM review_item i JOIN submission s ON s.id=i.submission_id JOIN review_explanation e ON e.review_item_id=i.id AND e.round=i.current_round"+where+" ORDER BY i.id LIMIT ? OFFSET ?").params(params).query().listOfRows();
        return object("items",found.stream().limit(limit).map(row->view(accessible(actor,row.get("id").toString()),false)).toList(),"nextCursor",found.size()>limit?cursor(start+limit,scope):null);
    }

    /** 검토 상세를 권한 범위에서 읽는다. Args: actor 사용자, reviewId 검토. Returns: 상세 DTO. Raises: 404 범위 밖. Side Effects: 읽기. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> get(Actor actor,String reviewId) { return view(accessible(actor,reviewId),true); }

    /** 같은 조직의 검증된 두 제출본을 선택한 시나리오·월로 읽기 전용 비교한다.
     * Args: actor 사용자, submissionId 현재 제출, query baselineSubmissionId·scenario·baselineScenario·month.
     * Returns: 항목별 기준값·현재값·차이·비율과 양쪽 원천 조회 링크. Raises: 권한·기간·시나리오·검증 근거 오류.
     * Side Effects: DB 조회와 정확한 십진 계산만 수행하며 고정된 검증·소명·마스터를 변경하지 않는다.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> compareSubmissions(Actor actor,String submissionId,Map<String,String> query) {
        checkQuery(query,Set.of("baselineSubmissionId","scenario","baselineScenario","month"));
        if(!query.containsKey("baselineSubmissionId")) throw invalid("비교할 기준 제출본을 선택해 주세요.");
        var current=comparisonSubmission(actor,submissionId);var baseline=comparisonSubmission(actor,query.get("baselineSubmissionId"));
        if(!current.get("organization_id").equals(baseline.get("organization_id"))||!current.get("year").equals(baseline.get("year")))
            throw invalid("같은 조직·연도의 제출본만 비교할 수 있습니다.");
        String scenario=query.getOrDefault("scenario","ANNUAL_PLAN".equals(current.get("type"))?"PLAN":"ACTUAL");
        String baselineScenario=query.getOrDefault("baselineScenario","ANNUAL_PLAN".equals(baseline.get("type"))?"PLAN":scenario);
        filter("scenario",scenario);filter("scenario",baselineScenario);
        Integer month=query.containsKey("month")?(Integer)filter("month",query.get("month")):null;
        var before=comparisons.cells(number(baseline.get("id")));var after=comparisons.cells(number(current.get("id")));
        var metadata=new HashMap<Long,Map<String,Object>>();for(var item:rows("SELECT id,code,name,kind FROM report_item"))metadata.put(number(item.get("id")),item);
        var selected=new TreeMap<String,ReviewComparisonService.Cell>();
        for(var cell:before.values())if(cell.scenario().equals(baselineScenario)&&(month==null||cell.month()==month))selected.put(cell.reportId()+":"+String.format("%02d",cell.month()),cell);
        for(var cell:after.values())if(cell.scenario().equals(scenario)&&(month==null||cell.month()==month))selected.put(cell.reportId()+":"+String.format("%02d",cell.month()),cell);
        var items=new ArrayList<Map<String,Object>>();
        for(var cell:selected.values()) {
            var left=before.get(comparisons.key(cell.reportId(),cell.year(),cell.month(),baselineScenario));
            var right=after.get(comparisons.key(cell.reportId(),cell.year(),cell.month(),scenario));
            BigDecimal delta=left==null||right==null?null:right.amount().subtract(left.amount());
            var report=metadata.get(cell.reportId());
            items.add(object("reportItem",object("id",Long.toString(cell.reportId()),"code",report.get("code"),"name",report.get("name"),"kind",report.get("kind")),
                    "year",cell.year(),"month",cell.month(),"scenario",scenario,"baselineScenario",baselineScenario,
                    "baselineAmount",left==null?null:left.amount().toPlainString(),"currentAmount",right==null?null:right.amount().toPlainString(),
                    "gapAmount",delta==null?null:delta.toPlainString(),"gapRate",delta==null||left.amount().signum()==0?null:delta.multiply(new BigDecimal("100")).divide(left.amount().abs(),8,RoundingMode.HALF_UP).toPlainString(),
                    "valueStatus",left==null?"MISSING_BASELINE":right==null?"MISSING_CURRENT":"AVAILABLE",
                    "baselineEvidenceUrl",financialEvidenceUrl(baseline.get("id").toString(),cell,baselineScenario),
                    "currentEvidenceUrl",financialEvidenceUrl(current.get("id").toString(),cell,scenario)));
        }
        return object("submissionId",submissionId,"baselineSubmissionId",query.get("baselineSubmissionId"),"organizationId",current.get("organization_id").toString(),
                "year",current.get("year"),"month",month,"scenario",scenario,"baselineScenario",baselineScenario,"items",items);
    }

    /** 비교 전에 소속과 원본 대조가 끝난 검증 스냅샷을 확인한다.
     * Args: actor 사용자, submissionId 제출. Returns: 내부 제출 문맥. Raises: 접근 범위 404·검증 불완전 409. Side Effects: DB 읽기.
     */
    private Map<String,Object> comparisonSubmission(Actor actor,String submissionId) {
        var found=rows("SELECT s.*,r.type,p.year FROM submission s JOIN submission_request r ON r.id=s.request_id JOIN annual_plan p ON p.id=r.annual_plan_id WHERE s.id=?",id(submissionId));
        if(found.isEmpty()||!actor.canAccessOrganization(found.getFirst().get("organization_id").toString()))throw new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","자료를 찾을 수 없습니다.");
        if(!comparisons.complete(id(submissionId)))throw conflict("COMPARISON_SOURCE_UNAVAILABLE","원본 대조가 완료된 검증 제출본을 선택해 주세요.");
        return found.getFirst();
    }

    /** 비교 금액의 양쪽 원천을 기존 상세 조회 경로로 연결한다.
     * Args: submissionId 제출, cell 항목·기간, scenario 시나리오. Returns: API 상대 링크. Raises: 없음. Side Effects: 없음.
     */
    private String financialEvidenceUrl(String submissionId,ReviewComparisonService.Cell cell,String scenario) {
        return "/api/v1/submissions/"+submissionId+"/financial-values?year="+cell.year()+"&month="+cell.month()+"&scenario="+scenario+("OP".equals(cell.code())?"":"&reportItemId="+cell.reportId());
    }

    /** 원본끼리 임의 짝짓기 없이 의미 다중집합과 정확한 전후 합계를 반환한다.
     * Args: actor 사용자, reviewId 검토, query reason/basis/페이지. Returns: 근거 페이지. Raises: 비교 선택·권한 오류. Side Effects: 읽기.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> evidence(Actor actor,String reviewId,Map<String,String> query) {
        checkQuery(query,Set.of("reason","basis","limit","cursor")); var row=accessible(actor,reviewId);String reason=query.get("reason"),basis=query.get("basis");
        Long baseline=null;String scenario=null;
        if("CHANGE".equals(reason)&&basis==null&&Boolean.TRUE.equals(row.get("change_detected"))) { baseline=nullable(row.get("previous_submission_id"));scenario=row.get("scenario").toString(); }
        else if("GAP".equals(reason)&&Set.of("PLAN","PREVIOUS").contains(Objects.toString(basis,""))) {
            var found=rows("SELECT * FROM gap_comparison WHERE review_item_id=? AND basis=?",id(reviewId),basis);
            if(found.isEmpty()) throw selection(); baseline=nullable(found.getFirst().get("baseline_submission_id"));scenario=found.getFirst().get("baseline_scenario").toString();
        } else throw selection();
        long current=number(row.get("submission_id")),report=number(row.get("report_item_id"));int year=(int)number(row.get("year")),month=(int)number(row.get("month"));
        var before=baseline==null?null:comparisons.cells(baseline).get(comparisons.key(report,year,month,scenario));
        var after=comparisons.cells(current).get(comparisons.key(report,year,month,row.get("scenario").toString()));
        var items=new ArrayList<Map<String,Object>>(); if(before!=null) evidenceItems(items,"BEFORE",baseline,before); if(after!=null)evidenceItems(items,"AFTER",current,after);
        BigDecimal beforeTotal=baseline==null?null:before==null?BigDecimal.ZERO:before.amount(),afterTotal=after==null?BigDecimal.ZERO:after.amount();
        String scope=scope(actor,query,"evidence:"+reviewId+":"+token(row));int start=offset(query.get("cursor"),scope),end=Math.min(items.size(),start+limit(query));
        return object("reviewItemId",reviewId,"reason",reason,"basis",basis,"baselineSubmissionId",baseline==null?null:baseline.toString(),"baselineScenario",scenario,"currentSubmissionId",Long.toString(current),"currentScenario",row.get("scenario"),"comparisonMode",baseline==null?"NOT_APPLICABLE":"MULTISET","beforeTotal",beforeTotal==null?null:beforeTotal.toPlainString(),"afterTotal",afterTotal.toPlainString(),"netDelta",beforeTotal==null?null:afterTotal.subtract(beforeTotal).toPlainString(),"items",items.subList(Math.min(start,items.size()),end),"nextCursor",end<items.size()?cursor(end,scope):null);
    }

    /** 전후 원천 항목을 원본 주소와 함께 추가한다. Args: items 결과, side 방향, submission 제출, cell 칸. Returns: 없음. Raises: JSON 오류. Side Effects: 메모리 목록만 변경. */
    private void evidenceItems(List<Map<String,Object>> items,String side,long submission,ReviewComparisonService.Cell cell) {
        for(var fact:cell.facts()) { var attributes=comparisons.attributes(fact);items.add(object("side",side,"groupKey",hash(comparisons.json(attributes).getBytes(StandardCharsets.UTF_8)),"lineId",fact.get("line_id").toString(),"valueId",fact.get("value_id").toString(),"amount",fact.get("amount").toString(),"semanticAttributes",attributes,"source",object("submissionId",Long.toString(submission),"sheet",fact.get("source_sheet"),"cell",fact.get("source_cell")))); }
    }

    /** 현재 조직의 답변을 회차·토큰·잠금 규칙에 따라 저장한다.
     * Args: actor 담당자, reviewId 검토, key 멱등키, input 토큰·회차·답변. Returns: 상세 성공 응답. Raises: 권한·회차·상태 충돌. Side Effects: 현재 답변 및 멱등 결과를 원자 저장.
     */
    public CommandExecutor.Result answer(Actor actor,String reviewId,String key,AnswerInput input) {
        actor.requireRole("ORG_MANAGER");accessible(actor,reviewId);
        return commands.execute(actor,"PUT /api/v1/review-items/"+id(reviewId)+"/answer",key,fingerprint(input),()->{
            var row=lock(actor,reviewId);var explanation=current(row);round(row,input.round());
            if("EXCLUDED".equals(row.get("workflow_state"))) throw conflict("REVIEW_EXCLUDED","검토 대상에서 제외된 항목입니다.");
            if(explanation.get("inherited_from_id")!=null) throw conflict("EXPLANATION_LOCKED","이어받은 수용 소명은 변경할 수 없습니다.");
            if(!Set.of("DRAFT","PENDING").contains(row.get("submission_status"))) throw conflict("EXPLANATION_LOCKED","종료된 제출본의 답변은 변경할 수 없습니다.");
            if(Objects.equals(explanation.get("answer"),input.answer())&&Objects.equals(explanation.get("answered_by"),id(actor.id())))return result(view(row,true));
            assertToken(row,input.expectedStateToken());
            if(!"PENDING".equals(explanation.get("verdict"))||("PENDING".equals(row.get("submission_status"))&&(explanation.get("answer")!=null||explanation.get("requested_by")==null))) throw conflict("EXPLANATION_LOCKED","현재 답변은 잠겨 있습니다.");
            jdbc.sql("UPDATE review_explanation SET answer=?,answered_by=?,answered_at=? WHERE id=?").params(input.answer(),id(actor.id()),OffsetDateTime.now(ZoneOffset.UTC),explanation.get("id")).update();return result(view(accessible(actor,reviewId),true));
        });
    }

    /** 제출 중 소명을 수용하거나 새 보완 회차를 원자적으로 연다.
     * Args: actor 경영기획, reviewId 검토, key 멱등키, input 토큰·회차·판단. Returns: 갱신 상세. Raises: 권한·회차·상태 충돌. Side Effects: 회차 상태와 멱등 결과 저장.
     */
    public CommandExecutor.Result decide(Actor actor,String reviewId,String key,DecisionInput input) {
        actor.requireRole("PLANNER");accessible(actor,reviewId);
        if(!Set.of("ACCEPT","REQUEST_CHANGES").contains(input.action())||("REQUEST_CHANGES".equals(input.action())&&(input.note()==null||input.note().isBlank())))throw invalid("수용 또는 보완 요청과 사유를 확인해 주세요.");
        return commands.execute(actor,"POST /api/v1/review-items/"+id(reviewId)+"/decisions",key,fingerprint(input),()->{
            var row=lock(actor,reviewId);round(row,input.round());assertToken(row,input.expectedStateToken());var explanation=current(row);
            if("EXCLUDED".equals(row.get("workflow_state"))) throw conflict("REVIEW_EXCLUDED","검토 대상에서 제외된 항목입니다.");
            if(!"PENDING".equals(row.get("submission_status"))||explanation.get("answer")==null||!"PENDING".equals(explanation.get("verdict")))throw conflict("EXPLANATION_LOCKED","제출된 현재 답변만 검토할 수 있습니다.");
            var now=OffsetDateTime.now(ZoneOffset.UTC);boolean accept="ACCEPT".equals(input.action());
            jdbc.sql("UPDATE review_explanation SET verdict=?,reviewed_by=?,reviewed_at=?,review_note=? WHERE id=?").params(accept?"ACCEPTED":"REJECTED",id(actor.id()),now,input.note(),explanation.get("id")).update();
            if(!accept) { int next=input.round()+1;jdbc.sql("INSERT INTO review_explanation(review_item_id,round,request_note,requested_by,requested_at,verdict) VALUES (?,?,?,?,?,'PENDING')").params(id(reviewId),next,input.note(),id(actor.id()),now).update();jdbc.sql("UPDATE review_item SET current_round=?,workflow_state='REQUESTED',workflow_note=? WHERE id=?").params(next,input.note(),id(reviewId)).update(); }
            return result(view(accessible(actor,reviewId),true));
        });
    }

    /** 선택한 제출 중 항목을 검토 대상으로 지정하거나 제외하고 소명을 일괄 요청한다.
     * Args: actor 기획자, key 멱등키, input 동일 요청의 항목·토큰·행위·메시지·기한.
     * Returns: 갱신한 항목 목록. Raises: 권한·잘못된 대상·상태·토큰 충돌.
     * Side Effects: 조직·제출·검토를 일관된 순서로 잠그고 상태·요청 회차·행위 이력을 한 트랜잭션에 저장한다.
     */
    public CommandExecutor.Result actions(Actor actor,String key,BulkActionInput input) {
        actor.requireRole("PLANNER");
        if(input.items()==null||input.items().isEmpty()||input.items().size()>100
                ||!Set.of("SELECT","EXCLUDE","REQUEST_EXPLANATION").contains(Objects.toString(input.action(),""))) throw invalid("행위와 1~100개 검토 항목을 선택해 주세요.");
        if(!"SELECT".equals(input.action())&&(input.note()==null||input.note().isBlank())) throw invalid("제외 사유 또는 소명 요청 메시지가 필요합니다.");
        if("REQUEST_EXPLANATION".equals(input.action())&&input.dueAt()==null) throw invalid("소명 회신 기한이 필요합니다.");
        var unique=new HashSet<Long>();
        for(var item:input.items()) {
            if(item==null||item.expectedStateToken()==null||item.expectedStateToken().isBlank()||!unique.add(id(item.reviewItemId()))) throw invalid("항목과 현재 토큰을 중복 없이 전달해 주세요.");
            accessible(actor,item.reviewItemId());
        }
        return commands.execute(actor,"POST /api/v1/review-items/actions",key,fingerprint(input),()->{
            var selected=input.items().stream().sorted(Comparator.comparingLong(item->number(accessible(actor,item.reviewItemId()).get("organization_id")))).toList();
            var locked=new ArrayList<Map<String,Object>>();Long request=null;
            for(var item:selected) {
                var row=lock(actor,item.reviewItemId());assertToken(row,item.expectedStateToken());
                if(!"PENDING".equals(row.get("submission_status"))) throw conflict("REVIEW_LOCKED","검토 대기 제출본의 항목만 처리할 수 있습니다.");
                if("SELECT".equals(input.action())&&!Set.of("NEEDS_REVIEW","SELECTED").contains(workflowState(row,current(row))))
                    throw conflict("REVIEW_STAGE_CONFLICT","검토 필요 상태의 항목만 새로 선정할 수 있습니다.");
                if(request!=null&&request!=number(row.get("request_id"))) throw invalid("동일한 제출 요청의 항목만 함께 처리할 수 있습니다.");
                request=number(row.get("request_id"));locked.add(row);
            }
            var now=OffsetDateTime.now(ZoneOffset.UTC);
            for(var row:locked) {
                int round=(int)number(row.get("current_round"));
                if("REQUEST_EXPLANATION".equals(input.action())) {
                    var explanation=current(row);
                    if(explanation.get("answer")!=null||!"PENDING".equals(explanation.get("verdict"))) {
                        if("PENDING".equals(explanation.get("verdict"))) jdbc.sql("UPDATE review_explanation SET verdict='REJECTED',reviewed_by=?,reviewed_at=?,review_note=? WHERE id=?")
                                .params(id(actor.id()),now,input.note(),explanation.get("id")).update();
                        round++;
                        jdbc.sql("INSERT INTO review_explanation(review_item_id,round,request_note,requested_by,requested_at,due_at,verdict) VALUES (?,?,?,?,?,?,'PENDING')")
                                .params(row.get("id"),round,input.note(),id(actor.id()),now,input.dueAt()).update();
                    } else jdbc.sql("UPDATE review_explanation SET request_note=?,requested_by=?,requested_at=?,due_at=? WHERE id=?")
                            .params(input.note(),id(actor.id()),now,input.dueAt(),explanation.get("id")).update();
                }
                String state=switch(input.action()){case "SELECT"->"SELECTED";case "EXCLUDE"->"EXCLUDED";default->"REQUESTED";};
                jdbc.sql("UPDATE review_item SET workflow_state=?,workflow_note=?,current_round=? WHERE id=?").params(state,input.note(),round,row.get("id")).update();
                jdbc.sql("INSERT INTO review_workflow_event(review_item_id,action,explanation_round,note,due_at,acted_by,acted_at) VALUES (?,?,?,?,?,?,?)")
                        .params(row.get("id"),input.action(),round,input.note(),"REQUEST_EXPLANATION".equals(input.action())?input.dueAt():null,id(actor.id()),now).update();
            }
            return result(object("items",locked.stream().map(row->view(accessible(actor,row.get("id").toString()),true)).toList()));
        });
    }

    /** 고정된 검증 정책과 담당자의 명시 요청·제외 결정을 적용해 필수 여부를 계산한다.
     * Args: row 검토 행. Returns: 제출·승인에 소명이 필요한지. Raises: 없음. Side Effects: 없음.
     */
    private boolean required(Map<String,Object> row) {
        return !"EXCLUDED".equals(row.get("workflow_state"))&&("REQUESTED".equals(row.get("workflow_state"))||Boolean.TRUE.equals(row.get("gap_exceeded"))
                ||(!Set.of("monthly-actual/2.0","annual-plan/2.0").contains(Objects.toString(row.get("review_policy_version"),"legacy/1.0"))&&Boolean.TRUE.equals(row.get("change_detected"))));
    }

    /** 목록/미답변 조회에 동일한 필수 정책을 적용한다.
     * Args: 없음. Returns: 내부 고정 SQL 조건. Raises: 없음. Side Effects: 없음.
     */
    private String requiredSql() { return "i.workflow_state<>'EXCLUDED' AND (i.workflow_state='REQUESTED' OR i.gap_exceeded=TRUE OR (i.review_policy_version NOT IN ('monthly-actual/2.0','annual-plan/2.0') AND i.change_detected=TRUE))"; }

    /** 소명 회차와 담당자의 선정을 화면 진행 상태로 조합한다.
     * Args: row 검토 행, explanation 현재 회차. Returns: 공개 진행 상태. Raises: 없음. Side Effects: 없음.
     */
    private String workflowState(Map<String,Object> row,Map<String,Object> explanation) {
        if("EXCLUDED".equals(row.get("workflow_state"))) return "EXCLUDED";
        if("ACCEPTED".equals(explanation.get("verdict"))) return "ACCEPTED";
        if("REQUESTED".equals(row.get("workflow_state"))) return explanation.get("answer")==null?"IN_PROGRESS":"RE_REVIEW";
        return Objects.toString(row.get("workflow_state"),"NEEDS_REVIEW");
    }

    /** 화면과 같은 진행 상태로 서버 목록을 필터링한다.
     * Args: 없음. Returns: 내부 고정 CASE 표현식. Raises: 없음. Side Effects: 없음.
     */
    private String workflowSql() { return "CASE WHEN i.workflow_state='EXCLUDED' THEN 'EXCLUDED' WHEN e.verdict='ACCEPTED' THEN 'ACCEPTED' WHEN i.workflow_state='REQUESTED' AND e.answer IS NULL THEN 'IN_PROGRESS' WHEN i.workflow_state='REQUESTED' THEN 'RE_REVIEW' ELSE i.workflow_state END"; }

    /** 조직·제출·검토를 승인과 같은 순서로 잠근다. Args: actor 사용자, reviewId 검토. Returns: 최신 행. Raises: 권한·대상 충돌. Side Effects: 현재 트랜잭션 잠금. */
    private Map<String,Object> lock(Actor actor,String reviewId) {
        var row=accessible(actor,reviewId);jdbc.sql("SELECT id FROM organization WHERE id=? FOR UPDATE").param(row.get("organization_id")).query(Long.class).single();jdbc.sql("SELECT id FROM submission WHERE id=? FOR UPDATE").param(row.get("submission_id")).query(Long.class).single();jdbc.sql("SELECT id FROM review_item WHERE id=? FOR UPDATE").param(id(reviewId)).query(Long.class).single();
        row=accessible(actor,reviewId);
        if("DRAFT".equals(row.get("submission_status"))&&!comparisons.currentContext(number(row.get("submission_id"))))throw conflict("REVIEW_CONTEXT_CHANGED","비교 기준이 바뀌어 다시 검증해야 합니다.");
        if(rows("SELECT request_id FROM request_target WHERE request_id=? AND organization_id=? AND excluded=FALSE",row.get("request_id"),row.get("organization_id")).isEmpty())throw conflict("REQUEST_TARGET_EXCLUDED","제출 대상에서 제외된 조직입니다.");return row;
    }

    /** 내부 행을 검토 DTO로 변환한다. Args: row 검토, detail 전체 회차 여부. Returns: DTO. Raises: DB 오류. Side Effects: 읽기. */
    private Map<String,Object> view(Map<String,Object> row,boolean detail) {
        var explanation=current(row);var history=detail?rows("SELECT * FROM review_explanation WHERE review_item_id=? ORDER BY round",row.get("id")):List.of(explanation);
        var result=object("id",row.get("id").toString(),"submissionId",row.get("submission_id").toString(),"organizationId",row.get("organization_id").toString(),"reportItem",object("id",row.get("report_item_id").toString(),"code",row.get("code"),"name",row.get("name"),"kind",row.get("kind")),"year",row.get("year"),"month",row.get("month"),"scenario",row.get("scenario"),"amountPresent",row.get("amount_present"),"currentAmount",row.get("current_amount")==null?null:row.get("current_amount").toString(),"changeDetected",row.get("change_detected"),"gapExceeded",row.get("gap_exceeded"),"required",required(row),"reviewPolicyVersion",row.get("review_policy_version"),"workflowState",workflowState(row,explanation),"workflowNote",row.get("workflow_note"),"requestDueAt",instant(explanation.get("due_at")),"explanationState",explanation.get("answer")==null?"UNANSWERED":"ACCEPTED".equals(explanation.get("verdict"))?"ACCEPTED":"DRAFT".equals(row.get("submission_status"))?"ANSWERED":"IN_REVIEW","currentRound",row.get("current_round"),"stateToken",token(row),"explanations",history.stream().map(this::explanation).toList());
        if(detail) result.put("workflowHistory",rows("SELECT * FROM review_workflow_event WHERE review_item_id=? ORDER BY id",row.get("id")).stream().map(event->object("id",event.get("id").toString(),"action",event.get("action"),"round",event.get("explanation_round"),"note",event.get("note"),"dueAt",instant(event.get("due_at")),"actedBy",actor(event.get("acted_by")),"actedAt",instant(event.get("acted_at")))).toList());
        result.put("comparisons",rows("SELECT c.*,t.scenario,t.percent,t.amount,t.percent_enabled,t.amount_enabled FROM gap_comparison c JOIN gap_threshold t ON t.id=c.threshold_id WHERE c.review_item_id=? ORDER BY c.id",row.get("id")).stream().map(c->object("basis",c.get("basis"),"baselineSubmissionId",c.get("baseline_submission_id").toString(),"baselineScenario",c.get("baseline_scenario"),"baselineAmount",c.get("baseline_amount").toString(),"currentAmount",c.get("current_amount").toString(),"gapAmount",c.get("gap_amount").toString(),"gapRate",c.get("gap_rate")==null?null:c.get("gap_rate").toString(),"threshold",object("id",c.get("threshold_id").toString(),"scenario",c.get("scenario"),"percent",c.get("percent").toString(),"amount",c.get("amount").toString(),"percentEnabled",c.get("percent_enabled"),"amountEnabled",c.get("amount_enabled")),"exceeded",c.get("exceeded"))).toList());return result;
    }

    /** 회차와 이어받은 출처를 표시 DTO로 읽는다. Args: e 회차. Returns: 소명 DTO. Raises: DB 오류. Side Effects: 읽기. */
    private Map<String,Object> explanation(Map<String,Object> e) {
        Map<String,Object> inherited=null;
        if(e.get("inherited_from_id")!=null) { var old=rows("SELECT e.id,i.id AS review_id,i.submission_id,s.seq,r.type,r.month,p.year FROM review_explanation e JOIN review_item i ON i.id=e.review_item_id JOIN submission s ON s.id=i.submission_id JOIN submission_request r ON r.id=s.request_id JOIN annual_plan p ON p.id=r.annual_plan_id WHERE e.id=?",e.get("inherited_from_id")).getFirst();inherited=object("explanationId",old.get("id").toString(),"reviewItemId",old.get("review_id").toString(),"submissionId",old.get("submission_id").toString(),"versionLabel",old.get("year")+"-"+("ANNUAL_PLAN".equals(old.get("type"))?"PLAN":String.format("%02d",number(old.get("month"))))+"-"+String.format("%02d",number(old.get("seq")))); }
        return object("id",e.get("id").toString(),"round",e.get("round"),"requestNote",e.get("request_note"),"requestedBy",actor(e.get("requested_by")),"requestedAt",instant(e.get("requested_at")),"dueAt",instant(e.get("due_at")),"answer",e.get("answer"),"answeredBy",actor(e.get("answered_by")),"answeredAt",instant(e.get("answered_at")),"verdict",e.get("verdict"),"reviewedBy",actor(e.get("reviewed_by")),"reviewedAt",instant(e.get("reviewed_at")),"reviewNote",e.get("review_note"),"inheritedFromId",e.get("inherited_from_id")==null?null:e.get("inherited_from_id").toString(),"inheritedFrom",inherited);
    }

    /** 행위자 표시 정보를 읽는다. Args: id nullable 사용자. Returns: ActorRef/null. Raises: DB 오류. Side Effects: 읽기. */
    public Map<String,Object> actor(Object id) { if(id==null)return null;var a=rows("SELECT id,name,email,role FROM app_user WHERE id=?",id).getFirst();return object("id",a.get("id").toString(),"name",a.get("name"),"email",a.get("email"),"role",a.get("role")); }
    /** 현재 회차를 읽는다. Args: row 검토. Returns: 회차. Raises: DB 오류. Side Effects: 읽기. */
    private Map<String,Object> current(Map<String,Object> row) { return rows("SELECT * FROM review_explanation WHERE review_item_id=? AND round=?",row.get("id"),row.get("current_round")).getFirst(); }
    /** 검토 토큰을 만든다. Args: row 검토. Returns: 해시. Raises: JSON 오류. Side Effects: 읽기. */
    private String token(Map<String,Object> row) { return fingerprint(object("item",row,"explanation",current(row))); }
    /** 기대 토큰을 검사한다. Args: row 검토, expected 기대값. Returns: 없음. Raises: 변경 충돌. Side Effects: 읽기. */
    private void assertToken(Map<String,Object> row,String expected) { if(!token(row).equals(expected))throw conflict("RESOURCE_CHANGED","검토 상태가 변경되었습니다. 다시 조회해 주세요."); }
    /** 현재 회차를 검사한다. Args: row 검토, round 요청회차. Returns: 없음. Raises: 회차 충돌. Side Effects: 없음. */
    private void round(Map<String,Object> row,int round) { if(number(row.get("current_round"))!=round)throw conflict("EXPLANATION_ROUND_CHANGED","현재 소명 회차가 변경되었습니다."); }
    /** 검토 행을 소속 범위에서 조회한다. Args: actor 사용자, reviewId 검토. Returns: 내부 행. Raises: 404 범위 밖. Side Effects: 읽기. */
    private Map<String,Object> accessible(Actor actor,String reviewId) { var found=rows(ROW,id(reviewId));if(found.isEmpty()||!actor.canAccessOrganization(found.getFirst().get("organization_id").toString()))throw new ApiException(HttpStatus.NOT_FOUND,"REVIEW_ITEM_NOT_FOUND","검토 항목을 찾을 수 없습니다.");return found.getFirst(); }
    /** 허용 필터를 확인한다. Args: query 입력, allowed 허용집합. Returns: 없음. Raises: 입력 오류. Side Effects: 없음. */
    public void checkQuery(Map<String,String> query,Set<String> allowed) { if(!allowed.containsAll(query.keySet()))throw invalid("지원하지 않는 조회 조건입니다."); }
    /** 필터 값을 검증한다. Args: key 항목, value 입력. Returns: 타입 변환값. Raises: 입력 오류. Side Effects: 없음. */
    private Object filter(String key,String value) { if(key.endsWith("Id"))return id(value);if(key.equals("scenario")){if(!Set.of("PLAN","ACTUAL","ESTIMATE").contains(value))throw invalid("시나리오를 확인해 주세요.");return value;}try{int n=Integer.parseInt(value);if(key.equals("month")?(n<1||n>12):(n<2000||n>9999))throw new IllegalArgumentException();return n;}catch(IllegalArgumentException ex){throw invalid("기간을 확인해 주세요.");} }
    /** 페이지 크기를 검증한다. Args: query 조건. Returns: 1~100. Raises: 입력 오류. Side Effects: 없음. */
    private int limit(Map<String,String> query) { try{int n=Integer.parseInt(query.getOrDefault("limit","50"));if(n<1||n>100)throw new IllegalArgumentException();return n;}catch(IllegalArgumentException ex){throw invalid("limit는 1~100입니다.");} }
    /** 권한·필터를 커서에 고정한다. Args: actor 사용자, query 조건, name 조회종류. Returns: 범위문자열. Raises: JSON 오류. Side Effects: 없음. */
    private String scope(Actor actor,Map<String,String> query,String name) { var filters=new TreeMap<>(query);filters.remove("cursor");return comparisons.json(List.of(actor.id(),actor.role(),Objects.toString(actor.organization(),""),name,filters)); }
    /** JSON 해시를 만든다. Args: value 값. Returns: SHA256. Raises: 직렬화 오류. Side Effects: 없음. */
    private String fingerprint(Object value) { return hash(comparisons.json(value).getBytes(StandardCharsets.UTF_8)); }
    /** 성공 명령 결과를 만든다. Args: value DTO. Returns: 응답. Raises: 직렬화 오류. Side Effects: 없음. */
    private CommandExecutor.Result result(Object value) { return new CommandExecutor.Result(200,comparisons.json(value),null); }
    /** 고정 SQL을 읽는다. Args: sql 쿼리, params 값. Returns: 행목록. Raises: DB 오류. Side Effects: 읽기. */
    private List<Map<String,Object>> rows(String sql,Object...params) { return jdbc.sql(sql).params(params).query().listOfRows(); }
    /** 숫자를 long으로 읽는다. Args: value DB값. Returns: 정수. Raises: 형식 오류. Side Effects: 없음. */
    private long number(Object value) { return ((Number)value).longValue(); }
    /** nullable ID를 읽는다. Args: value 값. Returns: 정수/null. Raises: 형식 오류. Side Effects: 없음. */
    private Long nullable(Object value) { return value==null?null:number(value); }
    /** 입력 예외를 만든다. Args: message 안내. Returns: 400 예외. Raises: 없음. Side Effects: 없음. */
    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message); }
    /** 상태 예외를 만든다. Args: code 코드, message 안내. Returns: 409 예외. Raises: 없음. Side Effects: 없음. */
    private ApiException conflict(String code,String message) { return new ApiException(HttpStatus.CONFLICT,code,message); }
    /** 비교 선택 오류를 만든다. Args: 없음. Returns: 422 예외. Raises: 없음. Side Effects: 없음. */
    private ApiException selection() { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_COMPARISON_SELECTION","해당 검토 항목의 비교 기준을 선택해 주세요."); }
    /** 답변 입력이며 상태·시각·행위자는 서버가 결정한다. */
    public record AnswerInput(@NotBlank String expectedStateToken,@Min(1) int round,@NotBlank @Size(max=4000) String answer) {}
    /** 검토 입력이며 새 회차는 서버가 결정한다. */
    public record DecisionInput(@NotBlank String expectedStateToken,@Min(1) int round,@NotBlank String action,@Size(max=4000) String note) {}
    /** 일괄 처리할 개별 대상이며 현재 상태 토큰은 항목 조회 결과를 사용한다. */
    public record ActionItem(@NotBlank String reviewItemId,@NotBlank String expectedStateToken) {}
    /** 기획자의 선택·제외·소명 요청 입력이며 원본 금액이나 확정본은 변경하지 않는다. */
    public record BulkActionInput(@NotBlank String action,@NotEmpty @Size(max=100) List<@Valid ActionItem> items,@Size(max=4000) String note,OffsetDateTime dueAt) {}
}
