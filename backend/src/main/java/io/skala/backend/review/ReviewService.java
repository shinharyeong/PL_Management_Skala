package io.skala.backend.review;

import static io.skala.backend.submission.ApiData.*;

import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.Actor;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
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
        var rows=rows("SELECT i.change_detected,i.gap_exceeded,e.answer,e.verdict FROM review_item i JOIN review_explanation e ON e.review_item_id=i.id AND e.round=i.current_round WHERE i.submission_id=? AND i.active=TRUE",submissionId);
        int required=0,unanswered=0,pending=0;
        for(var row:rows) if(Boolean.TRUE.equals(row.get("change_detected"))||Boolean.TRUE.equals(row.get("gap_exceeded"))) { required++; if(row.get("answer")==null) unanswered++; if(!"ACCEPTED".equals(row.get("verdict"))) pending++; }
        return object("requiredCount",required,"unansweredCount",unanswered,"pendingReviewCount",pending);
    }

    /** 검토 상태를 제출 토큰에 연결할 안정적인 문자열로 만든다. Args: submissionId 제출. Returns: 현재 회차 상태. Raises: DB 오류. Side Effects: 읽기. */
    public String revision(long submissionId) { return comparisons.json(rows("SELECT i.id,i.context_fingerprint,i.current_round,e.answer,e.answered_by,e.answered_at,e.verdict,e.reviewed_at FROM review_item i JOIN review_explanation e ON e.review_item_id=i.id AND e.round=i.current_round WHERE i.submission_id=? AND i.active=TRUE ORDER BY i.id",submissionId)); }

    /** 검증 응답의 검토 ID를 읽는다. Args: submissionId 제출, unanswered 미답변만. Returns: ID 목록. Raises: DB 오류. Side Effects: 읽기. */
    public List<String> ids(long submissionId,boolean unanswered) { return rows("SELECT i.id FROM review_item i JOIN review_explanation e ON e.review_item_id=i.id AND e.round=i.current_round WHERE i.submission_id=? AND i.active=TRUE"+(unanswered?" AND (i.change_detected=TRUE OR i.gap_exceeded=TRUE) AND e.answer IS NULL":"")+" ORDER BY i.id",submissionId).stream().map(row->row.get("id").toString()).toList(); }

    /** 조직 범위를 먼저 제한하고 검토 목록을 페이지로 읽는다.
     * Args: actor 사용자, query 필터. Returns: 목록·커서. Raises: 입력 오류. Side Effects: DB 읽기.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(Actor actor,Map<String,String> query) {
        checkQuery(query,Set.of("requestId","submissionId","organizationId","reportItemId","year","month","scenario","reason","explanationState","cursor","limit"));
        var params=new ArrayList<Object>(); String where=" WHERE i.active=TRUE";
        if(!actor.role().equals("PLANNER")) { where+=" AND s.organization_id=?"; params.add(id(actor.organization().id())); }
        var columns=Map.of("requestId","s.request_id","submissionId","i.submission_id","organizationId","s.organization_id","reportItemId","i.report_item_id","year","i.year","month","i.month","scenario","i.scenario");
        for(var entry:columns.entrySet()) if(query.containsKey(entry.getKey())) { where+=" AND "+entry.getValue()+"=?"; params.add(filter(entry.getKey(),query.get(entry.getKey()))); }
        if(query.containsKey("reason")) where+=switch(query.get("reason")) { case "CHANGE" -> " AND i.change_detected=TRUE"; case "GAP" -> " AND i.gap_exceeded=TRUE"; case "INHERITED" -> " AND e.inherited_from_id IS NOT NULL"; default -> throw invalid("reason을 확인해 주세요."); };
        if(query.containsKey("explanationState")) where+=switch(query.get("explanationState")) { case "UNANSWERED" -> " AND e.answer IS NULL"; case "ACCEPTED" -> " AND e.verdict='ACCEPTED'"; case "ANSWERED" -> " AND e.answer IS NOT NULL AND e.verdict='PENDING' AND s.status='DRAFT'"; case "IN_REVIEW" -> " AND e.answer IS NOT NULL AND e.verdict='PENDING' AND s.status<>'DRAFT'"; default -> throw invalid("소명 상태를 확인해 주세요."); };
        String scope=scope(actor,query,"list");int limit=limit(query),start=offset(query.get("cursor"),scope); params.add(limit+1);params.add(start);
        var found=jdbc.sql("SELECT i.id FROM review_item i JOIN submission s ON s.id=i.submission_id JOIN review_explanation e ON e.review_item_id=i.id AND e.round=i.current_round"+where+" ORDER BY i.id LIMIT ? OFFSET ?").params(params).query().listOfRows();
        return object("items",found.stream().limit(limit).map(row->view(accessible(actor,row.get("id").toString()),false)).toList(),"nextCursor",found.size()>limit?cursor(start+limit,scope):null);
    }

    /** 검토 상세를 권한 범위에서 읽는다. Args: actor 사용자, reviewId 검토. Returns: 상세 DTO. Raises: 404 범위 밖. Side Effects: 읽기. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> get(Actor actor,String reviewId) { return view(accessible(actor,reviewId),true); }

    /** 원본끼리 임의 짝짓기 없이 의미 다중집합과 정확한 전후 합계를 반환한다.
     * Args: actor 사용자, reviewId 검토, query reason/basis/페이지. Returns: 근거 페이지. Raises: 비교 선택·권한 오류. Side Effects: 읽기.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> evidence(Actor actor,String reviewId,Map<String,String> query) {
        checkQuery(query,Set.of("reason","basis","limit","cursor")); var row=accessible(actor,reviewId);String reason=query.get("reason"),basis=query.get("basis");
        Long baseline=null;String scenario=null;
        if("CHANGE".equals(reason)&&basis==null&&Boolean.TRUE.equals(row.get("change_detected"))) { baseline=nullable(row.get("previous_submission_id"));scenario=row.get("scenario").toString(); }
        else if("GAP".equals(reason)&&Set.of("PLAN","PREVIOUS").contains(Objects.toString(basis,""))&&Boolean.TRUE.equals(row.get("gap_exceeded"))) {
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
            if(!"PENDING".equals(row.get("submission_status"))||explanation.get("answer")==null||!"PENDING".equals(explanation.get("verdict")))throw conflict("EXPLANATION_LOCKED","제출된 현재 답변만 검토할 수 있습니다.");
            var now=OffsetDateTime.now(ZoneOffset.UTC);boolean accept="ACCEPT".equals(input.action());
            jdbc.sql("UPDATE review_explanation SET verdict=?,reviewed_by=?,reviewed_at=?,review_note=? WHERE id=?").params(accept?"ACCEPTED":"REJECTED",id(actor.id()),now,input.note(),explanation.get("id")).update();
            if(!accept) { int next=input.round()+1;jdbc.sql("INSERT INTO review_explanation(review_item_id,round,request_note,requested_by,requested_at,verdict) VALUES (?,?,?,?,?,'PENDING')").params(id(reviewId),next,input.note(),id(actor.id()),now).update();jdbc.sql("UPDATE review_item SET current_round=? WHERE id=?").params(next,id(reviewId)).update(); }
            return result(view(accessible(actor,reviewId),true));
        });
    }

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
        var result=object("id",row.get("id").toString(),"submissionId",row.get("submission_id").toString(),"organizationId",row.get("organization_id").toString(),"reportItem",object("id",row.get("report_item_id").toString(),"code",row.get("code"),"name",row.get("name"),"kind",row.get("kind")),"year",row.get("year"),"month",row.get("month"),"scenario",row.get("scenario"),"amountPresent",row.get("amount_present"),"currentAmount",row.get("current_amount")==null?null:row.get("current_amount").toString(),"changeDetected",row.get("change_detected"),"gapExceeded",row.get("gap_exceeded"),"required",Boolean.TRUE.equals(row.get("change_detected"))||Boolean.TRUE.equals(row.get("gap_exceeded")),"explanationState",explanation.get("answer")==null?"UNANSWERED":"ACCEPTED".equals(explanation.get("verdict"))?"ACCEPTED":"DRAFT".equals(row.get("submission_status"))?"ANSWERED":"IN_REVIEW","currentRound",row.get("current_round"),"stateToken",token(row),"explanations",history.stream().map(this::explanation).toList());
        if(detail) result.put("comparisons",rows("SELECT c.*,t.scenario,t.percent,t.amount FROM gap_comparison c JOIN gap_threshold t ON t.id=c.threshold_id WHERE c.review_item_id=? ORDER BY c.id",row.get("id")).stream().map(c->object("basis",c.get("basis"),"baselineSubmissionId",c.get("baseline_submission_id").toString(),"baselineScenario",c.get("baseline_scenario"),"baselineAmount",c.get("baseline_amount").toString(),"currentAmount",c.get("current_amount").toString(),"gapAmount",c.get("gap_amount").toString(),"gapRate",c.get("gap_rate")==null?null:c.get("gap_rate").toString(),"threshold",object("id",c.get("threshold_id").toString(),"scenario",c.get("scenario"),"percent",c.get("percent").toString(),"amount",c.get("amount").toString()),"exceeded",c.get("exceeded"))).toList());return result;
    }

    /** 회차와 이어받은 출처를 표시 DTO로 읽는다. Args: e 회차. Returns: 소명 DTO. Raises: DB 오류. Side Effects: 읽기. */
    private Map<String,Object> explanation(Map<String,Object> e) {
        Map<String,Object> inherited=null;
        if(e.get("inherited_from_id")!=null) { var old=rows("SELECT e.id,i.id AS review_id,i.submission_id,s.seq,r.type,r.month,p.year FROM review_explanation e JOIN review_item i ON i.id=e.review_item_id JOIN submission s ON s.id=i.submission_id JOIN submission_request r ON r.id=s.request_id JOIN annual_plan p ON p.id=r.annual_plan_id WHERE e.id=?",e.get("inherited_from_id")).getFirst();inherited=object("explanationId",old.get("id").toString(),"reviewItemId",old.get("review_id").toString(),"submissionId",old.get("submission_id").toString(),"versionLabel",old.get("year")+"-"+("ANNUAL_PLAN".equals(old.get("type"))?"PLAN":String.format("%02d",number(old.get("month"))))+"-"+String.format("%02d",number(old.get("seq")))); }
        return object("id",e.get("id").toString(),"round",e.get("round"),"requestNote",e.get("request_note"),"requestedBy",actor(e.get("requested_by")),"requestedAt",instant(e.get("requested_at")),"answer",e.get("answer"),"answeredBy",actor(e.get("answered_by")),"answeredAt",instant(e.get("answered_at")),"verdict",e.get("verdict"),"reviewedBy",actor(e.get("reviewed_by")),"reviewedAt",instant(e.get("reviewed_at")),"reviewNote",e.get("review_note"),"inheritedFromId",e.get("inherited_from_id")==null?null:e.get("inherited_from_id").toString(),"inheritedFrom",inherited);
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
}
