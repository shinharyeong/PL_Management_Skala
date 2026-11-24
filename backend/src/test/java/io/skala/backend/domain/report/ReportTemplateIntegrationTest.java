package io.skala.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.global.security.SessionTokenStore;
import io.skala.backend.support.PostgresIntegrationTest;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** RPT-03~07 자료 틀 저장·불러오기(형식은 AI 모델 JSON 스키마)와 기준 월 선택지 */
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ReportTemplateIntegrationTest extends PostgresIntegrationTest {

    /**
     * 데모 화면이 저장하는 모양의 틀. 백엔드는 봉투(schema_version·name·blocks[].id·kind)만 검사하고
     * 블록 세부(numbercard 같은 종류, order·layout·styles 등)는 그대로 보관한다.
     */
    private static final String DEFINITION = """
            {"schema_version": 1, "name": "아무 이름", "sourceFile": "월보고.pdf",
             "baseRule": {"mode": "latest_master"}, "order": ["b1", "b2", "c1"], "layout": {"b2": {"w": 50}},
             "blocks": [
               {"id": "b1", "kind": "title", "text": "월 실적 보고"},
               {"id": "b2", "kind": "table", "metric": "OP_BY_ORG", "header_spans": [[0, 1]]},
               {"id": "c1", "kind": "numbercard", "metric": "영업이익"}
             ]}""";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SessionTokenStore sessions;
    @Autowired
    ObjectMapper objectMapper;

    String planner;
    String manager;
    long plannerId;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        long org = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '틀조직') RETURNING id", Long.class, "TT" + s);
        plannerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role) VALUES (?, '기획', 'PLANNER') RETURNING id",
                Long.class, "tp" + s + "@sk.com");
        long managerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role, organization_id) VALUES (?, '담당', 'ORG_MANAGER', ?) RETURNING id",
                Long.class, "tm" + s + "@sk.com", org);
        planner = sessions.issue(plannerId).token();
        manager = sessions.issue(managerId).token();
    }

    @Test
    void saveVersionsAndLoadTemplates() throws Exception {
        // RPT-04 새 틀: definition의 id·name은 서버 값으로 맞춘다
        String key = "it-" + UUID.randomUUID();
        JsonNode created = json(create("월 마감 보고", DEFINITION, key)
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.latestVersion").value(1))
                .andExpect(jsonPath("$.definition.name").value("월 마감 보고"))
                .andExpect(jsonPath("$.definition.blocks[1].metric").value("OP_BY_ORG"))
                .andExpect(jsonPath("$.definition.blocks[2].kind").value("numbercard"))
                .andExpect(jsonPath("$.definition.layout.b2.w").value(50)));
        String id = created.get("id").asText();
        assertThat(created.at("/definition/id").asText()).isEqualTo(id);
        create("월 마감 보고", DEFINITION, key).andExpect(header().string("Idempotency-Replayed", "true"));

        // 봉투가 깨지면 422 INVALID_TEMPLATE: 블록 id 없음, 스키마 버전이 숫자가 아님, blocks가 배열이 아님
        create("잘못된 틀", DEFINITION.replace("{\"id\": \"b2\", ", "{"), "it-" + UUID.randomUUID())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TEMPLATE"))
                .andExpect(jsonPath("$.errors[0].field").value("definition.blocks[1]"))
                .andExpect(jsonPath("$.errors[0].code").value("required"));
        create("잘못된 틀", DEFINITION.replace("\"schema_version\": 1", "\"schema_version\": \"1\""), "it-" + UUID.randomUUID())
                .andExpect(status().isUnprocessableEntity());
        create("잘못된 틀", "{\"schema_version\": 1, \"name\": \"x\", \"blocks\": {}}", "it-" + UUID.randomUUID())
                .andExpect(status().isUnprocessableEntity());

        // RPT-05 새 버전. 보고 있던 버전이 최신이 아니면 409
        String edited = DEFINITION.replace("월 실적 보고", "월 실적 보고(수정)");
        version(id, edited, 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.latestVersion").value(2));
        version(id, edited, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_REVISION_CHANGED"));

        // RPT-06 최신·지정 버전
        mvc.perform(auth(get("/api/v1/report/templates/{id}", id), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.definition.blocks[0].text").value("월 실적 보고(수정)"));
        mvc.perform(auth(get("/api/v1/report/templates/{id}", id).param("version", "1"), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.blocks[0].text").value("월 실적 보고"))
                .andExpect(jsonPath("$.latestVersion").value(2));
        mvc.perform(auth(get("/api/v1/report/templates/{id}", id).param("version", "9"), planner))
                .andExpect(status().isNotFound());

        // RPT-03 목록(최신 순, 커서)
        String second = json(create("두 번째 틀", DEFINITION, "it-" + UUID.randomUUID())).get("id").asText();
        JsonNode page = json(mvc.perform(auth(get("/api/v1/report/templates").param("limit", "1"), planner))
                .andExpect(status().isOk()));
        assertThat(page.at("/items/0/id").asText()).isEqualTo(second);
        assertThat(page.at("/items/0/definition").isNull()).isTrue();
        JsonNode next = json(mvc.perform(auth(get("/api/v1/report/templates").param("limit", "1")
                .param("cursor", page.get("nextCursor").asText()), planner)).andExpect(status().isOk()));
        assertThat(next.at("/items/0/id").asText()).isEqualTo(id);
        assertThat(next.at("/items/0/latestVersion").asInt()).isEqualTo(2);

        // 조직 담당자는 쓸 수 없다
        mvc.perform(auth(get("/api/v1/report/templates"), manager)).andExpect(status().isForbidden());
    }

    @Test
    void periodsShowConfirmedProvisionalAndNone() throws Exception {
        int year;
        do {
            year = ThreadLocalRandom.current().nextInt(2100, 9999);
        } while (jdbc.queryForObject("SELECT count(*) FROM annual_plan WHERE year = ?", Integer.class, year) > 0);
        long org = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '기간조직') RETURNING id", Long.class,
                "TP" + UUID.randomUUID().toString().substring(0, 8));
        long plan = jdbc.queryForObject("INSERT INTO annual_plan(year, status) VALUES (?, 'CONFIRMED') RETURNING id", Long.class, year);
        long october = request(plan, year, 10, org);
        long octSubmission = submission(october, org);
        long master = jdbc.queryForObject("""
                INSERT INTO master_version(request_id, seq, trigger_submission_id, content_sha256, calculation_version, created_by, created_at)
                VALUES (?, 1, ?, ?, 'test', ?, now()) RETURNING id""", Long.class, october, octSubmission, "c".repeat(64), plannerId);
        jdbc.update("INSERT INTO master_source(master_id, organization_id, submission_id) VALUES (?, ?, ?)", master, org, octSubmission);
        submission(request(plan, year, 11, org), org); // 11월은 승인본만(잠정)

        mvc.perform(auth(get("/api/v1/report/periods").param("year", String.valueOf(year)), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(year))
                .andExpect(jsonPath("$.months.length()").value(12))
                .andExpect(jsonPath("$.months[9].state").value("CONFIRMED"))
                .andExpect(jsonPath("$.months[9].masterId").value(String.valueOf(master)))
                .andExpect(jsonPath("$.months[10].state").value("PROVISIONAL"))
                .andExpect(jsonPath("$.months[10].masterId").isEmpty())
                .andExpect(jsonPath("$.months[0].state").value("NONE"))
                .andExpect(jsonPath("$.default.month").isNumber());
        mvc.perform(auth(get("/api/v1/report/periods").param("year", "1999"), planner)).andExpect(status().isBadRequest());
    }

    private long request(long plan, int year, int month, long org) {
        long id = jdbc.queryForObject("""
                INSERT INTO submission_request(annual_plan_id, type, month, status, requested_by, requested_at, period_key)
                VALUES (?, 'MONTHLY_ACTUAL', ?, 'OPEN', ?, now(), ?) RETURNING id""", Long.class, plan, month, plannerId,
                year + "-T-" + month);
        jdbc.update("INSERT INTO request_target(request_id, organization_id, selected) VALUES (?, ?, true)", id, org);
        return id;
    }

    private long submission(long requestId, long org) {
        String key = "uploads/test/" + UUID.randomUUID() + ".xlsx";
        jdbc.update("INSERT INTO stored_object(object_key, content_type, size_bytes, sha256) VALUES (?, 'application/octet-stream', 1, ?)",
                key, "0".repeat(64));
        return jdbc.queryForObject("""
                INSERT INTO submission(request_id, organization_id, seq, status, original_filename, source_object_key,
                    source_sha256, uploaded_by, uploaded_at, submitted_by, submitted_at, reviewed_by, reviewed_at, approved_seq)
                VALUES (?, ?, 1, 'APPROVED', 'f.xlsx', ?, ?, ?, now(), ?, now(), ?, now(), 1) RETURNING id""", Long.class,
                requestId, org, key, "a".repeat(64), plannerId, plannerId, plannerId);
    }

    private ResultActions create(String name, String definition, String key) throws Exception {
        return mvc.perform(auth(post("/api/v1/report/templates"), planner).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"definition\":" + definition + "}"));
    }

    private ResultActions version(String id, String definition, int expected) throws Exception {
        return mvc.perform(auth(post("/api/v1/report/templates/{id}/versions", id), planner)
                .header("Idempotency-Key", "it-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + expected + ",\"definition\":" + definition + "}"));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }
}
