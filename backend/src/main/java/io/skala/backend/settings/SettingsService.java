package io.skala.backend.settings;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.Actor;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

/** 공식 제출 이력을 유지하며 기획자의 조직·계정·요청 문구와 본인 소속 확인을 관리한다. */
@Service
public class SettingsService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final CommandExecutor commands;

    /** 설정 DB와 원자적 명령 경계를 연결한다.
     * Args: jdbc DB, mapper JSON, commands 멱등 실행기. Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public SettingsService(JdbcClient jdbc, ObjectMapper mapper, CommandExecutor commands) {
        this.jdbc = jdbc; this.mapper = mapper; this.commands = commands;
    }

    /** 등록 조직을 설정용 상태 토큰과 함께 읽는다.
     * Args: actor 기획자. Returns: 조직 목록. Raises: 권한·DB 오류. Side Effects: DB 읽기.
     */
    public Map<String,Object> organizations(Actor actor) {
        actor.requireRole("PLANNER");
        return object("items", rows("SELECT * FROM organization ORDER BY code,id").stream().map(this::organizationView).toList(), "nextCursor", null);
    }

    /** 요청 대상 스냅샷을 변경하지 않고 새 조직을 등록한다.
     * Args: actor 기획자, key 멱등키, input 코드·이름·활성 여부. Returns: 생성된 조직.
     * Raises: 권한·입력·코드중복 오류. Side Effects: 조직·감사·멱등 DB 저장.
     */
    public CommandExecutor.Result createOrganization(Actor actor, String key, JsonNode input) {
        actor.requireRole("PLANNER"); fields(input, Set.of("code","name","active"));
        String code = text(input,"code",80), name = text(input,"name",200);
        if (!code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) throw invalid("조직 코드는 영문·숫자·밑줄·하이픈을 사용합니다.");
        boolean active = bool(input,"active",true);
        return commands.execute(actor,"POST /api/v1/settings/organizations",key,fingerprint(input),()-> {
            guard();
            if (!rows("SELECT id FROM organization WHERE code=?",code).isEmpty()) throw conflict("ORGANIZATION_CODE_EXISTS","이미 등록한 조직 코드입니다.");
            long organizationId = insert("INSERT INTO organization(code,name,active) VALUES (?,?,?)",code,name,active);
            var result = organizationView(one("SELECT * FROM organization WHERE id=?",organizationId));
            audit(actor,"ORGANIZATION",organizationId,"CREATE",result);
            return result(201,result,"/api/v1/settings/organizations/"+organizationId);
        });
    }

    /** 조직 표시 이름과 향후 요청 활성 여부만 변경한다.
     * Args: actor 기획자, organizationId 조직, key 멱등키, input 토큰·이름·활성 여부.
     * Returns: 변경 조직. Raises: 오래된 토큰·열린 요청 사용중·입력 오류.
     * Side Effects: 조직과 감사 DB 갱신. 기존 요청 대상·제출·마스터는 변경하지 않는다.
     */
    public CommandExecutor.Result updateOrganization(Actor actor,String organizationId,String key,JsonNode input) {
        actor.requireRole("PLANNER"); fields(input,Set.of("expectedStateToken","name","active"));
        long target=id(organizationId); requireChange(input);
        return commands.execute(actor,"PATCH /api/v1/settings/organizations/"+target,key,fingerprint(input),()-> {
            guard(); var row=required("SELECT * FROM organization WHERE id=? FOR UPDATE",target);
            expect(input,organizationView(row));
            String name=input.has("name")?text(input,"name",200):row.get("name").toString();
            boolean active=bool(input,"active",Boolean.TRUE.equals(row.get("active")));
            if (!active && Boolean.TRUE.equals(row.get("active")) && !rows("SELECT t.request_id FROM request_target t JOIN submission_request r ON r.id=t.request_id WHERE t.organization_id=? AND t.excluded=FALSE AND r.status='OPEN'",target).isEmpty())
                throw conflict("ORGANIZATION_IN_USE","열린 요청의 필수 대상 조직은 비활성화할 수 없습니다. 요청을 먼저 마감해 주세요.");
            jdbc.sql("UPDATE organization SET name=?,active=?,settings_revision=settings_revision+1 WHERE id=?").params(name,active,target).update();
            var result=organizationView(one("SELECT * FROM organization WHERE id=?",target)); audit(actor,"ORGANIZATION",target,"UPDATE",result);
            return result(200,result,"");
        });
    }

    /** 기획자가 관리할 사용자 목록을 조직·역할·활성 여부로 읽는다.
     * Args: actor 기획자, query 선택 organizationId/role/active. Returns: 계정 목록.
     * Raises: 권한·필터 오류. Side Effects: DB 읽기.
     */
    public Map<String,Object> users(Actor actor,Map<String,String> query) {
        actor.requireRole("PLANNER"); if(!Set.of("organizationId","role","active").containsAll(query.keySet())) throw invalid("지원하지 않는 사용자 필터입니다.");
        String sql="SELECT * FROM app_user WHERE 1=1"; var params=new ArrayList<Object>();
        if(query.containsKey("organizationId")){sql+=" AND organization_id=?";params.add(id(query.get("organizationId")));}
        if(query.containsKey("role")){role(query.get("role"));sql+=" AND role=?";params.add(query.get("role"));}
        if(query.containsKey("active")){if(!Set.of("true","false").contains(query.get("active")))throw invalid("active는 true/false입니다.");sql+=" AND active=?";params.add(Boolean.parseBoolean(query.get("active")));}
        return object("items",rows(sql+" ORDER BY name,id",params.toArray()).stream().map(this::userView).toList(),"nextCursor",null);
    }

    /** 기존 이력을 소유할 새 계정을 등록하고 최초 소속 확인을 대기시킨다.
     * Args: actor 기획자, key 멱등키, input 이메일·이름·역할·조직·활성 여부.
     * Returns: 생성 계정. Raises: 잘못된 소속·중복 이메일·권한 오류. Side Effects: 사용자·감사·멱등 DB 저장.
     */
    public CommandExecutor.Result createUser(Actor actor,String key,JsonNode input) {
        actor.requireRole("PLANNER");fields(input,Set.of("email","name","role","organizationId","active"));
        String email=text(input,"email",320).toLowerCase(Locale.ROOT),name=text(input,"name",200),role=text(input,"role",30);
        if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw invalid("이메일 형식을 확인해 주세요.");
        boolean active=bool(input,"active",true);
        return commands.execute(actor,"POST /api/v1/settings/users",key,fingerprint(input),()-> {
            guard(); Long organization=organizationFor(role,input.get("organizationId"));
            if(!rows("SELECT id FROM app_user WHERE LOWER(email)=?",email).isEmpty())throw conflict("EMAIL_ALREADY_EXISTS","이미 등록한 이메일입니다.");
            long user=insert("INSERT INTO app_user(email,name,role,organization_id,active) VALUES (?,?,?,?,?)",email,name,role,organization,active);
            var result=userView(one("SELECT * FROM app_user WHERE id=?",user));audit(actor,"USER",user,"CREATE",result);
            return result(201,result,"/api/v1/settings/users/"+user);
        });
    }

    /** 계정의 역할·소속·활성 여부를 변경하고 변경된 소속 확인을 무효화한다.
     * Args: actor 기획자, userId 대상 사용자, key 멱등키, input 토큰·변경 필드.
     * Returns: 변경 계정. Raises: 자기 권한 제거·소속·토큰·입력 오류.
     * Side Effects: 사용자·감사·멱등 DB 저장. 과거 요청·행위자 FK와 제출 원천은 유지한다.
     */
    public CommandExecutor.Result updateUser(Actor actor,String userId,String key,JsonNode input) {
        actor.requireRole("PLANNER");fields(input,Set.of("expectedStateToken","name","role","organizationId","active"));requireChange(input);long target=id(userId);
        return commands.execute(actor,"PATCH /api/v1/settings/users/"+target,key,fingerprint(input),()-> {
            guard();var row=required("SELECT * FROM app_user WHERE id=? FOR UPDATE",target);expect(input,userView(row));
            String name=input.has("name")?text(input,"name",200):row.get("name").toString();
            String role=input.has("role")?text(input,"role",30):row.get("role").toString();
            JsonNode organizationInput=input.has("organizationId")?input.get("organizationId"):mapper.valueToTree(row.get("organization_id")==null?null:row.get("organization_id").toString());
            Long organization=organizationFor(role,organizationInput);boolean active=bool(input,"active",Boolean.TRUE.equals(row.get("active")));
            if(target==id(actor.id())&&(!active||!"PLANNER".equals(role)))throw conflict("SELF_ACCESS_CHANGE","자신의 기획 권한을 제거하거나 비활성화할 수 없습니다.");
            boolean affiliationChanged=!role.equals(row.get("role"))||!Objects.equals(organization,row.get("organization_id")==null?null:((Number)row.get("organization_id")).longValue());
            jdbc.sql("UPDATE app_user SET name=?,role=?,organization_id=?,active=?,profile_confirmed_at=?,settings_revision=settings_revision+1 WHERE id=?")
                .params(name,role,organization,active,affiliationChanged?null:row.get("profile_confirmed_at"),target).update();
            var result=userView(one("SELECT * FROM app_user WHERE id=?",target));audit(actor,"USER",target,"UPDATE",result);return result(200,result,"");
        });
    }

    /** 전역 요청 문구를 비활성 이력까지 포함하여 읽는다.
     * Args: actor 기획자. Returns: 문구 목록. Raises: 권한·DB 오류. Side Effects: DB 읽기.
     */
    public Map<String,Object> templates(Actor actor) {
        actor.requireRole("PLANNER");return object("items",rows("SELECT * FROM request_message_template ORDER BY active DESC,name,id").stream().map(this::templateView).toList(),"nextCursor",null);
    }

    /** 새 전역 문구를 등록한다. 요청을 실제 발송하거나 기존 요청을 변경하지 않는다.
     * Args: actor 기획자, key 멱등키, input 이름·종류·본문. Returns: 생성 문구.
     * Raises: 입력·권한 오류. Side Effects: 문구·감사·멱등 DB 저장.
     */
    public CommandExecutor.Result createTemplate(Actor actor,String key,JsonNode input) {
        actor.requireRole("PLANNER");fields(input,Set.of("name","type","message"));
        String name=text(input,"name",200),type=templateType(text(input,"type",30)),message=text(input,"message",5000);
        if("EXPLANATION".equals(type)&&message.length()>4000)throw invalid("소명 요청 문구는 4000자 이하입니다.");
        return commands.execute(actor,"POST /api/v1/settings/message-templates",key,fingerprint(input),()-> {
            long template=insert("INSERT INTO request_message_template(name,type,message,updated_by,updated_at) VALUES (?,?,?,?,?)",name,type,message,id(actor.id()),now());
            var result=templateView(one("SELECT * FROM request_message_template WHERE id=?",template));audit(actor,"MESSAGE_TEMPLATE",template,"CREATE",result);return result(201,result,"/api/v1/settings/message-templates/"+template);
        });
    }

    /** 문구 수정 또는 논리 삭제를 이력과 함께 저장한다.
     * Args: actor 기획자, templateId 대상, key 멱등키, input 토큰·변경 필드, delete 논리삭제 여부.
     * Returns: 변경 문구. Raises: 입력·토큰·권한 오류. Side Effects: 문구·감사·멱등 DB 저장; 이미 생성한 요청 메시지 유지.
     */
    public CommandExecutor.Result updateTemplate(Actor actor,String templateId,String key,JsonNode input,boolean delete) {
        actor.requireRole("PLANNER");fields(input,delete?Set.of("expectedStateToken"):Set.of("expectedStateToken","name","type","message","active"));if(!delete)requireChange(input);long target=id(templateId);
        return commands.execute(actor,(delete?"DELETE":"PATCH")+" /api/v1/settings/message-templates/"+target,key,fingerprint(input),()-> {
            var row=required("SELECT * FROM request_message_template WHERE id=? FOR UPDATE",target);expect(input,templateView(row));
            String name=input.has("name")?text(input,"name",200):row.get("name").toString();
            String type=input.has("type")?templateType(text(input,"type",30)):row.get("type").toString();
            String message=input.has("message")?text(input,"message",5000):row.get("message").toString();
            if("EXPLANATION".equals(type)&&message.length()>4000)throw invalid("소명 요청 문구는 4000자 이하입니다.");
            boolean active=!delete&&bool(input,"active",Boolean.TRUE.equals(row.get("active")));
            jdbc.sql("UPDATE request_message_template SET name=?,type=?,message=?,active=?,revision=revision+1,updated_by=?,updated_at=? WHERE id=?").params(name,type,message,active,id(actor.id()),now(),target).update();
            var result=templateView(one("SELECT * FROM request_message_template WHERE id=?",target));audit(actor,"MESSAGE_TEMPLATE",target,delete?"DEACTIVATE":"UPDATE",result);return result(200,result,"");
        });
    }

    /** 로그인 사용자가 확인할 현재 서버 역할과 소속을 읽는다.
     * Args: actor 인증 사용자. Returns: 사용자·확인상태·토큰. Raises: DB 오류. Side Effects: DB 읽기.
     */
    public Map<String,Object> confirmation(Actor actor) {
        var row=required("SELECT * FROM app_user WHERE id=?",id(actor.id()));
        return object("actor",actor,"confirmed",row.get("profile_confirmed_at")!=null,"confirmedAt",instant(row.get("profile_confirmed_at")),"stateToken",token(row));
    }

    /** 사용자가 서버에 등록된 자신의 역할·소속을 명시적으로 확인했음을 기록한다.
     * Args: actor 인증 사용자, key 멱등키, input 조회 토큰만. Returns: 확인된 상태.
     * Raises: 토큰·권한 변경 오류. Side Effects: CommandExecutor의 사용자 행 잠금·역할/조직 재검사 아래 본인 확인 시각·멱등 응답 DB 저장. 역할·소속 지정 불가.
     */
    public CommandExecutor.Result confirm(Actor actor,String key,JsonNode input) {
        fields(input,Set.of("expectedStateToken"));
        return commands.execute(actor,"POST /api/v1/auth/profile-confirmation",key,fingerprint(input),()-> {
            expect(input,confirmation(actor));
            jdbc.sql("UPDATE app_user SET profile_confirmed_at=COALESCE(profile_confirmed_at,?) WHERE id=?").params(now(),id(actor.id())).update();
            return result(200,confirmation(actor),"");
        });
    }

    /** 조직 DTO를 만든다. Args: row DB 행. Returns: 조직·토큰. Raises: 없음. Side Effects: 없음. */
    private Map<String,Object> organizationView(Map<String,Object> row) {return object("id",row.get("id").toString(),"code",row.get("code"),"name",row.get("name"),"active",row.get("active"),"stateToken",token(row));}
    /** 계정 DTO에서 내부 수정번호를 숨긴다. Args: row DB 행. Returns: 계정·조직·토큰. Raises: DB 오류. Side Effects: 조직 읽기. */
    private Map<String,Object> userView(Map<String,Object> row) {
        Map<String,Object> organization=null;
        if(row.get("organization_id")!=null){var org=one("SELECT * FROM organization WHERE id=?",row.get("organization_id"));organization=object("id",org.get("id").toString(),"code",org.get("code"),"name",org.get("name"));}
        return object("id",row.get("id").toString(),"email",row.get("email"),"name",row.get("name"),"role",row.get("role"),"organization",organization,"active",row.get("active"),"confirmedAt",instant(row.get("profile_confirmed_at")),"stateToken",token(row));
    }
    /** 문구 DTO를 만든다. Args: row DB 행. Returns: 문구·토큰. Raises: 없음. Side Effects: 없음. */
    private Map<String,Object> templateView(Map<String,Object> row) {return object("id",row.get("id").toString(),"name",row.get("name"),"type",row.get("type"),"message",row.get("message"),"active",row.get("active"),"updatedAt",instant(row.get("updated_at")),"stateToken",token(row));}
    /** 역할별 조직 존재·활성 조건을 검사한다. Args: value 역할, organization 조직 JSON. Returns: 조직 ID/null. Raises: 입력 오류. Side Effects: 조직 읽기. */
    private Long organizationFor(String value,JsonNode organization) {
        role(value);
        if("PLANNER".equals(value)){if(organization!=null&&!organization.isNull())throw invalid("기획자에게 조직을 지정하지 않습니다.");return null;}
        if(organization==null||!organization.isTextual())throw invalid("조직 담당자는 활성 조직 ID가 필요합니다.");
        long target=id(organization.asText());if(rows("SELECT id FROM organization WHERE id=? AND active=TRUE",target).isEmpty())throw invalid("활성 조직을 선택해 주세요.");return target;
    }
    /** 관리자 변경을 직렬화한다. Args: 없음. Returns: 없음. Raises: DB 잠금 오류. Side Effects: 현재 트랜잭션 잠금. */
    private void guard(){jdbc.sql("SELECT id FROM settings_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();}
    /** 설정 변경 결과를 감사 이력으로 남긴다. Args: actor 행위자, type 유형, target ID, action 동작, snapshot 결과. Returns: 없음. Raises: DB 오류. Side Effects: 감사 INSERT. */
    private void audit(Actor actor,String type,long target,String action,Object snapshot){jdbc.sql("INSERT INTO settings_audit_event(entity_type,entity_id,action,snapshot_json,acted_by,acted_at) VALUES (?,?,?,?,?,?)").params(type,target,action,json(snapshot),id(actor.id()),now()).update();}
    /** 요청의 토큰을 확인한다. Args: input 요청, current 현재 DTO. Returns: 없음. Raises: 입력·충돌 오류. Side Effects: 없음. */
    private void expect(JsonNode input,Map<String,Object> current){if(!text(input,"expectedStateToken",128).equals(current.get("stateToken")))throw conflict("RESOURCE_CHANGED","설정이 변경되었습니다. 최신 내용을 다시 확인해 주세요.");}
    /** 변경 필드가 있는지 확인한다. Args: input 요청. Returns: 없음. Raises: 입력 오류. Side Effects: 없음. */
    private void requireChange(JsonNode input){if(input.size()<2)throw invalid("변경할 필드를 지정해 주세요.");}
    /** 허용된 JSON 객체 필드만 받는다. Args: input 요청, allowed 필드 집합. Returns: 없음. Raises: 입력 오류. Side Effects: 없음. */
    private void fields(JsonNode input,Set<String> allowed){if(input==null||!input.isObject())throw invalid("JSON 객체가 필요합니다.");var fields=input.fieldNames();while(fields.hasNext())if(!allowed.contains(fields.next()))throw invalid("허용되지 않은 설정 필드입니다.");}
    /** 필수 문자열을 검증한다. Args: input 객체, field 이름, max 최대길이. Returns: 양끝공백 제거값. Raises: 입력 오류. Side Effects: 없음. */
    private String text(JsonNode input,String field,int max){var value=input.path(field);if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>max)throw invalid(field+" 값을 확인해 주세요.");return value.asText().strip();}
    /** 선택 boolean을 읽는다. Args: input 객체, field 이름, fallback 기본값. Returns: boolean. Raises: 입력 오류. Side Effects: 없음. */
    private boolean bool(JsonNode input,String field,boolean fallback){if(!input.has(field))return fallback;if(!input.path(field).isBoolean())throw invalid(field+"는 true/false입니다.");return input.path(field).asBoolean();}
    /** 역할 열거형을 검사한다. Args: value 역할. Returns: 없음. Raises: 입력 오류. Side Effects: 없음. */
    private void role(String value){if(!Set.of("PLANNER","ORG_MANAGER").contains(value))throw invalid("역할을 확인해 주세요.");}
    /** 문구 종류를 검사한다. Args: value 종류. Returns: 유효 종류. Raises: 입력 오류. Side Effects: 없음. */
    private String templateType(String value){if(!Set.of("ANNUAL_PLAN","MONTHLY_ACTUAL","EXPLANATION").contains(value))throw invalid("문구 종류를 확인해 주세요.");return value;}
    /** 행의 변경 상태를 토큰으로 만든다. Args: row DB 행. Returns: 불투명 해시. Raises: 직렬화 오류. Side Effects: 없음. */
    private String token(Map<String,Object> row){return fingerprint(new TreeMap<>(row));}
    /** 정규 요청 지문을 만든다. Args: value 값. Returns: SHA-256. Raises: 직렬화 오류. Side Effects: 없음. */
    private String fingerprint(Object value){return hash(json(value).getBytes(StandardCharsets.UTF_8));}
    /** JSON을 직렬화한다. Args: value 값. Returns: JSON. Raises: IllegalStateException. Side Effects: 없음. */
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception exception){throw new IllegalStateException("설정 응답을 직렬화하지 못했습니다.");}}
    /** 행 목록을 읽는다. Args: sql 고정SQL, params 값. Returns: 행목록. Raises: DB 오류. Side Effects: 읽기. */
    private List<Map<String,Object>> rows(String sql,Object...params){return jdbc.sql(sql).params(params).query().listOfRows();}
    /** 한 행을 읽는다. Args: sql 고정SQL, params 값. Returns: 행. Raises: DB 오류. Side Effects: 읽기. */
    private Map<String,Object> one(String sql,Object...params){return jdbc.sql(sql).params(params).query().singleRow();}
    /** 존재해야 하는 리소스를 읽는다. Args: sql 고정SQL, params 값. Returns: 행. Raises: 404·DB 오류. Side Effects: 읽기/요청한 행잠금. */
    private Map<String,Object> required(String sql,Object...params){var found=rows(sql,params);if(found.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","자료를 찾을 수 없습니다.");return found.getFirst();}
    /** 생성 ID를 반환한다. Args: sql INSERT, params 값. Returns: ID. Raises: DB 오류. Side Effects: INSERT. */
    private long insert(String sql,Object...params){var holder=new GeneratedKeyHolder();jdbc.sql(sql).params(params).update(holder,"id");return holder.getKey().longValue();}
    /** 명령 결과를 만든다. Args: status HTTP, body DTO, location 경로. Returns: 성공 결과. Raises: 직렬화 오류. Side Effects: 없음. */
    private CommandExecutor.Result result(int status,Object body,String location){return new CommandExecutor.Result(status,json(body),location);}
    /** UTC 시각을 읽는다. Args: 없음. Returns: UTC. Raises: 없음. Side Effects: 시스템시계 읽기. */
    private OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    /** 안전한 입력 오류를 만든다. Args: message 안내. Returns: 400 예외. Raises: 없음. Side Effects: 없음. */
    private ApiException invalid(String message){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST",message);}
    /** 안전한 상태 충돌을 만든다. Args: code 코드, message 안내. Returns: 409 예외. Raises: 없음. Side Effects: 없음. */
    private ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
}
