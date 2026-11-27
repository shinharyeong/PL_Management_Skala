package io.skala.backend.domain.decisionagent;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.report.service.ReportAiClient;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.SessionTokenStore;
import io.skala.backend.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 실제 PostgreSQL에서 소유권·버전 충돌·멱등성·불변 기준·결측 차단을 검증한다. */
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class DecisionAgentIntegrationTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SessionTokenStore sessions;
    @MockitoBean ReportAiClient ai;
    private long plannerId;
    private long organizationId;
    private long submissionId;
    private long masterId;
    private long revenueItem;
    private long costLine;
    private int year;
    private String planner;
    private String otherPlanner;
    private String manager;

    /** 목적: 요청 대상 조직과 서로 다른 원천 행을 등록해 완전한 12개월 기준을 준비한다. Args: 없음. Returns: 없음. Raises: DB 오류. Side Effects: 테스트 DB 레코드·세션 생성, 파일 생성 없음. */
    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        organizationId = jdbc.queryForObject("INSERT INTO organization(code,name) VALUES (?,'의사결정 조직') RETURNING id", Long.class, "DA" + unique);
        plannerId = jdbc.queryForObject("INSERT INTO app_user(email,name,role) VALUES (?,'기획','PLANNER') RETURNING id", Long.class, "da" + unique + "@sk.com");
        long otherId = jdbc.queryForObject("INSERT INTO app_user(email,name,role) VALUES (?,'다른 기획','PLANNER') RETURNING id", Long.class, "db" + unique + "@sk.com");
        long managerId = jdbc.queryForObject("INSERT INTO app_user(email,name,role,organization_id) VALUES (?,'담당','ORG_MANAGER',?) RETURNING id", Long.class, "dc" + unique + "@sk.com", organizationId);
        planner = sessions.issue(plannerId).token();
        otherPlanner = sessions.issue(otherId).token();
        manager = sessions.issue(managerId).token();
        do {
            year = ThreadLocalRandom.current().nextInt(2100, 9999);
        } while (jdbc.queryForObject("SELECT count(*) FROM annual_plan WHERE year=?", Integer.class, year) != 0);
        long plan = jdbc.queryForObject("INSERT INTO annual_plan(year,status) VALUES (?,'CONFIRMED') RETURNING id", Long.class, year);
        long request = jdbc.queryForObject("""
                INSERT INTO submission_request(annual_plan_id,type,month,status,requested_by,requested_at,period_key)
                VALUES (?,'MONTHLY_ACTUAL',11,'OPEN',?,now(),?) RETURNING id
                """, Long.class, plan, plannerId, year + "-DA-11");
        jdbc.update("INSERT INTO request_target(request_id,organization_id,selected) VALUES (?,?,true)", request, organizationId);
        String objectKey = "decision-test-" + unique;
        jdbc.update("INSERT INTO stored_object(object_key,content_type,size_bytes,sha256) VALUES (?,'application/octet-stream',1,?)", objectKey, "a".repeat(64));
        submissionId = jdbc.queryForObject("""
                INSERT INTO submission(request_id,organization_id,seq,status,original_filename,source_object_key,
                    source_sha256,uploaded_by,uploaded_at,approved_seq)
                VALUES (?,?,1,'APPROVED','fixture.xlsx',?,?,?,now(),1) RETURNING id
                """, Long.class, request, organizationId, objectKey, "a".repeat(64), plannerId);
        masterId = jdbc.queryForObject("""
                INSERT INTO master_version(request_id,seq,trigger_submission_id,content_sha256,calculation_version,created_by,created_at)
                VALUES (?,1,?,?,'test',?,now()) RETURNING id
                """, Long.class, request, submissionId, "b".repeat(64), plannerId);
        jdbc.update("INSERT INTO master_source(master_id,organization_id,submission_id) VALUES (?,?,?)", masterId, organizationId, submissionId);
        revenueItem = item("REVENUE");
        values(revenueItem, "100", 1);
        costLine = values(item("COST"), "20", 2);
        when(ai.post(eq("/v1/decision-agent/compose"), any(), anyLong())).thenThrow(new BusinessException(ErrorCode.PROCESSING_UNAVAILABLE));
    }

    /** 목적: 생성·조회·후보·실행 경로의 멱등성, 권한, 기준 고정, AI 장애 대체를 검증한다. Args: 없음. Returns: 없음. Raises: 검증/HTTP 오류. Side Effects: 테스트 DB 분석 변경. */
    @Test
    void fullLifecycleKeepsSnapshotAndProtectsOwnership() throws Exception {
        String createKey = key();
        String id = json(create(planner, createKey).andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshot.baselineProfitWon").value("960"))
                .andExpect(jsonPath("$.revision").value(1))).get("id").asText();
        create(planner, createKey).andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "true"));
        mvc.perform(auth(get("/api/v1/decision-agent/analyses/" + id), otherPlanner)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/decision-agent/analyses"), otherPlanner)).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(auth(get("/api/v1/decision-agent/baselines").param("year", String.valueOf(year)), manager)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/decision-agent/baselines").param("year", String.valueOf(year)), planner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].masterId").value(String.valueOf(masterId)));

        Candidate candidate = new Candidate("one", String.valueOf(organizationId), "개선 검토", EffectType.NET_PROFIT, "40", null,
                "5", "10", 12, Category.OPERATIONS, "", EvidenceStatus.REVIEWED, "계약 조건 검토", "계약-01");
        String candidateKey = key();
        CandidatesRequest candidates = new CandidatesRequest(1, List.of(candidate));
        save(id, candidateKey, candidates).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        save(id, candidateKey, candidates).andExpect(header().string("Idempotency-Replayed", "true"));
        save(id, key(), candidates).andExpect(status().isConflict());
        String runKey = key();
        run(id, runKey, 2).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.result.conditionalOutlookWon").value("985"))
                .andExpect(jsonPath("$.result.conditionalGapWon").value("65"))
                .andExpect(jsonPath("$.narrative.mode").value("TEMPLATE"));
        run(id, runKey, 2).andExpect(header().string("Idempotency-Replayed", "true"));
        verify(ai, times(1)).post(eq("/v1/decision-agent/compose"), any(), eq(plannerId));

        jdbc.update("UPDATE financial_value SET amount=200 WHERE line_id=?", costLine);
        mvc.perform(auth(get("/api/v1/decision-agent/analyses/" + id), planner))
                .andExpect(jsonPath("$.snapshot.baselineProfitWon").value("960"));
        save(id, key(), new CandidatesRequest(3, List.of())).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(4)).andExpect(jsonPath("$.result").isEmpty()).andExpect(jsonPath("$.narrative").isEmpty());
        assertThatThrownBy(() -> jdbc.update("UPDATE decision_agent_analysis SET snapshot=jsonb_set(snapshot,'{baselineProfitWon}','\"1\"') WHERE id=?", Long.parseLong(id)))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("UPDATE app_user SET role='ORG_MANAGER',organization_id=? WHERE id=?", organizationId, plannerId);
        create(planner, createKey).andExpect(status().isForbidden());
        save(id, candidateKey, candidates).andExpect(status().isForbidden());
        run(id, runKey, 2).andExpect(status().isForbidden());
    }

    /** 목적: 한 달 비용 누락을 매출의 월 존재로 숨길 수 없음을 검증한다. Args: 없음. Returns: 없음. Raises: 검증/HTTP 오류. Side Effects: 테스트 원천 삭제. */
    @Test
    void rejectsMissingCostMonthAndMissingMaster() throws Exception {
        jdbc.update("DELETE FROM financial_value WHERE line_id=? AND month=12", costLine);
        create(planner, key()).andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/v1/decision-agent/analyses"), planner).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new CreateRequest("목표", "검토", "9223372036854775807", "1050", List.of()))))
                .andExpect(status().isNotFound());
    }

    /** 목적: 종류별 월은 완전해도 추가 원천 행 하나의 누락을 차단한다. Args: 없음. Returns: 없음. Raises: 검증/HTTP 오류. Side Effects: 테스트 원천 추가/삭제. */
    @Test
    void rejectsOneIncompleteFinancialLineEvenWhenKindCoverageIsComplete() throws Exception {
        long extra = values(revenueItem, "1", 3);
        jdbc.update("DELETE FROM financial_value WHERE line_id=? AND month=12", extra);
        create(planner, key()).andExpect(status().isBadRequest());
    }

    /** 목적: 분석 생성 HTTP 요청을 보낸다. Args: token 사용자, requestKey 멱등 키. Returns: 결과 검사기. Raises: HTTP 오류. Side Effects: 테스트 DB 생성. */
    private ResultActions create(String token, String requestKey) throws Exception {
        return mvc.perform(auth(post("/api/v1/decision-agent/analyses"), token).header("Idempotency-Key", requestKey)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new CreateRequest("목표 검토", "연말 달성 방안", String.valueOf(masterId), "1050", List.of(Category.HEADCOUNT, Category.RND)))));
    }

    /** 목적: 후보 저장 HTTP 요청을 보낸다. Args: id 분석, requestKey 멱등 키, request 후보. Returns: 검사기. Raises: HTTP 오류. Side Effects: DB 수정. */
    private ResultActions save(String id, String requestKey, CandidatesRequest request) throws Exception {
        return mvc.perform(auth(put("/api/v1/decision-agent/analyses/" + id + "/candidates"), planner).header("Idempotency-Key", requestKey)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)));
    }

    /** 목적: 계산 실행 요청을 보낸다. Args: id 분석, requestKey 멱등 키, revision 예상 버전. Returns: 검사기. Raises: HTTP 오류. Side Effects: DB 수정. */
    private ResultActions run(String id, String requestKey, int revision) throws Exception {
        return mvc.perform(auth(post("/api/v1/decision-agent/analyses/" + id + "/runs"), planner).header("Idempotency-Key", requestKey)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new RunRequest(revision))));
    }

    /** 목적: 고유 직접 계정을 만든다. Args: kind 매출/비용. Returns: 계정 ID. Raises: DB 오류. Side Effects: 테스트 DB 생성. */
    private long item(String kind) {
        return jdbc.queryForObject("INSERT INTO report_item(code,name,kind,is_leaf) VALUES (?,'테스트 계정',?,true) RETURNING id", Long.class, "DA" + UUID.randomUUID(), kind);
    }

    /** 목적: 제출본 안에서 구분되는 원천 행에 12개월 실적/추정을 만든다. Args: item 계정, amount 월별 원 금액, sourceRow 손익 시트의 중복 없는 양수 행 번호. Returns: 행 ID. Raises: DB 오류와 중복 원천 행 제약 위반. Side Effects: 테스트 DB 생성. */
    private long values(long item, String amount, int sourceRow) {
        long line = jdbc.queryForObject("INSERT INTO financial_line(submission_id,report_item_id,source_sheet,source_row) VALUES (?,?,'손익',?) RETURNING id", Long.class, submissionId, item, sourceRow);
        for (int month = 1; month <= 12; month++) {
            jdbc.update("INSERT INTO financial_value(line_id,year,month,scenario,amount,source_record_id,source_cell) VALUES (?,?,?,?,?::numeric,?,'A1')",
                    line, year, month, month <= 11 ? "ACTUAL" : "ESTIMATE", amount, UUID.randomUUID().toString());
        }
        return line;
    }

    /** 목적: 인증 헤더를 추가한다. Args: request 요청, token 세션. Returns: 인증 요청. Raises: 없음. Side Effects: 요청 객체 수정. */
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    /** 목적: 응답 JSON을 파싱한다. Args: result HTTP 결과. Returns: JSON. Raises: 파싱 오류. Side Effects: 없음. */
    private JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** 목적: 요청마다 유일한 멱등 키를 제공한다. Args: 없음. Returns: 키. Raises: 없음. Side Effects: 난수 사용. */
    private String key() {
        return "decision-" + UUID.randomUUID();
    }
}
