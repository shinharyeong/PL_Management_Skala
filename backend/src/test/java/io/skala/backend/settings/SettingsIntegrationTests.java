package io.skala.backend.settings;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** 설정 권한·최초 확인·불변 대상·멱등 갱신의 HTTP와 DB 경계를 검증한다. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:settings_v9;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
    "app.local-auth.planner-email=settings-planner@example.test",
    "app.local-auth.planner-token=settings-planner-token-at-least-32-characters",
    "app.local-auth.organization-email=settings-manager@example.test",
    "app.local-auth.organization-token=settings-manager-token-at-least-32-characters"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test","local"})
@Transactional
class SettingsIntegrationTests {
    private static final String PLANNER="Bearer settings-planner-token-at-least-32-characters";
    private static final String MANAGER="Bearer settings-manager-token-at-least-32-characters";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    /** 합성 조직과 사용자만 준비한다. Args: 없음. Returns: 없음. Raises: DB 오류. Side Effects: 롤백되는 H2 INSERT. */
    @BeforeEach
    void prepare(){
        jdbc.sql("INSERT INTO organization(id,code,name) VALUES (701,'SET-A','설정 조직'),(702,'SET-B','변경 조직')").update();
        jdbc.sql("INSERT INTO app_user(id,email,name,role,organization_id) VALUES (711,'settings-planner@example.test','설정 기획','PLANNER',NULL),(712,'settings-manager@example.test','설정 담당','ORG_MANAGER',701)").update();
    }

