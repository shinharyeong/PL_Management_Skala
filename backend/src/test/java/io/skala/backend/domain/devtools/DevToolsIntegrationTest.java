package io.skala.backend.domain.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.support.PostgresIntegrationTest;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = {"app.dev-tools.enabled=true", "app.dev-tools.access-key=test-dev-key",
        "app.parser.sweeper.enabled=false"})
@Transactional
class DevToolsIntegrationTest extends PostgresIntegrationTest {

    private static final String KEY_HEADER = DevToolsService.ACCESS_KEY_HEADER;
    private static final String KEY = "test-dev-key";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void seed() {
        jdbc.update("insert into app_user(email,name,role,active) values ('dev-copy-test@sk.com','개발검증','PLANNER',true),"
                + "('inactive-copy-test@sk.com','비활성','PLANNER',false)");
    }

    @Test
    void lists_active_db_users_only_with_access_key_and_issues_real_session() throws Exception {
        // 키가 없거나 틀리면 계정 목록도 보이지 않는다
        mvc.perform(get("/api/v1/dev/accounts")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dev/accounts").header(KEY_HEADER, "wrong")).andExpect(status().isForbidden());
        // 허용 목록 없이 DB의 활성 사용자를 보여 준다(비활성은 제외)
        mvc.perform(get("/api/v1/dev/accounts").header(KEY_HEADER, KEY)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("dev-copy-test@sk.com")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("inactive-copy-test@sk.com"))));
        mvc.perform(post("/api/v1/dev/sessions").header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"inactive-copy-test@sk.com\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/dev/sessions").header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"outsider@sk.com\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/dev/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"dev-copy-test@sk.com\"}")).andExpect(status().isForbidden());
        String token = login();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PLANNER"));
    }

    @Test
    void shared_copy_requires_login_is_visible_without_login_and_rejects_stale_edits() throws Exception {
        String key = "pages.devToolsIntegrationTest";
        String body = json.writeValueAsString(Map.of("key", key, "value", "공통 문구", "expectedRevision", 0));
        mvc.perform(put("/api/v1/dev/ui-copy").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        String token = login();
        mvc.perform(put("/api/v1/dev/ui-copy").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        mvc.perform(get("/api/v1/ui-copy")).andExpect(status().isOk()).andExpect(content().string(Matchers.containsString("공통 문구")));
        mvc.perform(put("/api/v1/dev/ui-copy").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select copy_value from ui_copy_override where copy_key=?", String.class, key)).isEqualTo("공통 문구");
        mvc.perform(put("/api/v1/dev/ui-copy").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("key", key, "value", "되돌림", "expectedRevision", 1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(put("/api/v1/dev/ui-copy").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("key", "pages.__proto__", "value", "bad", "expectedRevision", 0))))
                .andExpect(status().isBadRequest());
    }

    private String login() throws Exception {
        String response = mvc.perform(post("/api/v1/dev/sessions").header(KEY_HEADER, KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dev-copy-test@sk.com\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("token").asText();
    }
}
