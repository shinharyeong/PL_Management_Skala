package io.skala.backend.review;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.Actor;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 제출 당시 기준을 보존하면서 새 Gap 기준과 읽기 전용 영향을 제공한다. */
@Service
public class GapThresholdService {
    private final JdbcClient jdbc;private final CommandExecutor commands;private final ReviewComparisonService comparisons;private final ReviewService reviews;private final ObjectMapper mapper;
    /** 의존성을 받는다. Args: jdbc DB, commands 실행기, comparisons 비교, reviews 행위자표시, mapper JSON. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public GapThresholdService(JdbcClient jdbc,CommandExecutor commands,ReviewComparisonService comparisons,ReviewService reviews,ObjectMapper mapper){this.jdbc=jdbc;this.commands=commands;this.comparisons=comparisons;this.reviews=reviews;this.mapper=mapper;}
    /** 현재 시나리오별 기준을 읽는다. Args: actor 경영기획. Returns: nullable 기준과 토큰. Raises: 권한 오류. Side Effects: 읽기. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> current(Actor actor){var actual=latest("ACTUAL");var estimate=latest("ESTIMATE");return object("actual",actual==null?null:view(actual),"estimate",estimate==null?null:view(estimate),"stateToken",fingerprint(object("actual",actual==null?null:actual.get("id"),"estimate",estimate==null?null:estimate.get("id"))));}
    /** 입력한 기준을 새 이력으로 적용한다. Args: actor 경영기획, key 멱등키, input 기준·기대토큰. Returns: 생성 기준. Raises: 입력·토큰 충돌. Side Effects: 기준 및 멱등 결과 DB 저장. */
    public CommandExecutor.Result apply(Actor actor,String key,ApplyInput input){
        actor.requireRole("PLANNER");validate(input.scenario(),input.percent(),input.amount());
        return commands.execute(actor,"POST /api/v1/gap-thresholds",key,fingerprint(input),()->{
            jdbc.sql("SELECT id FROM gap_threshold_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
            if(!Objects.equals(input.expectedStateToken(),current(actor).get("stateToken")))throw new ApiException(HttpStatus.CONFLICT,"RESOURCE_CHANGED","Gap 기준이 변경되었습니다. 다시 조회해 주세요.");
            var keyHolder=new GeneratedKeyHolder();jdbc.sql("INSERT INTO gap_threshold(scenario,percent,amount,applied_by,applied_at) VALUES (?,?,?,?,?)").params(input.scenario(),decimal(input.percent()),decimal(input.amount()),id(actor.id()),OffsetDateTime.now(ZoneOffset.UTC)).update(keyHolder,"id");
            var threshold=rows("SELECT * FROM gap_threshold WHERE id=?",Objects.requireNonNull(keyHolder.getKey()).longValue()).getFirst();return new CommandExecutor.Result(201,comparisons.json(view(threshold)),null);
        });
    }
    /** 최신 제출 완료본의 전체 OP를 고정 비교 문맥으로 다시 평가한다.
     * Args: actor 경영기획, input 요청·후보 기준. Returns: 평가·초과·제외 수와 원본 참조. Raises: 입력·권한 오류. Side Effects: DB 읽기만 수행하며 기준·소명을 바꾸지 않는다.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(Actor actor,PreviewInput input){
        actor.requireRole("PLANNER");validate(input.scenario(),input.percent(),input.amount());long request=id(input.requestId());
        var requests=rows("SELECT * FROM submission_request WHERE id=? AND type='MONTHLY_ACTUAL'",request);if(requests.isEmpty())throw invalid("월간 요청을 선택해 주세요.");
        var sources=rows("SELECT s.id,s.organization_id FROM submission s JOIN request_target t ON t.request_id=s.request_id AND t.organization_id=s.organization_id WHERE s.request_id=? AND t.excluded=FALSE AND s.status<>'DRAFT' AND s.seq=(SELECT MAX(x.seq) FROM submission x WHERE x.request_id=s.request_id AND x.organization_id=s.organization_id AND x.status<>'DRAFT') ORDER BY s.organization_id",request);
        int evaluated=0,exceeded=0,excluded=0;var organizations=new HashSet<Object>();var exclusions=new TreeMap<String,Integer>();var sourceIds=new ArrayList<String>();var candidate=object("percent",decimal(input.percent()),"amount",decimal(input.amount()));
        var cellCache=new HashMap<Long,Map<String,ReviewComparisonService.Cell>>();var completeCache=new HashMap<Long,Boolean>();
        int closing=((Number)requests.getFirst().get("month")).intValue();int expected="ACTUAL".equals(input.scenario())?closing:12-closing;
        for(var source:sources){long submission=((Number)source.get("id")).longValue();sourceIds.add(Long.toString(submission));var row=comparisons.submission(submission);Map<String,Object> context=fixedContext(submission);
            if(context==null||!completeCache.computeIfAbsent(submission,comparisons::complete)||!"monthly-actual/1.0".equals(context.get("reviewPolicyVersion"))){excluded+=expected;exclusions.merge("VALIDATION_UNAVAILABLE",expected,Integer::sum);continue;}
            var cells=cellCache.computeIfAbsent(submission,comparisons::cells).values().stream().filter(cell->cell.code().equals("OP")&&cell.scenario().equals(input.scenario())).toList();
            if(cells.size()!=expected){excluded+=expected;exclusions.merge("SOURCE_INCOMPLETE",expected,Integer::sum);continue;}
            for(var cell:cells){var diagnostics=new ArrayList<Map<String,Object>>();var result=comparisons.comparisons(row,cell,context,candidate,diagnostics,cellCache,completeCache);if(!diagnostics.isEmpty()||result.isEmpty()){excluded++;exclusions.merge(diagnostics.isEmpty()?"BASELINE_MISSING":diagnostics.getFirst().get("code").toString(),1,Integer::sum);continue;}evaluated++;if(result.stream().anyMatch(value->Boolean.TRUE.equals(value.get("exceeded")))){exceeded++;organizations.add(source.get("organization_id"));}}
        }
        return object("requestId",input.requestId(),"scenario",input.scenario(),"evaluatedItemCount",evaluated,"exceededItemCount",exceeded,"exceededOrganizationCount",organizations.size(),"excludedItemCount",excluded,"exclusions",exclusions.entrySet().stream().filter(e->e.getValue()>0).map(e->object("code",e.getKey(),"count",e.getValue())).toList(),"sourceSubmissionIds",sourceIds,"evaluatedAt",OffsetDateTime.now(ZoneOffset.UTC).toInstant().toString());
    }
    /** 제출 당시 비교 문맥을 읽는다. Args: submission 제출. Returns: 고정 문맥/null. Raises: DB 오류. Side Effects: 읽기. */
    private Map<String,Object> fixedContext(long submission){var found=rows("SELECT o.payload FROM submission_validation v JOIN stored_object o ON o.object_key=v.validation_result_object_key WHERE v.submission_id=?",submission);try{return found.isEmpty()?null:mapper.convertValue(mapper.readTree((byte[])found.getFirst().get("payload")).get("comparisonContext"),Map.class);}catch(Exception ex){return null;}}
    /** 최신 단일 기준을 읽는다. Args: scenario 구분. Returns: 행/null. Raises: DB 오류. Side Effects: 읽기. */
    private Map<String,Object> latest(String scenario){var found=rows("SELECT * FROM gap_threshold WHERE scenario=? ORDER BY id DESC LIMIT 1",scenario);return found.isEmpty()?null:found.getFirst();}
    /** 기준 표시 DTO를 만든다. Args: row 기준. Returns: DTO. Raises: DB 오류. Side Effects: 읽기. */
    private Map<String,Object> view(Map<String,Object> row){return object("id",row.get("id").toString(),"scenario",row.get("scenario"),"percent",row.get("percent").toString(),"amount",row.get("amount").toString(),"appliedBy",reviews.actor(row.get("applied_by")),"appliedAt",instant(row.get("applied_at")));}
    /** 기준 입력의 종류·정밀도를 검증한다. Args: scenario 종류, percent 비율, amount 금액. Returns: 없음. Raises: 입력 오류. Side Effects: 없음. */
    private void validate(String scenario,String percent,String amount){if(!Set.of("ACTUAL","ESTIMATE").contains(Objects.toString(scenario,"")))throw invalid("ACTUAL 또는 ESTIMATE 기준을 선택해 주세요.");decimal(percent);decimal(amount);}
    /** 반올림 없는 음이 아닌 NUMERIC(30,6) 값을 검증한다. Args: input 문자열. Returns: 십진값. Raises: 입력 오류. Side Effects: 없음. */
    private BigDecimal decimal(String input){try{if(input==null||!input.matches("[0-9]+(?:\\.[0-9]+)?"))throw new IllegalArgumentException();var value=new BigDecimal(input);if(value.scale()>6||value.precision()-value.scale()>24)throw new IllegalArgumentException();return value;}catch(IllegalArgumentException ex){throw invalid("기준은 정수 24자리·소수 6자리 이내의 0 이상 십진 문자열입니다.");}}
    /** 고정 SQL을 읽는다. Args: sql 쿼리, params 값. Returns: 행. Raises: DB 오류. Side Effects: 읽기. */
    private List<Map<String,Object>> rows(String sql,Object...params){return jdbc.sql(sql).params(params).query().listOfRows();}
    /** 멱등 입력 해시를 만든다. Args: value 입력. Returns: SHA256. Raises: JSON 오류. Side Effects: 없음. */
    private String fingerprint(Object value){return hash(comparisons.json(value).getBytes(StandardCharsets.UTF_8));}
    /** 입력 오류를 만든다. Args: message 안내. Returns: 400. Raises: 없음. Side Effects: 없음. */
    private ApiException invalid(String message){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message);}
    /** 새 적용 입력이며 기준 시각·행위자는 서버가 기록한다. */
    public record ApplyInput(@NotBlank String expectedStateToken,@NotBlank String scenario,@NotBlank String percent,@NotBlank String amount){}
    /** 읽기 전용 후보 기준 입력이다. */
    public record PreviewInput(@NotBlank String requestId,@NotBlank String scenario,@NotBlank String percent,@NotBlank String amount){}
}
