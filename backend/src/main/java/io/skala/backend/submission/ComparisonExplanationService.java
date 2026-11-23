package io.skala.backend.submission;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.review.ReviewComparisonService;
import io.skala.backend.review.ReviewService;
import io.skala.backend.security.Actor;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

/** 자동 Gap 스냅샷을 보존한 채 기획자가 선택한 비교 문맥의 새 소명을 요청한다. */
@Service
public class ComparisonExplanationService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final CommandExecutor commands;
    private final ReviewService reviews;
    private final ReviewComparisonService comparisons;
    private final SubmissionService submissions;

    /** 명시 비교 요청의 의존성을 연결한다.
     * Args: jdbc DB, mapper JSON, commands 명령, reviews 검토, comparisons 비교, submissions 제출 조회.
     * Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public ComparisonExplanationService(JdbcClient jdbc,ObjectMapper mapper,CommandExecutor commands,ReviewService reviews,ReviewComparisonService comparisons,SubmissionService submissions){this.jdbc=jdbc;this.mapper=mapper;this.commands=commands;this.reviews=reviews;this.comparisons=comparisons;this.submissions=submissions;}

    /** 최종 제출본 사이의 검증된 비교에 새 필수 소명 회차를 연다.
     * Args: actor 기획자, submissionId 현재 PENDING, key 멱등키, input 토큰·비교·항목·메시지·기한.
     * Returns: 현재 검토 상세 목록과 변경된 제출 상세.
     * Raises: 권한·입력·토큰·최종 제출/검증 미충족 오류. 하나라도 실패하면 전 항목 롤백.
     * Side Effects: 조직·제출 잠금, 검토·소명회차·요청 이벤트·멱등 DB 저장. 금융값·고정 Gap·마스터는 변경하지 않는다.
     */
    public CommandExecutor.Result request(Actor actor,String submissionId,String key,JsonNode input){
        actor.requireRole("PLANNER");fields(input,Set.of("expectedStateToken","baselineSubmissionId","baselineScenario","scenario","month","reportItemIds","note","dueAt"));
        String token=text(input,"expectedStateToken",128),baseline=text(input,"baselineSubmissionId",19),scenario=text(input,"scenario",20),baselineScenario=text(input,"baselineScenario",20),note=text(input,"note",4000);
        id(baseline);long currentId=id(submissionId);int month=integer(input,"month",1,12);OffsetDateTime due;
        try{due=OffsetDateTime.parse(text(input,"dueAt",80));}catch(java.time.format.DateTimeParseException exception){throw invalid("시간대가 있는 회신 기한을 지정해 주세요.");}
        if(!Set.of("PLAN","ACTUAL","ESTIMATE").contains(scenario)||!Set.of("PLAN","ACTUAL","ESTIMATE").contains(baselineScenario))throw invalid("비교 시나리오를 확인해 주세요.");
        var selected=input.path("reportItemIds");if(!selected.isArray()||selected.isEmpty()||selected.size()>100)throw invalid("1~100개 보고 항목을 선택해 주세요.");
        var ids=new LinkedHashSet<String>();for(var value:selected){if(!value.isTextual()||!ids.add(Long.toString(id(value.asText()))))throw invalid("보고 항목 ID는 중복 없는 문자열입니다.");}
        var visible=submissions.submission(actor,submissionId);
        return commands.execute(actor,"POST /api/v1/submissions/"+currentId+"/comparison/explanation-requests",key,hash(json(input).getBytes(StandardCharsets.UTF_8)),()-> {
            jdbc.sql("SELECT id FROM organization WHERE id=? FOR UPDATE").param(id(visible.get("organizationId").toString())).query(Long.class).single();
            var row=jdbc.sql("SELECT * FROM submission WHERE id=? FOR UPDATE").param(currentId).query().singleRow();
            if(!"PENDING".equals(row.get("status")))throw conflict("REVIEW_LOCKED","검토 대기 제출본에만 소명을 요청할 수 있습니다.");
            if(rows("SELECT request_id FROM request_target WHERE request_id=? AND organization_id=? AND excluded=FALSE",row.get("request_id"),row.get("organization_id")).isEmpty())throw conflict("REQUEST_TARGET_EXCLUDED","현재 제출 대상에서 제외된 조직입니다.");
            var currentView=submissions.submission(actor,submissionId);
            if(!token.equals(currentView.get("stateToken")))throw conflict("RESOURCE_CHANGED","제출 상태가 변경되었습니다. 다시 확인해 주세요.");
            var fixedContext=currentView.get("comparisonContext") instanceof Map<?,?> context?context:Map.of();
            var baselineRows=rows("SELECT status FROM submission WHERE id=?",id(baseline));
            if(baselineRows.isEmpty()||"DRAFT".equals(baselineRows.getFirst().get("status")))throw invalid("비교 기준은 최종 제출된 버전이어야 합니다.");
            Map<String,Object> compared=reviews.compareSubmissions(actor,submissionId,Map.of("baselineSubmissionId",baseline,"baselineScenario",baselineScenario,"scenario",scenario,"month",Integer.toString(month)));
            var selectedItems=new LinkedHashMap<String,Map<String,Object>>();
            for(var item:(List<Map<String,Object>>)compared.get("items")){
                String report=((Map<String,Object>)item.get("reportItem")).get("id").toString();
                if(ids.contains(report)){if(!"AVAILABLE".equals(item.get("valueStatus")))throw invalid("기준값과 현재값이 확인된 항목만 소명을 요청할 수 있습니다.");selectedItems.put(report,item);}
            }
            if(selectedItems.size()!=ids.size())throw invalid("선택한 비교에 없는 보고 항목입니다.");
            var cells=comparisons.cells(currentId);var changed=new ArrayList<String>();var now=OffsetDateTime.now(ZoneOffset.UTC);
            for(String report:ids){
                var comparison=new LinkedHashMap<>(selectedItems.get(report));comparison.put("submissionId",submissionId);comparison.put("baselineSubmissionId",baseline);
                int year=((Number)comparison.get("year")).intValue();var cell=cells.get(comparisons.key(id(report),year,month,scenario));
                var existing=rows("SELECT * FROM review_item WHERE submission_id=? AND report_item_id=? AND year=? AND month=? AND scenario=? FOR UPDATE",currentId,id(report),year,month,scenario);
                long reviewId;int round;
                if(existing.isEmpty()){
                    String fingerprint=hash(json(comparison).getBytes(StandardCharsets.UTF_8));
                    reviewId=insert("INSERT INTO review_item(submission_id,report_item_id,year,month,scenario,amount_present,current_amount,change_detected,gap_exceeded,source_fingerprint,context_fingerprint,current_round,review_policy_version,workflow_state,workflow_note,requirement_mode) VALUES (?,?,?,?,?,TRUE,?,FALSE,FALSE,?,?,1,?,'REQUESTED',?,?)",currentId,id(report),year,month,scenario,cell.amount(),cell.fingerprint(),fingerprint,"PLAN".equals(scenario)?"annual-plan/2.0":"monthly-actual/2.0",note,fixedContext.get("requirementMode"));round=1;
                }else{
                    var item=existing.getFirst();reviewId=((Number)item.get("id")).longValue();round=((Number)item.get("current_round")).intValue()+1;
                    if(!Boolean.TRUE.equals(item.get("active")))throw conflict("REVIEW_SOURCE_CHANGED","현재 검증에서 제외된 검토 항목은 새 요청으로 되살릴 수 없습니다.");
                    jdbc.sql("UPDATE review_item SET current_round=?,active=TRUE,workflow_state='REQUESTED',workflow_note=? WHERE id=?").params(round,note,reviewId).update();
                }
                jdbc.sql("INSERT INTO review_explanation(review_item_id,round,request_note,requested_by,requested_at,due_at,verdict,request_comparison_json) VALUES (?,?,?,?,?,?,'PENDING',?)")
                    .params(reviewId,round,note,id(actor.id()),now,due,json(comparison)).update();
                jdbc.sql("INSERT INTO review_workflow_event(review_item_id,action,explanation_round,note,due_at,acted_by,acted_at) VALUES (?,'REQUEST_EXPLANATION',?,?,?,?,?)").params(reviewId,round,note,due,id(actor.id()),now).update();changed.add(Long.toString(reviewId));
            }
            return new CommandExecutor.Result(200,json(object("items",changed.stream().map(reviewId->reviews.get(actor,reviewId)).toList(),"submission",submissions.submission(actor,submissionId))),"");
        });
    }

    /** 허용된 입력 객체만 받는다. Args: input 요청, allowed 필드. Returns: 없음. Raises: 입력 오류. Side Effects: 없음. */
    private void fields(JsonNode input,Set<String> allowed){if(input==null||!input.isObject())throw invalid("JSON 객체가 필요합니다.");var names=input.fieldNames();while(names.hasNext())if(!allowed.contains(names.next()))throw invalid("허용되지 않은 소명 요청 필드입니다.");}
    /** 필수 문자열을 읽는다. Args: input 객체, field 필드, max 길이. Returns: 문자열. Raises: 입력 오류. Side Effects: 없음. */
    private String text(JsonNode input,String field,int max){var value=input.path(field);if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>max)throw invalid(field+" 값을 확인해 주세요.");return value.asText().strip();}
    /** 정수 범위를 확인한다. Args: input 객체, field 필드, min 최소, max 최대. Returns: 정수. Raises: 입력 오류. Side Effects: 없음. */
    private int integer(JsonNode input,String field,int min,int max){var value=input.path(field);if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<min||value.asInt()>max)throw invalid(field+" 범위를 확인해 주세요.");return value.asInt();}
    /** DB 행을 읽는다. Args: sql 고정 SQL, params 값. Returns: 행 목록. Raises: DB 오류. Side Effects: 읽기. */
    private List<Map<String,Object>> rows(String sql,Object...params){return jdbc.sql(sql).params(params).query().listOfRows();}
    /** 행을 생성한다. Args: sql INSERT, params 값. Returns: 생성 ID. Raises: DB 오류. Side Effects: INSERT. */
    private long insert(String sql,Object...params){var holder=new GeneratedKeyHolder();jdbc.sql(sql).params(params).update(holder,"id");return holder.getKey().longValue();}
    /** JSON을 직렬화한다. Args: value 값. Returns: JSON. Raises: 직렬화 오류. Side Effects: 없음. */
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception exception){throw new IllegalStateException("명시 비교 요청을 직렬화하지 못했습니다.");}}
    /** 입력 오류를 만든다. Args: message 안내. Returns: 400 예외. Raises: 없음. Side Effects: 없음. */
    private ApiException invalid(String message){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message);}
    /** 충돌 오류를 만든다. Args: code 코드, message 안내. Returns: 409 예외. Raises: 없음. Side Effects: 없음. */
    private ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
}
