package io.skala.backend.submission;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.security.Actor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 기획자가 지목한 반려 항목의 출처와 비교 금액을 검증하여 불변 이력으로 보관한다. */
@Service
public class SubmissionRejectionService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    /** 반려 이력 저장 경계를 연결한다. Args: jdbc DB, mapper JSON. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public SubmissionRejectionService(JdbcClient jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

    /** 반려 명령과 같은 트랜잭션에서 확인된 항목만 새 이력으로 저장한다.
     * Args: actor 기획자, submissionId 잠긴 PENDING 제출, inputs 선택 항목 배열.
     * Returns: 없음. Raises: 다른 제출의 검토·원천, 잘못된 기간·필드·중복 입력 오류.
     * Side Effects: 반려 스냅샷 INSERT. 기존 제출 원본·금융값·과거 반려 이력은 변경하지 않는다.
     */
    public void save(Actor actor,long submissionId,List<JsonNode> inputs) {
        actor.requireRole("PLANNER");int ordinal=0;var seen=new HashSet<String>();
        for(JsonNode input:inputs){
            var snapshot=snapshot(submissionId,input);
            String identity=snapshot.get("reportItem")+":"+snapshot.get("year")+":"+snapshot.get("month")+":"+snapshot.get("scenario")+":"+snapshot.get("source")+":"+snapshot.get("basis");
            if(!seen.add(identity))throw invalid("같은 반려 항목을 중복 선택할 수 없습니다.");
            jdbc.sql("INSERT INTO submission_rejection_item(submission_id,review_item_id,ordinal,snapshot_json,rejected_by,rejected_at) VALUES (?,?,?,?,?,?)")
                .params(submissionId,snapshot.get("reviewItemId")==null?null:id(snapshot.get("reviewItemId").toString()),++ordinal,json(snapshot),id(actor.id()),OffsetDateTime.now(ZoneOffset.UTC)).update();
        }
    }

    /** 상위 제출 권한 확인 뒤 보존된 반려 항목을 읽는다.
     * Args: submissionId 권한이 확인된 제출. Returns: 불변 항목 목록. Raises: DB·저장 JSON 손상.
     * Side Effects: DB 읽기. 현재 보고값으로 과거 금액을 재계산하지 않는다.
     */
    public List<Map<String,Object>> items(long submissionId){
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows("SELECT id,snapshot_json FROM submission_rejection_item WHERE submission_id=? ORDER BY ordinal",submissionId)){
            try{Map<String,Object> item=mapper.readValue(row.get("snapshot_json").toString(),new TypeReference<LinkedHashMap<String,Object>>(){});item.put("id",row.get("id").toString());result.add(item);}
            catch(Exception exception){throw new IllegalStateException("반려 항목 이력을 읽을 수 없습니다.");}
        }
        return result;
    }

    /** 외부 항목 선택을 현재 제출의 검증값과 대조한다.
     * Args: submissionId 제출, input 항목 선택. Returns: 서버가 확정한 반려 스냅샷.
     * Raises: 잘못된 선택·근거 없는 셀 오류. Side Effects: 검토·검증·금융 DB 읽기.
     */
    private Map<String,Object> snapshot(long submissionId,JsonNode input){
        fields(input,Set.of("reviewItemId","reportItemId","year","month","scenario","basis","source","reason"));
        String reason=text(input,"reason",2000),reviewId=null,reportId,scenario;int year,month;Map<String,Object> review=null;
        if(input.hasNonNull("reviewItemId")){
            reviewId=stringId(input,"reviewItemId");var found=rows("SELECT * FROM review_item WHERE id=? AND submission_id=? AND active=TRUE",id(reviewId),submissionId);
            if(found.isEmpty())throw invalid("해당 제출의 검토 항목을 선택해 주세요.");review=found.getFirst();
            reportId=review.get("report_item_id").toString();year=((Number)review.get("year")).intValue();month=((Number)review.get("month")).intValue();scenario=review.get("scenario").toString();
            if((input.has("reportItemId")&&!reportId.equals(stringId(input,"reportItemId")))||(input.has("year")&&integer(input,"year",2000,9999)!=year)||(input.has("month")&&integer(input,"month",1,12)!=month)||(input.has("scenario")&&!scenario.equals(text(input,"scenario",20))))throw invalid("검토 항목과 기간·보고 항목이 일치하지 않습니다.");
        }else{reportId=stringId(input,"reportItemId");year=integer(input,"year",2000,9999);month=integer(input,"month",1,12);scenario=text(input,"scenario",20);}
        if(!Set.of("PLAN","ACTUAL","ESTIMATE").contains(scenario))throw invalid("시나리오를 확인해 주세요.");
        var reports=rows("SELECT id,code,name,kind FROM report_item WHERE id=?",id(reportId));if(reports.isEmpty())throw invalid("보고 항목을 확인해 주세요.");var report=reports.getFirst();
        JsonNode current=validation(submissionId);String currentAmount=amount(current,reportId,year,month,scenario);
        if(review==null&&!hasValue(current,reportId,year,month,scenario))throw invalid("해당 제출에 없는 항목·기간은 반려 대상으로 선택할 수 없습니다.");
        String basis=input.hasNonNull("basis")?text(input,"basis",20):null;
        if(basis!=null&&!Set.of("PLAN","PREVIOUS").contains(basis))throw invalid("비교 기준은 PLAN/PREVIOUS입니다.");
        String baselineId=null,baselineScenario=scenario,baselineAmount=null;
        if(reviewId!=null){
            var comparisons=basis==null?rows("SELECT * FROM gap_comparison WHERE review_item_id=? ORDER BY basis",id(reviewId)):rows("SELECT * FROM gap_comparison WHERE review_item_id=? AND basis=?",id(reviewId),basis);
            if(!comparisons.isEmpty()){var comparison=comparisons.getFirst();basis=comparison.get("basis").toString();baselineId=comparison.get("baseline_submission_id").toString();baselineScenario=comparison.get("baseline_scenario").toString();baselineAmount=decimal(comparison.get("baseline_amount"));currentAmount=decimal(comparison.get("current_amount"));}
            else if(basis!=null)throw invalid("해당 항목에 저장된 비교 기준이 없습니다.");
        }
        if(baselineId==null){
            basis=basis==null?"PREVIOUS":basis;String field="PLAN".equals(basis)?"planSubmissionId":"previousSubmissionId";
            var value=current.path("comparisonContext").path(field);if(value.isTextual()){baselineId=value.asText();baselineScenario="PLAN".equals(basis)?"PLAN":scenario;baselineAmount=amount(validation(id(baselineId)),reportId,year,month,baselineScenario);}
        }
        Map<String,Object> source=null;String sourceAmount=null;
        if(input.hasNonNull("source")){
            var selection=input.path("source");fields(selection,Set.of("sheet","cell"));String sheet=text(selection,"sheet",255),cell=text(selection,"cell",30);
            if(!cell.matches("[A-Z]{1,3}[1-9][0-9]{0,6}"))throw invalid("원천 셀은 대문자 A1 주소여야 합니다.");
            var values=rows("SELECT v.amount FROM financial_line l JOIN financial_value v ON v.line_id=l.id WHERE l.submission_id=? AND l.report_item_id=? AND v.year=? AND v.month=? AND v.scenario=? AND l.source_sheet=? AND v.source_cell=?",submissionId,id(reportId),year,month,scenario,sheet,cell);
            if(values.size()!=1)throw invalid("해당 항목·기간에서 확인된 원천 셀을 선택해 주세요.");source=object("sheet",sheet,"cell",cell);sourceAmount=decimal(values.getFirst().get("amount"));
        }
        return object("reviewItemId",reviewId,"reportItem",object("id",reportId,"code",report.get("code"),"name",report.get("name"),"kind",report.get("kind")),"year",year,"month",month,"scenario",scenario,"basis",baselineId==null?null:basis,"baselineSubmissionId",baselineId,"baselineScenario",baselineId==null?null:baselineScenario,"baselineAmount",baselineAmount,"currentAmount",currentAmount,"amountScope","REPORT_ITEM","source",source,"sourceAmount",sourceAmount,"reason",reason);
    }

    /** 제출의 고정 검증 객체를 읽는다. Args: submissionId 제출. Returns: 검증 JSON 또는 빈 객체. Raises: 저장손상. Side Effects: DB 읽기. */
    private JsonNode validation(long submissionId){var found=jdbc.sql("SELECT o.payload FROM submission_validation v JOIN stored_object o ON o.object_key=v.validation_result_object_key WHERE v.submission_id=?").param(submissionId).query(byte[].class).list();if(found.isEmpty())return mapper.createObjectNode();try{return mapper.readTree(found.getFirst());}catch(Exception exception){throw new IllegalStateException("고정 검증 근거를 읽을 수 없습니다.");}}
    /** 선택 보고값의 존재를 검사한다. Args: snapshot 검증, report 보고항목, year 연도, month 월, scenario 시나리오. Returns: 존재여부. Raises: 없음. Side Effects: 없음. */
    private boolean hasValue(JsonNode snapshot,String report,int year,int month,String scenario){return value(snapshot,report,year,month,scenario)!=null;}
    /** 선택 보고값 금액을 읽는다. Args: snapshot 검증, report 보고항목, year 연도, month 월, scenario 시나리오. Returns: 금액/null. Raises: 없음. Side Effects: 없음. */
    private String amount(JsonNode snapshot,String report,int year,int month,String scenario){var item=value(snapshot,report,year,month,scenario);return item==null||!item.path("amount").isTextual()?null:item.path("amount").asText();}
    /** 보고값 업무키를 검색한다. Args: snapshot 검증, report 보고항목, year 연도, month 월, scenario 시나리오. Returns: 값 JSON/null. Raises: 없음. Side Effects: 없음. */
    private JsonNode value(JsonNode snapshot,String report,int year,int month,String scenario){for(var item:snapshot.path("reportValues"))if(report.equals(item.path("reportItemId").asText())&&year==item.path("year").asInt()&&month==item.path("month").asInt()&&scenario.equals(item.path("scenario").asText()))return item;return null;}
    /** 허용 필드를 검사한다. Args: input 객체, allowed 필드집합. Returns: 없음. Raises: 입력 오류. Side Effects: 없음. */
    private void fields(JsonNode input,Set<String> allowed){if(input==null||!input.isObject())throw invalid("반려 항목은 객체여야 합니다.");var names=input.fieldNames();while(names.hasNext())if(!allowed.contains(names.next()))throw invalid("허용되지 않은 반려 항목 필드입니다.");}
    /** 문자열을 검사한다. Args: input 객체, field 이름, max 길이. Returns: 문자열. Raises: 입력 오류. Side Effects: 없음. */
    private String text(JsonNode input,String field,int max){var value=input.path(field);if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>max)throw invalid(field+" 값을 확인해 주세요.");return value.asText().strip();}
    /** 문자열 ID를 확인한다. Args: input 객체, field 이름. Returns: ID. Raises: 입력 오류. Side Effects: 없음. */
    private String stringId(JsonNode input,String field){String value=text(input,field,19);id(value);return value;}
    /** 정수 범위를 확인한다. Args: input 객체, field 이름, min 최소, max 최대. Returns: 정수. Raises: 입력 오류. Side Effects: 없음. */
    private int integer(JsonNode input,String field,int min,int max){var value=input.path(field);if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<min||value.asInt()>max)throw invalid(field+" 범위를 확인해 주세요.");return value.asInt();}
    /** 정확 금액을 문자열로 만든다. Args: value DB 금액. Returns: 십진 문자열/null. Raises: 타입 오류. Side Effects: 없음. */
    private String decimal(Object value){return value==null?null:((BigDecimal)value).stripTrailingZeros().toPlainString();}
    /** DB 목록을 읽는다. Args: sql 고정SQL, params 값. Returns: 행목록. Raises: DB 오류. Side Effects: 읽기. */
    private List<Map<String,Object>> rows(String sql,Object...params){return jdbc.sql(sql).params(params).query().listOfRows();}
    /** 스냅샷을 직렬화한다. Args: value DTO. Returns: JSON. Raises: 저장손상. Side Effects: 없음. */
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception exception){throw new IllegalStateException("반려 근거를 직렬화하지 못했습니다.");}}
    /** 공개 입력 오류를 만든다. Args: message 안내. Returns: 400 예외. Raises: 없음. Side Effects: 없음. */
    private ApiException invalid(String message){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message);}
}
