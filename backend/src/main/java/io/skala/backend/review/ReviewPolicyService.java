package io.skala.backend.review;

import static io.skala.backend.submission.ApiData.*;

import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.Actor;
import jakarta.validation.constraints.NotBlank;
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

/** 연간·월간에 공통인 소명 필수 모드를 이력으로 관리하고 저장된 변경 근거에 후보를 적용한다. */
@Service
public class ReviewPolicyService {
    private final JdbcClient jdbc;
    private final CommandExecutor commands;
    private final ReviewComparisonService comparisons;
    private final ReviewService reviews;

    /** 정책의 DB·명령·표시 의존성을 받는다. Args: jdbc DB, commands 멱등 실행기, comparisons JSON, reviews 행위자. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public ReviewPolicyService(JdbcClient jdbc,CommandExecutor commands,ReviewComparisonService comparisons,ReviewService reviews) {
        this.jdbc=jdbc;this.commands=commands;this.comparisons=comparisons;this.reviews=reviews;
    }

    /** 현재 전역 정책을 읽는다. Args: actor 인증 사용자. Returns: 정책·기대 토큰. Raises: DB 오류. Side Effects: DB 읽기. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> current(Actor actor) { return view(latest()); }

    /** 전역 정책을 새 이력으로 추가한다. Args: actor 기획, key 멱등키, input 기대 토큰·후보 모드. Returns: 새 정책 DTO. Raises: 권한·입력·토큰 오류. Side Effects: 정책·멱등 응답 저장; 기존 검증·소명·원본은 수정하지 않는다. */
    public CommandExecutor.Result apply(Actor actor,String key,ApplyInput input) {
        actor.requireRole("PLANNER");validateMode(input.requirementMode());
        return commands.execute(actor,"POST /api/v1/review-policy",key,fingerprint(input),()->{
            jdbc.sql("SELECT id FROM review_policy_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
            if(!Objects.equals(input.expectedStateToken(),current(actor).get("stateToken")))
                throw new ApiException(HttpStatus.CONFLICT,"RESOURCE_CHANGED","소명 정책이 바뀌었습니다. 다시 조회해 주세요.");
            var keyHolder=new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO review_policy(requirement_mode,applied_by,applied_at) VALUES (?,?,?)")
                    .params(input.requirementMode(),id(actor.id()),OffsetDateTime.now(ZoneOffset.UTC)).update(keyHolder,"id");
            return new CommandExecutor.Result(201,comparisons.json(view(jdbc.sql("SELECT * FROM review_policy WHERE id=?").param(Objects.requireNonNull(keyHolder.getKey()).longValue()).query().singleRow())),null);
        });
    }

    /** 조직별 최신 제출완료본의 고정 변경·Gap·명시요청에 후보 모드를 적용한다.
     * Args: actor 기획, input 연간/월간 요청·후보 모드. Returns: 중복 없는 필수 검토·조직 수와 변경/Gap/명시요청별 수.
     * Raises: 권한·요청·입력 오류. Side Effects: 읽기만 수행하며 임계값·제출 당시 정책·소명 회차를 바꾸지 않는다. 임계값 후보 재계산은 Gap 미리보기를 사용한다.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(Actor actor,PreviewInput input) {
        actor.requireRole("PLANNER");validateMode(input.requirementMode());long requestId=id(input.requestId());
        if(jdbc.sql("SELECT COUNT(*) FROM submission_request WHERE id=?").param(requestId).query(Integer.class).single()==0)
            throw new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","요청을 찾을 수 없습니다.");
        var sources=jdbc.sql("""
                SELECT s.id,s.organization_id FROM submission s JOIN request_target t ON t.request_id=s.request_id AND t.organization_id=s.organization_id
                WHERE s.request_id=? AND t.excluded=FALSE AND s.status<>'DRAFT'
                  AND s.seq=(SELECT MAX(x.seq) FROM submission x WHERE x.request_id=s.request_id AND x.organization_id=s.organization_id AND x.status<>'DRAFT')
                ORDER BY s.organization_id
                """).param(requestId).query().listOfRows();
        int changed=0,gap=0,explicit=0,required=0,excluded=0;var organizations=new HashSet<Object>();var sourceIds=new ArrayList<String>();
        for(var source:sources) {
            sourceIds.add(source.get("id").toString());
            var items=jdbc.sql("SELECT change_detected,gap_exceeded,workflow_state FROM review_item WHERE submission_id=? AND active=TRUE").param(source.get("id")).query().listOfRows();
            for(var item:items) {
                if("EXCLUDED".equals(item.get("workflow_state"))) { excluded++;continue; }
                boolean isChanged=Boolean.TRUE.equals(item.get("change_detected")),isGap=Boolean.TRUE.equals(item.get("gap_exceeded")),isExplicit="REQUESTED".equals(item.get("workflow_state"));
                if(isChanged)changed++;if(isGap)gap++;if(isExplicit)explicit++;
                if(isGap||isExplicit||("ALL_CHANGES".equals(input.requirementMode())&&isChanged)) { required++;organizations.add(source.get("organization_id")); }
            }
        }
        return object("requestId",input.requestId(),"requirementMode",input.requirementMode(),"sourceSubmissionIds",sourceIds,
                "changedItemCount",changed,"gapExceededItemCount",gap,"explicitlyRequestedItemCount",explicit,"requiredItemCount",required,
                "requiredOrganizationCount",organizations.size(),"excludedItemCount",excluded,"evaluatedAt",OffsetDateTime.now(ZoneOffset.UTC).toInstant().toString());
    }

    /** 최신 정책 행을 읽는다. Args: 없음. Returns: V11 초기정책 또는 최근 적용행. Raises: DB 오류. Side Effects: DB 읽기. */
    private Map<String,Object> latest() { return jdbc.sql("SELECT * FROM review_policy ORDER BY id DESC LIMIT 1").query().singleRow(); }
    /** 정책 행을 외부 DTO로 바꾼다. Args: row 정책 행. Returns: 모드·적용자·적용시각·토큰. Raises: DB 오류. Side Effects: 행위자 읽기. */
    private Map<String,Object> view(Map<String,Object> row) { return object("id",row.get("id").toString(),"requirementMode",row.get("requirement_mode"),"appliedBy",reviews.actor(row.get("applied_by")),"appliedAt",instant(row.get("applied_at")),"stateToken",fingerprint(object("id",row.get("id").toString(),"requirementMode",row.get("requirement_mode")))); }
    /** 허용된 후보 모드인지 검사한다. Args: mode 입력. Returns: 없음. Raises: 400 입력 오류. Side Effects: 없음. */
    private void validateMode(String mode) { if(!Set.of("ALL_CHANGES","GAP_ONLY").contains(Objects.toString(mode,"")))throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","ALL_CHANGES 또는 GAP_ONLY를 선택해 주세요."); }
    /** 명령과 상태의 해시를 만든다. Args: value 직렬화 대상. Returns: SHA256. Raises: JSON 오류. Side Effects: 메모리 계산. */
    private String fingerprint(Object value) { return hash(comparisons.json(value).getBytes(StandardCharsets.UTF_8)); }
    /** 전역 정책 적용 입력이다. */
    public record ApplyInput(@NotBlank String expectedStateToken,@NotBlank String requirementMode) {}
    /** 고정된 제출 근거의 정책 영향 조회 입력이다. */
    public record PreviewInput(@NotBlank String requestId,@NotBlank String requirementMode) {}
}
