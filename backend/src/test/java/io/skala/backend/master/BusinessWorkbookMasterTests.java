package io.skala.backend.master;

import static io.skala.backend.submission.ApiData.hash;
import static io.skala.backend.submission.ApiData.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.parser.ParsingWorker;
import io.skala.backend.parser.PythonParserClient;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/** 명시한 기본 더미를 읽기 전용으로 실제 파서·HTTP·H2 마스터까지 연결하는 선택 실행 검사다. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:business_workbook_master;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
    "app.parser.worker-enabled=false"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles({"test", "local"})
@EnabledIfSystemProperty(named = "business.workbook.path", matches = ".+")
class BusinessWorkbookMasterTests {
    private static final String PLANNER = "workbook-planner@example.test";
    private static final String REVENUE_SHEET = "매출_세부_IFRS15後";
    private static final String COST_SHEET = "OpEx_세부_IFRS15後";
    private static final String SHEET_NAMESPACE = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String RELATIONSHIP_NAMESPACE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PythonParserClient parser;
    @Autowired TransactionTemplate transactions;

    /** 17개 원본의 모든 계획 셀을 독립 대조하고 마지막 승인으로 전체 마스터가 발행되는지 확인한다.
     * Args: business.workbook.path 시스템 속성의 기본 더미 폴더. Returns: 없음.
     * Raises: 원본·ZIP·XML·HTTP·DB·금액 대조 실패. Side Effects: 원본 읽기, Python -B 실행, 전용 H2 쓰기만 수행한다.
     */
    @Test
    void originalBusinessWorkbooksPublishAnExactAnnualMasterAfterEveryApproval() throws Exception {
        String configured = System.getProperty("business.workbook.path", "");
        assumeTrue(!configured.isBlank(), "business.workbook.path를 지정한 경우에만 원본을 읽습니다.");
        Path directory = Path.of(configured).toRealPath();
        JsonNode manifest = mapper.readTree(Files.readAllBytes(directory.resolve("manifest_baseline.json")));
        List<JsonNode> organizations = new ArrayList<>();
        Map<Path, String> digests = new LinkedHashMap<>();
        Path reference = null;
        for (JsonNode entry : manifest.path("files")) {
            Path path = sourcePath(directory, entry.path("filename").asText());
            digests.put(path, hash(Files.readAllBytes(path)));
            if (entry.path("is_master").asBoolean()) {
                assertThat(reference).isNull();
                reference = path;
            } else organizations.add(entry);
        }
        assertThat(organizations.size()).isEqualTo(17);
        assertThat(digests).hasSize(18);
        assertThat(reference).isNotNull();
        try {
            prepareOrganizations(organizations);
            JsonNode request = json(mvc.perform(post("/api/v1/submission-requests").with(asUser(PLANNER, "PLANNER"))
                    .header("Idempotency-Key", "business-annual-request-2026")
                    .contentType("application/json").content("{\"type\":\"ANNUAL_PLAN\",\"year\":2026}")), 201);
            String requestId = request.path("id").asText();
            assertThat(request.path("targetCount").asInt()).isEqualTo(17);
            List<String> submissions = new ArrayList<>();
            Map<String, BigDecimal> reportAmounts = new HashMap<>();
            Map<String, BigDecimal> monthlyAmounts = new HashMap<>();
            Map<String, Map<String, BigDecimal>> organizationAmounts = new LinkedHashMap<>();
            for (JsonNode organization : organizations) {
                String code = organization.path("org_code").asText();
                String email = managerEmail(code);
                Path path = sourcePath(directory, organization.path("filename").asText());
                byte[] bytes = Files.readAllBytes(path);
                JsonNode accepted = json(mvc.perform(multipart("/api/v1/submission-requests/" + requestId + "/submissions")
                        .file(new MockMultipartFile("file", code + "_2026-PLAN.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes))
                        .with(asUser(email, "ORG_MANAGER")).header("Idempotency-Key", "business-upload-" + code)), 202);
                String id = accepted.path("id").asText();
                new ParsingWorker(jdbc, transactions, parser, mapper, 90).runNext();
                JsonNode parsed = view(id, email, "ORG_MANAGER");
                assertThat(parsed.path("parsing").path("status").asText()).as("%s 파싱", code).isEqualTo("SUCCEEDED");
                JsonNode validated = command(id, "validation", email, "ORG_MANAGER", object("expectedStateToken", parsed.path("stateToken").asText()));
                JsonNode detail = validated.path("submission");
                assertThat(detail.path("readiness").path("canSubmit").asBoolean()).as("%s 최종 제출 준비", code).isTrue();
                var organizationMonths = new HashMap<String, BigDecimal>();
                compareSourceValues(id, numericCells(path), reportAmounts, monthlyAmounts, organizationMonths);
                organizationAmounts.put(code, organizationMonths);
                JsonNode submitted = command(id, "submit", email, "ORG_MANAGER", object("expectedStateToken", detail.path("stateToken").asText()));
                assertThat(submitted.path("status").asText()).isEqualTo("PENDING");
                submissions.add(id);
                assertThat(jdbc.sql("SELECT source_sha256 FROM submission WHERE id=?").param(Long.parseLong(id)).query(String.class).single())
                        .isEqualTo(digests.get(path));
            }
            assertThat(jdbc.sql("SELECT COUNT(*) FROM financial_line").query(Long.class).single()).isEqualTo(1003L);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM financial_value").query(Long.class).single()).isEqualTo(12036L);
            String masterId = approveEveryOrganization(submissions, requestId);
            JsonNode master = read("/api/v1/annual-masters/" + masterId);
            assertThat(master.path("sources").size()).isEqualTo(17);
            assertThat(master.path("sources").findValuesAsText("submissionId")).containsExactlyInAnyOrderElementsOf(submissions);
            assertThat(master.path("months").size()).isEqualTo(12);
            compareMasterReference(master, numericCells(reference), monthlyAmounts);
            compareOrganizationSummaries(master, organizationAmounts);
            compareReportValues(masterId, reportAmounts, monthlyAmounts);
            assertThat(jdbc.sql("SELECT status FROM annual_plan").query(String.class).single()).isEqualTo("COLLECTING");
            assertThat(jdbc.sql("SELECT contingency_amount FROM annual_plan").query().singleRow().get("contingency_amount")).isNull();
            JsonNode status = read("/api/v1/submission-requests/" + requestId + "/annual-master");
            assertThat(status.path("publicationState").asText()).isEqualTo("PUBLISHED");
            assertThat(status.path("approvedOrganizationCount").asInt()).isEqualTo(17);
            assertThat(status.path("missingOrganizations").isEmpty()).isTrue();
        } finally {
            for (var entry : digests.entrySet()) assertThat(hash(Files.readAllBytes(entry.getKey())))
                    .as("원본 파일 변경 없음: %s", entry.getKey().getFileName()).isEqualTo(entry.getValue());
        }
    }

    /** 전용 H2에 원본 manifest의 17개 조직과 기획·담당 계정만 준비한다.
     * Args: entries 기본 조직 목록. Returns: 없음. Raises: DB·manifest 중복 오류. Side Effects: H2 INSERT만 수행한다.
     */
    private void prepareOrganizations(List<JsonNode> entries) {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM organization").query(Long.class).single()).isZero();
        jdbc.sql("INSERT INTO app_user(id,email,name,role,active) VALUES (900,?,'원본 검증 기획','PLANNER',TRUE)").param(PLANNER).update();
        var codes = new HashSet<String>();
        for (int index = 0; index < entries.size(); index++) {
            JsonNode entry = entries.get(index);
            String code = entry.path("org_code").asText();
            assertThat(codes.add(code)).isTrue();
            assertThat(code).matches("F[0-9]+");
            jdbc.sql("INSERT INTO organization(id,code,name,active) VALUES (?,?,?,TRUE)")
                    .params(index + 1, code, entry.path("org_name").asText()).update();
            jdbc.sql("INSERT INTO app_user(id,email,name,role,organization_id,active) VALUES (?,?,?,'ORG_MANAGER',?,TRUE)")
                    .params(index + 1, managerEmail(code), entry.path("org_name").asText() + " 담당", index + 1).update();
        }
    }

    /** 검증 적재값을 파서와 독립적으로 읽은 원본 숫자·천원 환산과 전수 비교한다.
     * Args: submissionId 제출 ID, cells 원본 숫자, reports·months 전사 누적 기대값, organizationMonths 해당 조직 월별 기대값. Returns: 없음.
     * Raises: DB·원본 셀·금액 불일치. Side Effects: DB 읽기·메모리 합산만 수행한다.
     */
    private void compareSourceValues(String submissionId, Map<String, BigDecimal> cells,
            Map<String, BigDecimal> reports, Map<String, BigDecimal> months, Map<String, BigDecimal> organizationMonths) {
        var facts = jdbc.sql("""
                SELECT l.source_sheet,l.report_item_id,r.kind,v.source_cell,v.amount,v.year,v.month,v.scenario
                FROM financial_value v JOIN financial_line l ON l.id=v.line_id JOIN report_item r ON r.id=l.report_item_id
                WHERE l.submission_id=?
                """).param(Long.parseLong(submissionId)).query().listOfRows();
        assertThat(facts.size()).isEqualTo(708);
        for (var fact : facts) {
            String key = fact.get("source_sheet") + "!" + fact.get("source_cell");
            assertThat(cells.containsKey(key)).as("원본 셀 존재: %s", key).isTrue();
            BigDecimal amount = (BigDecimal) fact.get("amount");
            assertThat(amount).as("원본 셀 %s", key).isEqualByComparingTo(cells.get(key).multiply(BigDecimal.valueOf(1000)));
            assertThat(fact.get("year")).isEqualTo(2026);
            assertThat(fact.get("scenario")).isEqualTo("PLAN");
            reports.merge(fact.get("report_item_id") + ":" + fact.get("month"), amount, BigDecimal::add);
            months.merge(fact.get("kind") + ":" + fact.get("month"), amount, BigDecimal::add);
            organizationMonths.merge(fact.get("kind") + ":" + fact.get("month"), amount, BigDecimal::add);
        }
    }

    /** 앞선 16개 승인은 대기하고 마지막 승인만 마스터를 발행하는 HTTP 계약을 확인한다.
     * Args: submissions 조직별 제출 ID, requestId 연간 요청 ID. Returns: 발행 마스터 ID.
     * Raises: HTTP·발행 건수 검증 오류. Side Effects: 전용 H2 승인·자동 발행·멱등 재호출.
     */
    private String approveEveryOrganization(List<String> submissions, String requestId) throws Exception {
        String masterId = null;
        for (int index = 0; index < submissions.size(); index++) {
            String id = submissions.get(index);
            var payload = object("expectedStateToken", view(id, PLANNER, "PLANNER").path("stateToken").asText(), "action", "APPROVE");
            JsonNode result = command(id, "decisions", PLANNER, "PLANNER", payload);
            assertThat(result.path("submission").path("status").asText()).isEqualTo("APPROVED");
            assertThat(result.path("masterEffects").size()).isEqualTo(1);
            JsonNode effect = result.path("masterEffects").get(0);
            assertThat(effect.path("approvedOrganizationCount").asInt()).isEqualTo(index + 1);
            assertThat(effect.path("requiredOrganizationCount").asInt()).isEqualTo(17);
            assertThat(effect.path("action").asText()).isEqualTo(index < 16 ? "WAITING_INITIAL" : "CREATED");
            assertThat(jdbc.sql("SELECT COUNT(*) FROM annual_master").query(Long.class).single()).isEqualTo(index < 16 ? 0L : 1L);
            if (index == 15) {
                JsonNode waiting = read("/api/v1/submission-requests/" + requestId + "/annual-master");
                assertThat(waiting.path("missingOrganizations").size()).isEqualTo(1);
                assertThat(waiting.path("publicationState").asText()).isEqualTo("WAITING_INITIAL");
            }
            if (index == 16) {
                masterId = effect.path("masterId").asText();
                assertThat(command(id, "decisions", PLANNER, "PLANNER", payload)).isEqualTo(result);
                assertThat(jdbc.sql("SELECT COUNT(*) FROM annual_master").query(Long.class).single()).isEqualTo(1L);
            }
        }
        assertThat(masterId).isNotBlank();
        return masterId;
    }

    /** 별도 전체 마스터의 상세 595/408행을 합산해 발행된 36개 월 합계와 연간 합계를 대조한다.
     * Args: master 발행 상세, cells 기준 마스터 숫자, months 조직 원본의 누적값. Returns: 없음.
     * Raises: 상세 행 수·금액 검증 오류. Side Effects: 메모리 계산만 수행하며 상단 소계 수식 캐시는 쓰지 않는다.
     */
    private void compareMasterReference(JsonNode master, Map<String, BigDecimal> cells, Map<String, BigDecimal> months) {
        var revenue = referenceTotals(cells, REVENUE_SHEET, 23, 5, 595);
        var cost = referenceTotals(cells, COST_SHEET, 32, 6, 408);
        BigDecimal annualRevenue = BigDecimal.ZERO;
        BigDecimal annualCost = BigDecimal.ZERO;
        var seenMonths = new HashSet<Integer>();
        for (JsonNode item : master.path("months")) {
            int month = item.path("month").asInt();
            assertThat(month).isBetween(1, 12);
            assertThat(seenMonths.add(month)).as("월별 합계 중복 없음").isTrue();
            assertThat(months.get("REVENUE:" + month)).isEqualByComparingTo(revenue.get(month));
            assertThat(months.get("COST:" + month)).isEqualByComparingTo(cost.get(month));
            assertThat(new BigDecimal(item.path("revenue").asText())).isEqualByComparingTo(revenue.get(month));
            assertThat(new BigDecimal(item.path("cost").asText())).isEqualByComparingTo(cost.get(month));
            assertThat(new BigDecimal(item.path("operatingProfit").asText())).isEqualByComparingTo(revenue.get(month).subtract(cost.get(month)));
            annualRevenue = annualRevenue.add(revenue.get(month));
            annualCost = annualCost.add(cost.get(month));
        }
        assertThat(seenMonths.size()).isEqualTo(12);
        assertThat(new BigDecimal(master.path("totals").path("revenue").asText())).isEqualByComparingTo(annualRevenue);
        assertThat(new BigDecimal(master.path("totals").path("cost").asText())).isEqualByComparingTo(annualCost);
        assertThat(new BigDecimal(master.path("totals").path("operatingProfit").asText())).isEqualByComparingTo(annualRevenue.subtract(annualCost));
    }

    /** 조직별 지표가 17개 원본의 독립 기대값·고정 출처와 일치하고 전사 합계로 이어지는지 확인한다.
     * Args: master 고정 마스터 상세, organizationAmounts 원본 셀 대조를 마친 조직별 월 매출·비용.
     * Returns: 없음. Raises: 조직·출처·월·연간 합계 검증 오류. Side Effects: 메모리 비교만 수행한다.
     */
    private void compareOrganizationSummaries(JsonNode master, Map<String, Map<String, BigDecimal>> organizationAmounts) {
        var sources = new HashMap<String, JsonNode>(); var company = new HashMap<String, BigDecimal>(); var seen = new HashSet<String>();
        for (JsonNode source : master.path("sources")) sources.put(source.path("organizationCode").asText(), source);
        assertThat(master.path("organizations").size()).isEqualTo(17);
        for (JsonNode organization : master.path("organizations")) {
            String code = organization.path("organizationCode").asText(); assertThat(seen.add(code)).isTrue();
            JsonNode source = sources.get(code); assertThat(source).isNotNull();
            for (String field : List.of("organizationId", "organizationCode", "organizationName", "submissionId", "versionLabel")) {
                assertThat(organization.path(field)).as("%s 고정 출처 %s", code, field).isEqualTo(source.path(field));
            }
            var expected = organizationAmounts.get(code); assertThat(expected).isNotNull();
            BigDecimal annualRevenue = BigDecimal.ZERO, annualCost = BigDecimal.ZERO; var seenMonths = new HashSet<Integer>();
            assertThat(organization.path("months").size()).isEqualTo(12);
            for (JsonNode month : organization.path("months")) {
                int number = month.path("month").asInt(); assertThat(number).isBetween(1, 12); assertThat(seenMonths.add(number)).isTrue();
                BigDecimal revenue = expected.get("REVENUE:" + number), cost = expected.get("COST:" + number);
                assertThat(new BigDecimal(month.path("revenue").asText())).as("%s %s월 매출", code, number).isEqualByComparingTo(revenue);
                assertThat(new BigDecimal(month.path("cost").asText())).as("%s %s월 비용", code, number).isEqualByComparingTo(cost);
                assertThat(new BigDecimal(month.path("operatingProfit").asText())).as("%s %s월 손익", code, number).isEqualByComparingTo(revenue.subtract(cost));
                for (String field : List.of("revenue", "cost", "operatingProfit")) company.merge(number + ":" + field, new BigDecimal(month.path(field).asText()), BigDecimal::add);
                annualRevenue = annualRevenue.add(revenue); annualCost = annualCost.add(cost);
            }
            assertThat(new BigDecimal(organization.path("totals").path("revenue").asText())).isEqualByComparingTo(annualRevenue);
            assertThat(new BigDecimal(organization.path("totals").path("cost").asText())).isEqualByComparingTo(annualCost);
            assertThat(new BigDecimal(organization.path("totals").path("operatingProfit").asText())).isEqualByComparingTo(annualRevenue.subtract(annualCost));
            for (String field : List.of("revenue", "cost", "operatingProfit")) company.merge("annual:" + field, new BigDecimal(organization.path("totals").path(field).asText()), BigDecimal::add);
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(organizationAmounts.keySet());
        for (String field : List.of("revenue", "cost", "operatingProfit")) {
            assertThat(new BigDecimal(master.path("totals").path(field).asText())).isEqualByComparingTo(company.get("annual:" + field));
            for (JsonNode month : master.path("months")) assertThat(new BigDecimal(month.path(field).asText())).isEqualByComparingTo(company.get(month.path("month").asInt() + ":" + field));
        }
    }

    /** 보고값 모든 페이지를 상세별 적재 합계와 비교하고 중복·누락을 확인한다.
     * Args: masterId 발행 ID, reports·months 원본 대조를 마친 기대값. Returns: 없음.
     * Raises: HTTP·cursor·금액·건수 검증 오류. Side Effects: 조회 HTTP와 메모리 계산만 수행한다.
     */
    private void compareReportValues(String masterId, Map<String, BigDecimal> reports, Map<String, BigDecimal> months) throws Exception {
        String cursor = null;
        var seen = new HashSet<String>();
        do {
            var request = get("/api/v1/annual-masters/" + masterId + "/report-values").param("limit", "100").with(asUser(PLANNER, "PLANNER"));
            if (cursor != null) request.param("cursor", cursor);
            JsonNode page = json(mvc.perform(request), 200);
            for (JsonNode item : page.path("items")) {
                String key = item.path("reportItemId").asText() + ":" + item.path("month").asInt();
                assertThat(seen.add(key)).as("보고값 중복 없음").isTrue();
                assertThat(item.path("valueStatus").asText()).isEqualTo("AVAILABLE");
                assertThat(item.path("organizationId").isNull()).isTrue();
                BigDecimal expected = reports.getOrDefault(key, BigDecimal.ZERO);
                if (item.path("kind").asText().equals("DERIVED")) {
                    int month = item.path("month").asInt();
                    expected = switch (item.path("reportItemCode").asText()) {
                        case "REVENUE_TOTAL" -> months.get("REVENUE:" + month);
                        case "COST_TOTAL" -> months.get("COST:" + month);
                        case "OP" -> months.get("REVENUE:" + month).subtract(months.get("COST:" + month));
                        default -> throw new AssertionError("예상하지 않은 파생 보고 항목");
                    };
                }
                assertThat(new BigDecimal(item.path("amount").asText())).isEqualByComparingTo(expected);
            }
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null);
        assertThat(seen).hasSize(1836);
    }

    /** 기본 양식의 12개월 숫자가 모두 있는 상세행만 독립 합산한다.
     * Args: cells 원본 숫자, sheet 시트, startColumn 계획 첫 열, firstRow 상세 시작, count 기대 상세행 수.
     * Returns: 원 단위 월 합계. Raises: 행·셀 누락 검증 오류. Side Effects: 메모리 계산만 수행한다.
     */
    private Map<Integer, BigDecimal> referenceTotals(Map<String, BigDecimal> cells, String sheet, int startColumn, int firstRow, int count) {
        Map<Integer, BigDecimal> totals = new HashMap<>();
        var rows = new HashSet<Integer>();
        String prefix = sheet + "!" + column(startColumn);
        for (String address : cells.keySet()) if (address.startsWith(prefix)) {
            String suffix = address.substring(prefix.length());
            if (suffix.matches("[0-9]+") && Integer.parseInt(suffix) >= firstRow) rows.add(Integer.parseInt(suffix));
        }
        assertThat(rows).hasSize(count);
        for (int row : rows) for (int month = 1; month <= 12; month++) {
            String key = sheet + "!" + column(startColumn + month - 1) + row;
            assertThat(cells.containsKey(key)).as("기준 마스터 셀 존재: %s", key).isTrue();
            totals.merge(month, cells.get(key).multiply(BigDecimal.valueOf(1000)), BigDecimal::add);
        }
        return totals;
    }

    /** 양식 열 번호를 OOXML A1 주소의 영문 열로 바꾼다.
     * Args: number 양수 열 번호. Returns: 영문 열. Raises: 없음. Side Effects: 없음.
     */
    private String column(int number) {
        String result = "";
        while (number > 0) { number--; result = (char) ('A' + number % 26) + result; number /= 26; }
        return result;
    }

    /** 실제 원본 ZIP의 관계·시트 정의를 읽어 두 상세 시트의 숫자 캐시만 색인한다.
     * Args: path 읽기 전용 XLSX. Returns: 시트!셀별 십진수. Raises: ZIP·XML·숫자 오류.
     * Side Effects: 원본 스트림 읽기만 수행하며 외부 XML·DTD·임시 파일을 사용하지 않는다.
     */
    private Map<String, BigDecimal> numericCells(Path path) throws Exception {
        Map<String, BigDecimal> result = new HashMap<>();
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (var zip = new ZipFile(path.toFile())) {
            Map<String, String> relationships = new HashMap<>();
            try (InputStream input = zip.getInputStream(zip.getEntry("xl/_rels/workbook.xml.rels"))) {
                var xml = factory.createXMLStreamReader(input);
                while (xml.hasNext()) if (xml.next() == XMLStreamConstants.START_ELEMENT && xml.getLocalName().equals("Relationship"))
                    relationships.put(xml.getAttributeValue(null, "Id"), xml.getAttributeValue(null, "Target"));
                xml.close();
            }
            Map<String, String> sheets = new LinkedHashMap<>();
            try (InputStream input = zip.getInputStream(zip.getEntry("xl/workbook.xml"))) {
                var xml = factory.createXMLStreamReader(input);
                while (xml.hasNext()) if (xml.next() == XMLStreamConstants.START_ELEMENT && xml.getLocalName().equals("sheet")) {
                    String name = xml.getAttributeValue(null, "name");
                    if (List.of(REVENUE_SHEET, COST_SHEET).contains(name)) {
                        String target = relationships.get(xml.getAttributeValue(RELATIONSHIP_NAMESPACE, "id"));
                        sheets.put(name, target.startsWith("/") ? target.substring(1) : "xl/" + target);
                    }
                }
                xml.close();
            }
            assertThat(sheets).hasSize(2);
            for (var sheet : sheets.entrySet()) try (InputStream input = zip.getInputStream(zip.getEntry(sheet.getValue()))) {
                var xml = factory.createXMLStreamReader(input);
                String address = null;
                String type = null;
                while (xml.hasNext()) {
                    int event = xml.next();
                    if (event == XMLStreamConstants.START_ELEMENT && SHEET_NAMESPACE.equals(xml.getNamespaceURI())) {
                        if (xml.getLocalName().equals("c")) { address = xml.getAttributeValue(null, "r"); type = xml.getAttributeValue(null, "t"); }
                        else if (xml.getLocalName().equals("v") && address != null && (type == null || type.equals("n"))) {
                            String value = xml.getElementText();
                            if (!value.isBlank()) result.put(sheet.getKey() + "!" + address, new BigDecimal(value));
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && xml.getLocalName().equals("c")) address = null;
                }
                xml.close();
            }
        }
        return result;
    }

    /** manifest의 파일명을 지정 폴더 안의 실제 XLSX로 제한한다.
     * Args: directory 원본 폴더, filename manifest 파일명. Returns: 확인한 실제 경로.
     * Raises: 경로 이탈·원본 읽기 오류. Side Effects: 경로 메타데이터 읽기만 수행한다.
     */
    private Path sourcePath(Path directory, String filename) throws Exception {
        assertThat(Path.of(filename).getFileName().toString()).isEqualTo(filename);
        assertThat(filename).endsWith(".xlsx");
        Path path = directory.resolve(filename).toRealPath();
        assertThat(path.getParent()).isEqualTo(directory);
        return path;
    }

    /** 테스트 조직 코드에 해당하는 인증 이메일을 구성한다.
     * Args: code manifest 조직 코드. Returns: 테스트 전용 이메일. Raises: 없음. Side Effects: 없음.
     */
    private String managerEmail(String code) { return "workbook-" + code + "@example.test"; }

    /** String 이메일 principal을 넣고 실제 ActorResolver가 DB 소속·역할을 검사하게 한다.
     * Args: email 테스트 계정, role 역할. Returns: MockMvc 인증 후처리기. Raises: 없음.
     * Side Effects: 해당 테스트 HTTP의 SecurityContext만 설정하며 Bearer 인증 자체를 검사하는 대체물은 아니다.
     */
    private RequestPostProcessor asUser(String email, String role) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    /** 본문을 출력하지 않고 HTTP 결과를 JSON으로 읽는다.
     * Args: actions 수행한 HTTP, expected 기대 상태. Returns: JSON 응답. Raises: HTTP·JSON 오류.
     * Side Effects: 응답 메모리 읽기만 수행하며 multipart 원본·토큰을 출력하지 않는다.
     */
    private JsonNode json(ResultActions actions, int expected) throws Exception {
        var response = actions.andReturn().getResponse();
        assertThat(response.getStatus()).as("HTTP 상태").isEqualTo(expected);
        return mapper.readTree(response.getContentAsByteArray());
    }

    /** 지정 계정으로 제출 상세를 조회한다.
     * Args: id 제출 ID, email 계정, role 역할. Returns: 제출 JSON. Raises: HTTP·JSON 오류. Side Effects: 조회 HTTP.
     */
    private JsonNode view(String id, String email, String role) throws Exception {
        return json(mvc.perform(get("/api/v1/submissions/" + id).with(asUser(email, role))), 200);
    }

    /** 고정 멱등키로 검증·제출·판단 명령을 전송한다.
     * Args: id 제출 ID, action 명령, email 계정, role 역할, body JSON 본문. Returns: JSON 응답.
     * Raises: HTTP·JSON 오류. Side Effects: 전용 H2 명령 실행; 같은 명령 재호출은 같은 키를 쓴다.
     */
    private JsonNode command(String id, String action, String email, String role, Map<String, Object> body) throws Exception {
        return json(mvc.perform(post("/api/v1/submissions/" + id + "/" + action).with(asUser(email, role))
                .header("Idempotency-Key", "business-" + action + "-" + id)
                .contentType("application/json").content(mapper.writeValueAsBytes(body))), 200);
    }

    /** 기획 계정으로 마스터 조회 API를 읽는다.
     * Args: path API 경로. Returns: JSON 응답. Raises: HTTP·JSON 오류. Side Effects: 조회 HTTP만 수행한다.
     */
    private JsonNode read(String path) throws Exception { return json(mvc.perform(get(path).with(asUser(PLANNER, "PLANNER"))), 200); }
}
