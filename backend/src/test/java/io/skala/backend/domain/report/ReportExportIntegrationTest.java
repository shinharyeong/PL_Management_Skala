package io.skala.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.report.service.ReportAiClient;
import io.skala.backend.global.security.SessionTokenStore;
import io.skala.backend.support.PostgresIntegrationTest;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** RPT-16 XLSX 내보내기: 저장된 틀·기준 월로 report-ai에 다시 채워 원 단위 값 그대로 넣는다(report-ai는 가짜) */
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ReportExportIntegrationTest extends PostgresIntegrationTest {

    private static final String FILLED = """
            {"dataStatus": "PROVISIONAL", "blocks": [
              {"id": "b1", "kind": "title", "text": "월 실적 보고", "missing": false},
              {"id": "b2", "kind": "metric_table", "missing": true, "rows": [
                [{"text": "지표 (억원)", "hdr": true}, {"text": "11월", "hdr": true}, {"text": "계획 대비", "hdr": true}],
                [{"text": "영업이익"}, {"text": "12.3", "won": "1234567890"}, {"text": "데이터 없음", "won": null, "missing": true}]]},
              {"id": "b3", "kind": "table", "missing": false, "table": {"columns": ["org_name", "op_won"], "rows": [["사업부1", 500000000]]}}
            ]}""";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SessionTokenStore sessions;
    @Autowired
    ObjectMapper objectMapper;
    @MockitoBean
    ReportAiClient reportAi;

    String planner;
    String manager;
    long plannerId;
    long templateId;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        long org = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '내보내기조직') RETURNING id", Long.class, "EX" + s);
        plannerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role) VALUES (?, '기획', 'PLANNER') RETURNING id",
                Long.class, "ep" + s + "@sk.com");
        long managerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role, organization_id) VALUES (?, '담당', 'ORG_MANAGER', ?) RETURNING id",
                Long.class, "em" + s + "@sk.com", org);
        planner = sessions.issue(plannerId).token();
        manager = sessions.issue(managerId).token();
        templateId = jdbc.queryForObject("INSERT INTO report_template(name, created_by) VALUES ('월 보고', ?) RETURNING id", Long.class, plannerId);
        jdbc.update("INSERT INTO report_template_version(template_id, version, definition, created_by) VALUES (?, 1, ?::jsonb, ?)",
                templateId, """
                        {"schema_version": 1, "name": "월 보고", "blocks": [
                          {"id": "b1", "kind": "title", "text": "월 실적 보고"},
                          {"id": "b2", "kind": "metric_table", "label": "지표 표", "metrics": ["영업이익"], "compare": ["계획"]},
                          {"id": "b3", "kind": "table", "label": "조직별 영업이익", "sql": "SELECT org_name, op_won FROM v_op WHERE version = :version"}]}""",
                plannerId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportsXlsxWithWonValuesRefilledFromTemplate() throws Exception {
        when(reportAi.post(eq("/v1/data"), any(), eq(plannerId))).thenReturn(objectMapper.readTree(FILLED));
        byte[] file = mvc.perform(post("/api/v1/report/export").header("Authorization", "Bearer " + planner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\": \"" + templateId + "\", \"period\": {\"year\": 2026, \"month\": 11}, \"format\": \"XLSX\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("2026-11.xlsx")))
                .andReturn().getResponse().getContentAsByteArray();

        // 틀의 블록 전부를 기준 월로 다시 채워 달라고 했다(화면 숫자를 받지 않음)
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(reportAi).post(eq("/v1/data"), body.capture(), eq(plannerId));
        Map<String, Object> sent = (Map<String, Object>) body.getValue();
        assertThat(sent.get("base")).isEqualTo(Map.of("year", 2026, "month", 11));
        assertThat((List<JsonNode>) sent.get("blocks")).hasSize(3);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = wb.getSheet("보고서");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("월 보고").contains("2026년 11월").contains("(잠정)");
            Cell won = find(sheet, "영업이익", 1);
            assertThat(won.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(won.getNumericCellValue()).isEqualTo(1_234_567_890d);              // 화면은 12.3억, 파일은 원 단위 값
            assertThat(find(sheet, "영업이익", 2).getStringCellValue()).isEqualTo("데이터 없음");
            assertThat(find(sheet, "사업부1", 1).getNumericCellValue()).isEqualTo(500_000_000d);  // 확정 SQL 결과
            assertThat(wb.getSheet("정보").getRow(3).getCell(1).getStringCellValue()).isEqualTo("PROVISIONAL");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportsOneBlockAndRejectsOtherFormats() throws Exception {
        when(reportAi.post(eq("/v1/data"), any(), eq(plannerId))).thenReturn(objectMapper.readTree(FILLED));
        mvc.perform(post("/api/v1/report/export").header("Authorization", "Bearer " + planner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\": \"" + templateId + "\", \"version\": 1, \"period\": {\"year\": 2026, \"month\": 11}, \"blockId\": \"b2\", \"format\": \"XLSX\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(reportAi).post(eq("/v1/data"), body.capture(), eq(plannerId));
        assertThat((List<JsonNode>) ((Map<String, Object>) body.getValue()).get("blocks")).extracting(b -> b.get("id").asText())
                .containsExactly("b2");

        // PNG·PDF는 화면에서 만든다
        mvc.perform(post("/api/v1/report/export").header("Authorization", "Bearer " + planner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\": \"" + templateId + "\", \"period\": {\"year\": 2026, \"month\": 11}, \"format\": \"PDF\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/report/export").header("Authorization", "Bearer " + planner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\": \"" + templateId + "\", \"period\": {\"year\": 2026, \"month\": 11}, \"blockId\": \"zz\", \"format\": \"XLSX\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/report/export").header("Authorization", "Bearer " + planner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\": \"999999999\", \"period\": {\"year\": 2026, \"month\": 11}, \"format\": \"XLSX\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(post("/api/v1/report/export").header("Authorization", "Bearer " + manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\": \"" + templateId + "\", \"period\": {\"year\": 2026, \"month\": 11}, \"format\": \"XLSX\"}"))
                .andExpect(status().isForbidden());
    }

    /** 첫 칸이 label인 줄에서 column 칸 */
    private static Cell find(Sheet sheet, String label, int column) {
        for (Row row : sheet) {
            Cell first = row.getCell(0);
            if (first != null && first.getCellType() == CellType.STRING && first.getStringCellValue().equals(label)) {
                return row.getCell(column);
            }
        }
        throw new AssertionError("줄을 찾지 못함: " + label);
    }
}
