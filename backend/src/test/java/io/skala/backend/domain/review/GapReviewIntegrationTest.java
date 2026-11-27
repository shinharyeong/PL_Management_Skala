package io.skala.backend.domain.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.file.service.FileStorage;
import io.skala.backend.domain.file.value.FilePurpose;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Account;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Line;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Owner;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.ReportItem;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Source;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Value;
import io.skala.backend.domain.submission.parser.ParserBridgeResponse;
import io.skala.backend.domain.submission.service.ParsingResultService;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.support.PostgresIntegrationTest;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 확정 연간 계획(request_target.confirmed_plan_submission_id)을 기준으로 한 1월 변경 감지,
 * Gap 기준(GAP-03~05), 제출 때 Gap 계산, 계층형 표(GAP-06), 경영기획 요약(GAP-08).
 */
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = "app.parser.sweeper.enabled=false")
class GapReviewIntegrationTest extends PostgresIntegrationTest {

    private static final String SALES_KEY = "[\"REVENUE\",[\"매출\",\"제품매출\"]]";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    FileStorage fileStorage;
    @Autowired
    ParsingResultService parsingResultService;

    String orgCode;
    String orgName;
    long orgId;
    long managerId;
    long plannerId;
    int year;
    long plan;
    long planSubmission;
    RequestPostProcessor manager;
    RequestPostProcessor planner;

