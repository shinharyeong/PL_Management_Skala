package io.skala.backend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.user.service.AuthMailSender;
import io.skala.backend.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@Import(AuthFlowIntegrationTest.CapturingMail.class)
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class AuthFlowIntegrationTest extends PostgresIntegrationTest {

    static final Map<String, String> SENT = new ConcurrentHashMap<>();

    @TestConfiguration
    static class CapturingMail {
        @Bean
        @Primary
        AuthMailSender capturingAuthMailSender() {
            return SENT::put;
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void orgManagerLogsInSeesOwnMenusAndLogsOut() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long orgId = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '테스트부문') RETURNING id", Long.class, "A" + suffix);
        String email = "org-" + suffix + "@sk.com";
        jdbc.update("INSERT INTO app_user(email, name, role, organization_id) VALUES (?, '담당자', 'ORG_MANAGER', ?)", email, orgId);

        mvc.perform(post("/api/v1/auth/codes").contentType(MediaType.APPLICATION_JSON).content(json(Map.of("email", email))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.email").value(email));
        mvc.perform(post("/api/v1/auth/codes").contentType(MediaType.APPLICATION_JSON).content(json(Map.of("email", email))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CODE_RESEND_WAIT"));

        String code = SENT.get(email);
        String wrong = code.equals("000000") ? "111111" : "000000";
        mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "code", wrong))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_AUTH_CODE"))
                .andExpect(jsonPath("$.errors[0].code").value("MISMATCH"));

        String body = mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "code", code))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.me.role").value("ORG_MANAGER"))
                .andExpect(jsonPath("$.me.organization.code").value("A" + suffix))
                .andExpect(jsonPath("$.me.homePath").value("/requests"))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("token").asText();

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus[0].key").value("INBOX"));
        mvc.perform(get("/api/v1/me/badges").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openRequests").value(0))
                .andExpect(jsonPath("$.unreadNotifications").doesNotExist());

        mvc.perform(delete("/api/v1/auth/session").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void plannerGetsPlannerMenusAndBadges() throws Exception {
        String email = "plan-" + UUID.randomUUID().toString().substring(0, 8) + "@sktelecom.com";
        jdbc.update("INSERT INTO app_user(email, name, role) VALUES (?, '기획', 'PLANNER')", email);
        mvc.perform(post("/api/v1/auth/codes").contentType(MediaType.APPLICATION_JSON).content(json(Map.of("email", email))))
                .andExpect(status().isAccepted());
        JsonNode login = objectMapper.readTree(mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "code", SENT.get(email)))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(login.at("/me/organization").isNull()).isTrue();
        assertThat(login.at("/me/homePath").asText()).isEqualTo("/planning");
        mvc.perform(get("/api/v1/me/badges").header("Authorization", "Bearer " + login.get("token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadNotifications").value(0))
                .andExpect(jsonPath("$.openRequests").doesNotExist());
    }

    @Test
    void unregisteredOrForeignDomainIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/codes").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "nobody-" + UUID.randomUUID() + "@sk.com"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNREGISTERED_EMAIL"));
        mvc.perform(post("/api/v1/auth/codes").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "someone@gmail.com"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void protectedApiNeedsToken() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").exists());
        mvc.perform(post("/api/v1/submissions/1/validation").header("Authorization", "Bearer not-a-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