    /** 담당자의 설정 접근과 요청 본문을 통한 권한 승격을 차단한다.
     * Args: 없음. Returns: 없음. Raises: HTTP 검증 오류. Side Effects: 테스트 요청만 실행한다.
     */
    @Test
    void managerCannotManageUsersOrConfirmAnArbitraryRole() throws Exception {
        for(String path:new String[]{"users","organizations","message-templates"})mvc.perform(get("/api/v1/settings/"+path).header("Authorization",MANAGER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/settings/users").header("Authorization",MANAGER).header("Idempotency-Key","forbidden-user-create")
            .contentType("application/json").content("{\"email\":\"new@example.test\",\"name\":\"이름\",\"role\":\"PLANNER\"}")).andExpect(status().isForbidden());
        var confirmation=read("/api/v1/auth/profile-confirmation",MANAGER);
        mvc.perform(post("/api/v1/auth/profile-confirmation").header("Authorization",MANAGER).header("Idempotency-Key","invalid-profile-confirm")
            .contentType("application/json").content(mapper.writeValueAsBytes(Map.of("expectedStateToken",confirmation.path("stateToken").asText(),"role","PLANNER")))).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT role FROM app_user WHERE id=712").query(String.class).single()).isEqualTo("ORG_MANAGER");
    }

    /** 최초 확인을 영속하고 소속 변경 시 재확인하며 자기 권한 제거를 거부한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·JSON 검증 오류. Side Effects: 롤백되는 계정·감사·확인 DB 변경.
     */
    @Test
    void confirmationIsIdempotentAndAffiliationChangesRequireAnotherConfirmation() throws Exception {
        var initial=read("/api/v1/auth/profile-confirmation",MANAGER);assertThat(initial.path("confirmed").asBoolean()).isFalse();
        var body=Map.of("expectedStateToken",initial.path("stateToken").asText());
        var confirmed=write(post("/api/v1/auth/profile-confirmation"),MANAGER,"confirm-profile-001",body,200);
        var replay=write(post("/api/v1/auth/profile-confirmation"),MANAGER,"confirm-profile-001",body,200);
        assertThat(replay.path("confirmedAt")).isEqualTo(confirmed.path("confirmedAt"));assertThat(confirmed.path("confirmed").asBoolean()).isTrue();
        var manager=read("/api/v1/settings/users?organizationId=701",PLANNER).path("items").get(0);
        var changed=write(patch("/api/v1/settings/users/712"),PLANNER,"change-affiliation-001",Map.of("expectedStateToken",manager.path("stateToken").asText(),"organizationId","702"),200);
        assertThat(changed.path("confirmedAt").isNull()).isTrue();
        assertThat(read("/api/v1/auth/profile-confirmation",MANAGER).path("actor").path("organization").path("id").asText()).isEqualTo("702");
        var planner=read("/api/v1/settings/users?role=PLANNER",PLANNER).path("items").get(0);
        write(patch("/api/v1/settings/users/711"),PLANNER,"deny-self-disable-001",Map.of("expectedStateToken",planner.path("stateToken").asText(),"active",false),409);
        assertThat(jdbc.sql("SELECT active FROM app_user WHERE id=711").query(Boolean.class).single()).isTrue();
    }

    /** 문구 이력과 멱등·동시성·논리삭제를 검증한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·JSON 검증 오류. Side Effects: 롤백되는 문구·감사 DB 변경.
     */
    @Test
    void messageTemplatesPreserveHistoryAndRejectStaleEdits() throws Exception {
        var created=write(post("/api/v1/settings/message-templates"),PLANNER,"template-create-001",Map.of("name","월간 안내","type","MONTHLY_ACTUAL","message","기한 내 제출해 주세요."),201);
        String path="/api/v1/settings/message-templates/"+created.path("id").asText();
        var body=Map.of("expectedStateToken",created.path("stateToken").asText(),"message","수정 안내");
        var updated=write(patch(path),PLANNER,"template-update-001",body,200);
        write(patch(path),PLANNER,"template-update-002",body,409);
        var deleted=write(delete(path),PLANNER,"template-delete-001",Map.of("expectedStateToken",updated.path("stateToken").asText()),200);
        assertThat(deleted.path("active").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_message_template").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM settings_audit_event WHERE entity_type='MESSAGE_TEMPLATE'").query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT snapshot_json FROM settings_audit_event WHERE action='CREATE'").query(String.class).single()).contains("기한 내 제출해 주세요.");
    }

    /** 새 조직이 기존 요청 집합을 바꾸지 않고 열린 필수 조직은 비활성화하지 못한다.
     * Args: 없음. Returns: 없음. Raises: HTTP·DB 검증 오류. Side Effects: 롤백되는 조직·요청 DB 변경.
     */
    @Test
    void organizationManagementKeepsExistingRequestTargetsFixed() throws Exception {
        var request=write(post("/api/v1/submission-requests"),PLANNER,"settings-request-001",Map.of("type","ANNUAL_PLAN","year",2038),201);
        String requestId=request.path("id").asText();
        write(post("/api/v1/settings/organizations"),PLANNER,"organization-create-001",Map.of("code","SET-C","name","신설 조직"),201);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM request_target WHERE request_id=?").param(Long.parseLong(requestId)).query(Integer.class).single()).isEqualTo(2);
        JsonNode target=null;for(var organization:read("/api/v1/settings/organizations",PLANNER).path("items"))if(organization.path("id").asText().equals("701"))target=organization;
        assertThat(target).isNotNull();
        var failed=write(patch("/api/v1/settings/organizations/701"),PLANNER,"organization-disable-001",Map.of("expectedStateToken",target.path("stateToken").asText(),"active",false),409);
        assertThat(failed.path("code").asText()).isEqualTo("ORGANIZATION_IN_USE");
        assertThat(jdbc.sql("SELECT active FROM organization WHERE id=701").query(Boolean.class).single()).isTrue();
    }

    /** 인증 조회 결과를 JSON으로 읽는다. Args: path 경로, bearer 인증. Returns: JSON. Raises: HTTP·JSON 오류. Side Effects: 테스트 GET. */
    private JsonNode read(String path,String bearer)throws Exception{return mapper.readTree(mvc.perform(get(path).header("Authorization",bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());}
    /** JSON 명령을 실행해 예상 HTTP 상태를 확인한다. Args: request 빌더, bearer 인증, key 멱등키, body 본문, status 예상코드. Returns: JSON. Raises: 검증 오류. Side Effects: 테스트 명령 DB 변경. */
    private JsonNode write(MockHttpServletRequestBuilder request,String bearer,String key,Object body,int status)throws Exception{return mapper.readTree(mvc.perform(request.header("Authorization",bearer).header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsBytes(body))).andExpect(status().is(status)).andReturn().getResponse().getContentAsByteArray());}
}