    @BeforeEach
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase().replaceAll("[^A-Z0-9]", "0");
        orgCode = "G" + suffix;
        orgName = "갭사업부" + suffix;
        orgId = insert("insert into organization(code, name) values (?, ?) returning id", orgCode, orgName);
        plannerId = insert("insert into app_user(email, name, role) values (?, '기획', 'PLANNER') returning id",
                "p" + suffix + "@x.io");
        managerId = insert("insert into app_user(email, name, role, organization_id) values (?, '담당', 'ORG_MANAGER', ?) returning id",
                "o" + suffix + "@x.io", orgId);
        manager = as(new AuthenticatedUser(managerId, UserRole.ORG_MANAGER, orgId));
        planner = as(new AuthenticatedUser(plannerId, UserRole.PLANNER, null));
        year = 2000 + Math.floorMod(suffix.hashCode(), 900) + 100;
        plan = insert("insert into annual_plan(year, status) values (?, 'CONFIRMED') "
                + "on conflict (year) do update set year = excluded.year returning id", year);
        // 확정 연간 계획: 1월 계획 1000, 11월 계획 1000
        long annual = insert("""
                insert into submission_request(annual_plan_id, type, month, status, requested_by, requested_at, period_key)
                values (?, 'ANNUAL_PLAN', null, 'OPEN', ?, now(), ?) returning id""", plan, plannerId, "A-" + suffix);
        jdbc.update("insert into request_target(request_id, organization_id) values (?, ?)", annual, orgId);
        planSubmission = draft(annual, "plan", List.of(line(10,
                value(1, "PLAN", "1000", "C10"), value(11, "PLAN", "1000", "M10"))));
        jdbc.update("update parsing_job set status = 'SUCCEEDED' where submission_id = ?", planSubmission);
        jdbc.update("update submission set status = 'APPROVED', seq = 1, approved_seq = 1 where id = ?", planSubmission);
    }

    @Test
    void 일월_시트는_확정_연간_계획과_같은_구분끼리만_비교한다() throws Exception {
        long jan = monthly(1);
        // 계획 구분: 1월 1000 → 1100(변경), 11월 1000 그대로. 실적·추정은 계획과 구분이 달라 비교하지 않음
        long draft = draft(jan, "jan", List.of(line(10,
                value(1, "PLAN", "1100", "C10"), value(11, "PLAN", "1000", "M10"),
                value(1, "ACTUAL", "1500", "D10"), value(2, "ESTIMATE", "900", "E10"))));
        validate(draft)
                .andExpect(jsonPath("$.baseline.kind").value("ANNUAL_PLAN"))
                .andExpect(jsonPath("$.baseline.submissionId").value(String.valueOf(planSubmission)))
                .andExpect(jsonPath("$.progress.required").value(1));
    }

    @Test
    void 기준을_적용하면_이후_제출에서_Gap을_계산하고_계층형_표와_요약에_보인다() throws Exception {
        // GAP-03: 아직 적용한 기준이 없으면 3종 모두 꺼진 기본값
        JsonNode current = json(mvc.perform(get("/api/v1/gap-thresholds/current").with(planner)).andExpect(status().isOk()));
        assertThat(current.get("items")).hasSize(4);
        mvc.perform(get("/api/v1/gap-thresholds/current").with(manager)).andExpect(status().isForbidden());

        // GAP-05: 둘 다 끄면 400, 낡은 토큰 409, 정상 적용 201
        Map<String, Object> input = Map.of("comparison", "ACTUAL_VS_PLAN", "percentEnabled", true, "percent", "5",
                "amountEnabled", true, "amount", "100");
        apply(Map.of("comparison", "ACTUAL_VS_PLAN", "percentEnabled", false, "percent", "5",
                "amountEnabled", false, "amount", "100"), current.get("stateToken").asText())
                .andExpect(status().isBadRequest());
        apply(input, "st_stale").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_CHANGED"));
        assertThat(json(mvc.perform(get("/api/v1/gap-thresholds/current").with(planner))).get("stateToken").asText())
                .as("데이터가 그대로면 토큰도 그대로").isEqualTo(current.get("stateToken").asText());
        apply(input, current.get("stateToken").asText()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.percent").value("5"))
                .andExpect(jsonPath("$.amount").value("100"));

        // 11월 시트: 실적 1300(계획 1000 대비 30%, 300원) → Gap
        long nov = monthly(11);
        long draft = draft(nov, "nov", List.of(line(10, value(11, "ACTUAL", "1300", "M10"))));
        validate(draft).andExpect(jsonPath("$.baseline.kind").value("NONE"));
        mvc.perform(get("/api/v1/review-items").with(manager)
                .param("view", "ORG").param("submissionId", String.valueOf(draft)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.progress.required").value(1))
                .andExpect(jsonPath("$.groups[0].months[0].items[0].current").value("1300"))
                .andExpect(jsonPath("$.groups[0].months[0].items[0].answerState").value("EMPTY"))
                .andExpect(jsonPath("$.groups[0].months[0].items[0].baseline").isEmpty());
        submit(draft).andExpect(status().isOk());
        Map<String, Object> gap = jdbc.queryForMap("""
                select g.basis, g.baseline_amount, g.current_amount, g.gap_amount, g.exceeded, i.gap_exceeded
                from gap_comparison g join review_item i on i.id = g.review_item_id where i.submission_id = ?""", draft);
        assertThat(gap).containsEntry("basis", "PLAN").containsEntry("exceeded", true).containsEntry("gap_exceeded", true);
        assertThat(new java.math.BigDecimal(gap.get("gap_amount").toString())).isEqualByComparingTo("300");

        // GAP-04: 같은 비교를 50%로 올리면 영향 건수 0
        mvc.perform(post("/api/v1/gap-thresholds/preview").with(planner).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("comparison", "ACTUAL_VS_PLAN",
                                "percentEnabled", true, "percent", "50", "amountEnabled", false, "amount", "0"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.affectedCount").value(0));

        // GAP-06: 매출 › 제품매출 › 11월 실적
        JsonNode tree = json(mvc.perform(get("/api/v1/review-items").param("view", "PLANNER")
                .param("requestId", String.valueOf(nov)).param("organizationId", String.valueOf(orgId))
                .param("comparison", "ACTUAL_VS_PLAN").with(planner)).andExpect(status().isOk()));
        assertThat(tree.at("/tabCounts/NEEDS_REVIEW").asInt()).isEqualTo(1);
        JsonNode top = tree.get("tree").get(0);
        assertThat(top.get("name").asText()).isEqualTo("매출");
        assertThat(top.get("level").asInt()).isEqualTo(1);
        assertThat(top.get("diff").asText()).isEqualTo("300");
        JsonNode leaf = top.get("children").get(0).get("children").get(0);
        assertThat(leaf.get("name").asText()).isEqualTo("11월 실적");
        assertThat(leaf.get("level").asInt()).isEqualTo(3);
        assertThat(leaf.get("plan").asText()).isEqualTo("1000");
        assertThat(leaf.get("actual").asText()).isEqualTo("1300");
        assertThat(leaf.get("rate").asText()).isEqualTo("30.0");
        assertThat(leaf.get("stage").asInt()).isEqualTo(1);
        assertThat(leaf.get("gapExceeded").asBoolean()).isTrue();
        mvc.perform(get("/api/v1/review-items").param("view", "PLANNER").param("requestId", String.valueOf(nov))
                .param("organizationId", String.valueOf(orgId)).with(manager)).andExpect(status().isForbidden());

        // GAP-08: 경영기획팀에게만 계획 대비 요약과 제출 때 기준
        mvc.perform(get("/api/v1/review-items/{id}", leaf.get("reviewItemId").asText()).with(planner))
                .andExpect(jsonPath("$.summary.plan").value("1000"))
                .andExpect(jsonPath("$.summary.diff").value("300"))
                .andExpect(jsonPath("$.summary.threshold.percent").value("5"));
        mvc.perform(get("/api/v1/review-items/{id}", leaf.get("reviewItemId").asText()).with(manager))
                .andExpect(jsonPath("$.summary").doesNotExist());
    }

    @Test
    void 선택_처리와_원천_셀_버전_비교() throws Exception {
        JsonNode current = json(mvc.perform(get("/api/v1/gap-thresholds/current").with(planner)));
        apply(Map.of("comparison", "ACTUAL_VS_PLAN", "percentEnabled", true, "percent", "5",
                "amountEnabled", false, "amount", "0"), current.get("stateToken").asText()).andExpect(status().isCreated());
        long nov = monthly(11);
        // 같은 항목에 원천 행 두 개(11행 900, 10행 400)
        long first = draft(nov, "nov", List.of(line(11, value(11, "ACTUAL", "900", "M11")),
                line(10, value(11, "ACTUAL", "400", "M10"))));
        validate(first);
        submit(first).andExpect(status().isOk());
        JsonNode leaf = leaf(nov);
        String itemId = leaf.get("reviewItemId").asText();

        // GAP-09 원천 셀: 셀 오름차순(10행 → 11행), 계정·거래내역·금액
        mvc.perform(get("/api/v1/review-items/{id}/evidence", itemId).with(planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].sourceCell").value("M10"))
                .andExpect(jsonPath("$.items[0].amount").value("400"))
                .andExpect(jsonPath("$.items[0].account.code").value("ACC10"))
                .andExpect(jsonPath("$.items[1].sourceCell").value("M11"));

        // GAP-07: 소명 요청은 기한·메시지 필수 → 선정 → 다시 선정 409 → 소명 요청(IN_PROGRESS) → 제외 409
        act("REQUEST_EXPLANATION", itemId, leaf.get("stateToken").asText(), Map.of())
                .andExpect(status().isBadRequest());
        JsonNode selected = json(act("SELECT", itemId, leaf.get("stateToken").asText(), Map.of())
                .andExpect(status().isOk()));
        assertThat(selected.at("/items/0/tab").asText()).isEqualTo("SELECTED");
        assertThat(selected.at("/tabCounts/SELECTED").asInt()).isEqualTo(1);
        String token = selected.at("/items/0/stateToken").asText();
        act("SELECT", itemId, token, Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_STAGE_CONFLICT"));
        JsonNode requested = json(act("REQUEST_EXPLANATION", itemId, token,
                Map.of("dueAt", "2099-01-01T00:00:00Z", "message", "차이 원인을 적어 주세요.")).andExpect(status().isOk()));
        assertThat(requested.at("/items/0/tab").asText()).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("""
                select request_note from review_explanation where review_item_id = ? order by round desc limit 1""", String.class,
                Long.parseLong(itemId))).isEqualTo("차이 원인을 적어 주세요.");
        act("EXCLUDE", itemId, requested.at("/items/0/stateToken").asText(), Map.of("note", "x"))
                .andExpect(status().isConflict());

        // 업로드 소명 이후의 경영기획 요청에도 회차가 아닌 요청 여부로 회신을 허용한다.
        mvc.perform(put("/api/v1/review-items/{id}/answer", itemId).with(manager)
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("answer", "요청받은 첫 회차 소명 회신", "expectedStateToken", requested.at("/items/0/stateToken").asText()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answerState").value("WRITTEN"));
        assertThat(jdbc.queryForObject("select count(*) from notification where submission_id = ? and type = 'REPLY'", Integer.class, first)).isPositive();

        mvc.perform(get("/api/v1/review-items").with(manager)
                .param("view", "ORG").param("submissionId", String.valueOf(first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress.required").value(1))
                .andExpect(jsonPath("$.progress.answered").value(1));

        // 첫 제출본을 승인 버전(ver11.01)으로 두고, 값을 바꾼 새 초안을 올린다
        jdbc.update("update submission set status = 'APPROVED', approved_seq = 1, reviewed_by = ?, reviewed_at = now() where id = ?",
                plannerId, first);
        long second = draft(nov, "nov2", List.of(line(11, value(11, "ACTUAL", "950", "M11")),
                line(10, value(11, "ACTUAL", "400", "M10"))));
        validate(second);
        String secondItem = String.valueOf(jdbc.queryForObject(
                "select id from review_item where submission_id = ? and month = 11", Long.class, second));

        // GAP-10: 현재 승인본(ver11.01) vs 새 업로드(승인 시 ver11.02)
        mvc.perform(get("/api/v1/review-items/{id}/versions", secondItem).with(planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].label").value("ver11.01"))
                .andExpect(jsonPath("$.columns[0].state").value("CURRENT"))
                .andExpect(jsonPath("$.columns[1].state").value("NEW"))
                .andExpect(jsonPath("$.columns[1].nextLabelOnApproval").value("ver11.02"))
                .andExpect(jsonPath("$.rows[0].cell").value("매출!M10"))
                .andExpect(jsonPath("$.rows[0].changed").value(false))
                .andExpect(jsonPath("$.rows[1].values[0]").value("900"))
                .andExpect(jsonPath("$.rows[1].values[1]").value("950"))
                .andExpect(jsonPath("$.rows[1].change").value("50"))
                .andExpect(jsonPath("$.rows[1].changed").value(true));
        mvc.perform(get("/api/v1/review-items/{id}/versions", secondItem).with(manager)).andExpect(status().isForbidden());

        // GAP-01·02: 기본 기준은 최신 승인본
        mvc.perform(get("/api/v1/submissions/{id}/comparison", second).with(planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base.id").value(String.valueOf(first)))
                .andExpect(jsonPath("$.rows[0].name").value("매출"))
                .andExpect(jsonPath("$.rows[0].diff").value("50"));
        mvc.perform(get("/api/v1/submissions/{id}/comparison", second).param("baseSubmissionId", String.valueOf(second))
                .with(planner)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_COMPARISON_SELECTION"));
        byte[] xlsx = mvc.perform(get("/api/v1/submissions/{id}/comparison/file", second).with(planner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            var row = workbook.getSheet("Gap").getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("매출 › 제품매출");
            assertThat(row.getCell(1).getStringCellValue()).isEqualTo("11월 실적");
            assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(50.0);
        }
    }

    @Test
    void 계산기는_전월승인본의_동일월추정을_보여주며_당월승인으로_기준을_바꾸지_않는다() throws Exception {
        long oct = monthly(10);
        long previous = draft(oct, "oct", List.of(line(10, value(11, "ESTIMATE", "1100", "M10"))));
        validate(previous);
        jdbc.update("update submission set status = 'APPROVED', seq = 1, approved_seq = 1 where id = ?", previous);
        long nov = monthly(11);
        long current = draft(nov, "nov", List.of(line(10, value(11, "ACTUAL", "1200", "M10"),
                value(12, "ESTIMATE", "0", "N10"))));
        validate(current);
        jdbc.update("update submission set status = 'PENDING', seq = 1 where id = ?", current);
        JsonNode actual = leaf(nov);
        assertThat(actual.get("plan").asText()).isEqualTo("1000");
        assertThat(actual.get("actual").asText()).isEqualTo("1200");
        assertThat(actual.get("previousEstimate").asText()).isEqualTo("1100");
        assertThat(actual.get("scenario").asText()).isEqualTo("ACTUAL");
        assertThat(actual.get("month").asInt()).isEqualTo(11);
        jdbc.update("update submission set status = 'APPROVED', approved_seq = 1 where id = ?", current);
        assertThat(leaf(nov).get("previousEstimate").asText()).isEqualTo("1100");
        JsonNode estimates = json(mvc.perform(get("/api/v1/review-items").with(planner)
                .param("view", "PLANNER").param("requestId", String.valueOf(nov))
                .param("organizationId", String.valueOf(orgId)).param("tab", "ALL")
                .param("comparison", "ESTIMATE_VS_PLAN")).andExpect(status().isOk()));
        JsonNode estimate = java.util.stream.StreamSupport.stream(
                estimates.at("/tree/0/children/0/children").spliterator(), false)
                .filter(node -> node.get("month").asInt() == 12).findFirst().orElseThrow();
        assertThat(estimate.get("actual").asText()).isEqualTo("0");
        assertThat(estimate.get("scenario").asText()).isEqualTo("ESTIMATE");
        assertThat(estimate.get("previousEstimate").isNull()).isTrue();
    }

    @Test
    void 첫제출은_전월추정과계획_재제출은_당월직전제출을_비교하고_과거실적을_제외한다() throws Exception {
        for (String comparison : List.of("ACTUAL_VS_PLAN","ACTUAL_VS_PREVIOUS","ESTIMATE_VS_PREVIOUS")) {
            JsonNode settings=json(mvc.perform(get("/api/v1/gap-thresholds/current").with(planner)));
            apply(Map.of("comparison",comparison,"percentEnabled",true,"percent","5","amountEnabled",false,"amount","0"),settings.get("stateToken").asText()).andExpect(status().isCreated());
        }
        long oct=monthly(10);
        long old=draft(oct,"oct-policy",List.of(line(10,value(11,"ESTIMATE","1100","M10"),value(12,"ESTIMATE","900","N10"))));
        validate(old);
        jdbc.update("update submission set status='APPROVED',seq=1,approved_seq=1 where id=?",old);
        long nov=monthly(11);
        long first=draft(nov,"nov-first-policy",List.of(line(10,value(9,"ACTUAL","777","K10"),
                value(11,"ACTUAL","1200","M10"),value(12,"ESTIMATE","1000","N10"),value(11,"PLAN","9999","O10"))));
        validate(first).andExpect(jsonPath("$.progress.required").value(2));
        assertThat(jdbc.queryForObject("select count(*) from review_item where submission_id=? and active",Integer.class,first)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from financial_value v join financial_line l on l.id=v.line_id where l.submission_id=?",Integer.class,first)).isEqualTo(4);
        submit(first).andExpect(status().isOk());
        JsonNode initial=leaf(nov);
        assertThat(initial.get("comparisons")).hasSize(2);
        assertThat(initial.at("/comparisons/0/baselineAmount").asText()).isEqualTo("1000");
        assertThat(initial.at("/comparisons/1/baselineAmount").asText()).isEqualTo("1100");
        assertThat(initial.at("/comparisons/1/baselineScenario").asText()).isEqualTo("ESTIMATE");
        assertThat(jdbc.queryForObject("select count(*) from gap_comparison g join review_item i on i.id=g.review_item_id where i.submission_id=?",Integer.class,first)).isEqualTo(3);
        jdbc.update("update submission set status='REJECTED',reject_reason='수정 필요' where id=?",first);
        long second=draft(nov,"nov-second-policy",List.of(line(10,value(11,"ACTUAL","1250","M10"),value(12,"ESTIMATE","950","N10"))));
        validate(second).andExpect(jsonPath("$.progress.required").value(2));
        submit(second).andExpect(status().isOk());
        JsonNode revised=leaf(nov);
        assertThat(revised.get("comparisons")).hasSize(1);
        assertThat(revised.at("/comparisons/0/baselineSubmissionId").asText()).isEqualTo(String.valueOf(first));
        assertThat(revised.at("/comparisons/0/baselineScenario").asText()).isEqualTo("ACTUAL");
        assertThat(revised.at("/comparisons/0/diff").asText()).isEqualTo("50");
        assertThat(jdbc.queryForObject("select baseline_amount from gap_comparison g join review_item i on i.id=g.review_item_id where i.submission_id=? and i.scenario='ESTIMATE'",java.math.BigDecimal.class,second)).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("select count(*) from gap_comparison g join review_item i on i.id=g.review_item_id where i.submission_id=? and g.basis='PLAN'",Integer.class,second)).isZero();
        jdbc.update("update submission set status='APPROVED',approved_seq=1 where id=?",second);
        assertThat(leaf(nov).at("/comparisons/0/baselineSubmissionId").asText()).isEqualTo(String.valueOf(first));
        long unchanged=draft(nov,"nov-third-policy",List.of(line(10,value(11,"ACTUAL","1250","M10"),value(12,"ESTIMATE","950","N10"))));
        validate(unchanged).andExpect(jsonPath("$.progress.required").value(0));
    }

    @Test
    void 소명없이_직접승인하면_승인탭에_합산되고_제출본승인과는_구분된다() throws Exception {
        long nov=monthly(11);
        long submission=draft(nov,"direct-approval",List.of(line(10,value(11,"ACTUAL","1300","M10"))));
        validate(submission);
        submit(submission).andExpect(status().isOk());
        JsonNode row=leaf(nov);
        JsonNode result=json(act("APPROVE",row.get("reviewItemId").asText(),row.get("stateToken").asText(),Map.of())
                .andExpect(status().isOk()));
        assertThat(result.at("/items/0/tab").asText()).isEqualTo("ACCEPTED");
        assertThat(result.at("/tabCounts/EXCLUDED").asInt()).isZero();
        assertThat(jdbc.queryForObject("select workflow_state from review_item where id=?",String.class,row.get("reviewItemId").asLong())).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select action from review_workflow_event where review_item_id=? order by id desc limit 1",String.class,row.get("reviewItemId").asLong())).isEqualTo("APPROVE");
        assertThat(jdbc.queryForObject("select status from submission where id=?",String.class,submission)).isEqualTo("PENDING");
        act("SELECT",row.get("reviewItemId").asText(),result.at("/items/0/stateToken").asText(),Map.of())
                .andExpect(status().isConflict());
    }

    private JsonNode leaf(long requestId) throws Exception {
        JsonNode tree = json(mvc.perform(get("/api/v1/review-items").param("view", "PLANNER")
                .param("requestId", String.valueOf(requestId)).param("organizationId", String.valueOf(orgId))
                .param("tab", "ALL").with(planner)).andExpect(status().isOk()));
        return tree.get("tree").get(0).get("children").get(0).get("children").get(0);
    }

    private ResultActions act(String action, String itemId, String token, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("action", action);
        body.put("items", List.of(Map.of("reviewItemId", itemId, "expectedStateToken", token)));
        return mvc.perform(post("/api/v1/review-items/actions").with(planner).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private long monthly(int month) {
        long id = insert("""
                insert into submission_request(annual_plan_id, type, month, status, requested_by, requested_at, period_key)
                values (?, 'MONTHLY_ACTUAL', ?, 'OPEN', ?, now(), ?) returning id""",
                plan, month, plannerId, "M" + month + "-" + UUID.randomUUID().toString().substring(0, 8));
        // 월간 요청을 보낼 때 B가 확정 연간 계획을 고정해 두는 칸
        jdbc.update("insert into request_target(request_id, organization_id, confirmed_plan_submission_id) values (?, ?, ?)",
                id, orgId, planSubmission);
        return id;
    }

    private ResultActions apply(Map<String, Object> input, String token) throws Exception {
        Map<String, Object> body = new HashMap<>(input);
        body.put("expectedStateToken", token);
        return mvc.perform(post("/api/v1/gap-thresholds").with(planner).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions validate(long submissionId) throws Exception {
        return mvc.perform(post("/api/v1/submissions/{id}/validation", submissionId).with(manager)
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStateToken\":\"" + token(submissionId) + "\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions submit(long submissionId) throws Exception {
        for (Long itemId : jdbc.queryForList("select i.id from review_item i join review_explanation e on e.review_item_id=i.id where i.submission_id=? and i.active and e.verdict='PENDING' and e.answer is null", Long.class, submissionId)) {
            JsonNode item=json(mvc.perform(get("/api/v1/review-items/{id}",itemId).with(manager)).andExpect(status().isOk()));
            mvc.perform(put("/api/v1/review-items/{id}/answer",itemId).with(manager).header("Idempotency-Key",key())
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("answer","업로드 변경 사유", "expectedStateToken",item.get("stateToken").asText())))).andExpect(status().isOk());
        }
        return mvc.perform(post("/api/v1/submissions/{id}/submit", submissionId).with(manager)
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("expectedStateToken", token(submissionId)))));
    }

    private String token(long submissionId) throws Exception {
        return json(mvc.perform(get("/api/v1/submissions/{id}", submissionId).with(planner))).get("stateToken").asText();
    }

    private long draft(long requestId, String tag, List<Line> lines) {
        StoredObject stored = fileStorage.save(FilePurpose.SUBMISSION_EXCEL, orgCode + "_" + tag + ".xlsx",
                "application/octet-stream", new ByteArrayInputStream(("PK\u0003\u0004" + tag + UUID.randomUUID()).getBytes()));
        long submissionId = insert("""
                insert into submission(request_id, organization_id, status, original_filename, source_object_key,
                  source_sha256, uploaded_by, uploaded_at)
                values (?, ?, 'DRAFT', ?, ?, ?, ?, now()) returning id""",
                requestId, orgId, orgCode + "_" + tag + ".xlsx", stored.getObjectKey(), stored.getSha256(), managerId);
        long job = insert("""
                insert into parsing_job(submission_id, status, engine, attempt_no) values (?, 'RUNNING', 'test', 1)
                returning id""", submissionId);
        ParserSubmissionData data = new ParserSubmissionData("financial-submission/1.0", stored.getSha256(),
                new Owner(orgCode, orgName, "RESOLVED"),
                List.of(new ReportItem(SALES_KEY, "REVENUE", "제품매출", List.of("매출", "제품매출"), "RESOLVED")),
                lines, List.of());
        assertThat(parsingResultService.apply(job, 1, new ParserBridgeResponse("1.0", "test", "preview-records/1.0",
                "SUCCEEDED", null, List.of(), data)).orElseThrow().passed()).isTrue();
        return submissionId;
    }

    private static Line line(int row, Value... values) {
        return new Line("k" + row, "REVENUE", new Source("매출", row), new Account("ACC" + row, "계정" + row),
                "D-" + row, null, SALES_KEY, "RESOLVED", null, null, null, null, null, null,
                null, null, null, null, List.of(values));
    }

    private Value value(int month, String scenario, String amount, String cell) {
        return new Value(year, month, scenario, amount, "AVAILABLE", "PRESENT", cell, "rec:" + cell);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private long insert(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static String key() {
        return "it-" + UUID.randomUUID();
    }

    private static RequestPostProcessor as(AuthenticatedUser user) {
        return authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
