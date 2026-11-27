package io.skala.backend.domain.financial.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Account;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Line;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Owner;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.ReportItem;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Source;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Value;
import io.skala.backend.domain.submission.repository.SubmissionRepository;
import io.skala.backend.domain.user.repository.OrganizationRepository;
import io.skala.backend.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 첫 제출 두 건이 같은 보고·계정 항목을 서로 다른 순서로 등록하는 실제 PostgreSQL 경합. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ConcurrentIngestionIntegrationTest extends PostgresIntegrationTest {

    @Autowired FinancialIngestionService ingestion;
    @Autowired SubmissionRepository submissions;
    @Autowired OrganizationRepository organizations;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @Test
    void 동시_첫_제출은_공유_항목을_중복없이_등록하고_각_조직의_금액과_원문을_보존한다() throws Exception {
        String suffix = UUID.randomUUID().toString();
        long planner = id("insert into app_user(email,name,role) values (?, '기획', 'PLANNER') returning id",
                suffix + "@audit.test");
        long plan = id("insert into annual_plan(year,status) values (2026,'CONFIRMED') "
                + "on conflict(year) do update set year=excluded.year returning id");
        long request = id("""
                insert into submission_request(annual_plan_id,type,month,status,requested_by,requested_at,period_key)
                values (?, 'MONTHLY_ACTUAL',11,'OPEN',?,now(),?) returning id
                """, plan, planner, "audit-" + suffix.substring(0, 20));
        long org1 = org("A" + suffix), org2 = org("B" + suffix);
        long sub1 = draft(request, org1, planner, "one-" + suffix);
        long sub2 = draft(request, org2, planner, "two-" + suffix);
        String root = "동시제출-" + suffix;
        List<String> pathA = List.of(root, "항목A"), pathB = List.of(root, "항목B");
        String codeA = ReportItemCodes.of("REVENUE", pathA), codeB = ReportItemCodes.of("REVENUE", pathB);
        List<ReportItem> reports = List.of(new ReportItem(codeA, "REVENUE", "항목A", pathA, "RESOLVED"),
                new ReportItem(codeB, "REVENUE", "항목B", pathB, "RESOLVED"));
        List<Line> lines = List.of(line(codeA, suffix + "A", 7), line(codeB, suffix + "B", 8));
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> ingest(sub1, org1, reports, lines, ready, start));
            var second = executor.submit(() -> ingest(sub2, org2, reports.reversed(), lines.reversed(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(2);
        }
        assertThat(jdbc.queryForObject("select count(*) from report_item where code in (?,?)", Integer.class,
                codeA, codeB)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from account_item where source_code in (?,?)", Integer.class,
                suffix + "A", suffix + "B")).isEqualTo(2);
        for (long sub : List.of(sub1, sub2)) {
            assertThat(jdbc.queryForList("select description from financial_line where submission_id=?",
                    String.class, sub)).containsOnly("  엑셀 원문\n거래내역 및 산출근거  ");
            assertThat(jdbc.queryForObject("""
                    select sum(v.amount)::text from financial_value v join financial_line l on l.id=v.line_id
                    where l.submission_id=?
                    """, String.class, sub)).isEqualTo("246.000000");
        }
    }

    private int ingest(long sub, long org, List<ReportItem> reports, List<Line> lines,
                       CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("동시 제출 시작 시간 초과");
        return tx.execute(status -> {
            var organization = organizations.findById(org).orElseThrow();
            var data = new ParserSubmissionData("financial-submission/1.0", "a".repeat(64),
                    new Owner(organization.getCode(), organization.getName(), "RESOLVED"), reports, lines, List.of());
            var result = ingestion.ingest(submissions.findById(sub).orElseThrow(), organization, 2026, data);
            assertThat(result.diagnostics()).isEmpty();
            return result.valueCount();
        });
    }

    private Line line(String report, String account, int row) {
        return new Line("line:" + row, "REVENUE", new Source("매출", row), new Account(account, "계정" + row),
                "detail:" + row, null, report, "RESOLVED", null, null, null, null, null,
                "  엑셀 원문\n거래내역 및 산출근거  ", null, null, null, null,
                List.of(new Value(2026, 11, "ACTUAL", "123", "AVAILABLE", "PRESENT", "N" + row, "rec:" + row)));
    }

    private long org(String code) {
        return id("insert into organization(code,name) values (?,?) returning id", code, code);
    }

    private long draft(long request, long org, long user, String key) {
        jdbc.update("insert into request_target(request_id,organization_id) values (?,?)", request, org);
        jdbc.update("insert into stored_object(object_key,content_type,size_bytes,sha256) values (?,'application/xlsx',1,?)",
                key, "a".repeat(64));
        return id("""
                insert into submission(request_id,organization_id,status,original_filename,source_object_key,
                  source_sha256,uploaded_by,uploaded_at) values (?,?,'DRAFT','audit.xlsx',?,?,?,now()) returning id
                """, request, org, key, "a".repeat(64), user);
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
