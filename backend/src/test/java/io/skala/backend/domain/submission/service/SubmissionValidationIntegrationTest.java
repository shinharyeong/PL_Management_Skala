package io.skala.backend.domain.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.skala.backend.domain.financial.dto.IngestionResult;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Account;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Line;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Owner;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.ReportItem;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Source;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData.Value;
import io.skala.backend.domain.submission.dto.ValidationResponse;
import io.skala.backend.domain.submission.parser.ParserBridgeResponse;
import io.skala.backend.domain.submission.repository.ParsingJobRepository;
import io.skala.backend.domain.submission.repository.SubmissionRepository;
import io.skala.backend.domain.submission.value.BaselineKind;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 PostgreSQL(V1 스키마)에서 파서 결과 적재 → 변경 감지 → 소명 대상 생성 흐름을 확인한다.
 * TEST_DB_URL이 있을 때만 실행(PostgresIntegrationTest 참고).
 * 빈 DB에서 실행해야 한다(Flyway V1부터 적용).
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class SubmissionValidationIntegrationTest extends PostgresIntegrationTest {

    private static final String SHA_OCT = "a".repeat(64);
    private static final String SHA_NOV = "b".repeat(64);
    private static final String SALES_KEY = "[\"REVENUE\",[\"매출\",\"제품매출\"]]";
    private static final String RENT_KEY = "[\"COST\",[\"판관비\",\"임차료\"]]";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ParsingResultService parsingResultService;
    @Autowired
    SubmissionValidationService validationService;
    @Autowired
    SubmissionRepository submissionRepository;
    @Autowired
    ParsingJobRepository parsingJobRepository;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    SubmissionSubmitService submitService;
    @Autowired
    SubmissionQueryService queryService;

    @Test
    void 전월_승인본과_비교해_바뀐_항목만_소명_대상으로_만들고_그대로인_항목은_소명을_이어받는다() {
        long orgId = insert("insert into organization(code, name) values ('ORG1', '테스트사업부') returning id");
        long otherOrgId = insert("insert into organization(code, name) values ('ORG2', '다른사업부') returning id");
        long planner = insert("insert into app_user(email, name, role) values ('p@x.io', '기획', 'PLANNER') returning id");
        long manager = insert("insert into app_user(email, name, role, organization_id) values ('o@x.io', '담당', 'ORG_MANAGER', "
                + orgId + ") returning id");
        long outsider = insert("insert into app_user(email, name, role, organization_id) values ('z@x.io', '남', 'ORG_MANAGER', "
                + otherOrgId + ") returning id");
        long plan = insert("insert into annual_plan(year, status) values (2026, 'CONFIRMED') "
                + "on conflict (year) do update set year = excluded.year returning id");
        long octRequest = request(plan, 10, planner);
        long novRequest = request(plan, 11, planner);
        jdbc.update("insert into request_target(request_id, organization_id) values (?, ?), (?, ?)",
                octRequest, orgId, novRequest, orgId);

        // 10월 제출본: 적재 후 승인 처리
        long octSubmission = draft(octRequest, orgId, manager, "drafts/oct.xlsx", SHA_OCT);
        long octJob = job(octSubmission);
        IngestionResult octResult = complete(octJob, data(SHA_OCT, List.of(
                line("1:10", "REVENUE", SALES_KEY, "매출", 10, "A-1",
                        value(10, "ACTUAL", "1000", "N10"), value(11, "ESTIMATE", "1100", "O10"),
                        priorYear(10, "800", "B10")))));
        assertThat(octResult.diagnostics()).isEmpty();
        assertThat(octResult.lineCount()).isEqualTo(1);
        assertThat(octResult.valueCount()).isEqualTo(3); // 전년 실적 열도 참고값으로 저장
        // 상세 행의 이름·담당자도 저장한다(#58). 본부 코드는 파서가 주지 않으므로 비워 둔다.
        assertThat(jdbc.queryForMap("""
                select headquarters_code, headquarters_name, team_name, task_name, person_in_charge
                from financial_line where submission_id = ?""", octSubmission))
                .containsEntry("headquarters_name", "본부10").containsEntry("team_name", "팀10")
                .containsEntry("task_name", "과제10").containsEntry("person_in_charge", "담당자10")
                .containsEntry("headquarters_code", null);

        AuthenticatedUser managerUser = new AuthenticatedUser(manager, UserRole.ORG_MANAGER, orgId);
        ValidationResponse octValidation = validationService.validate(octSubmission, token(octSubmission), managerUser);
        assertThat(octValidation.baseline().kind()).isEqualTo(BaselineKind.NONE);
        assertThat(octValidation.progress().required()).isZero();
        assertThat(octValidation.canSubmit()).isTrue();

        jdbc.update("update submission set status = 'APPROVED', seq = 1, approved_seq = 1 where id = ?", octSubmission);
        long octActualItem = jdbc.queryForObject("""
                select id from review_item where submission_id = ? and month = 10 and scenario = 'ACTUAL'
                """, Long.class, octSubmission);
        jdbc.update("""
                insert into review_explanation(review_item_id, round, answer, answered_by, answered_at, verdict)
                values (?, 1, '대형 수주 반영', ?, now(), 'ACCEPTED')
                """, octActualItem, manager);

        // 11월 제출본: 10월 실적은 그대로, 11월 추정은 바뀜, 임차료 새로 생김, 빈 칸은 저장하지 않음
        long novSubmission = draft(novRequest, orgId, manager, "drafts/nov.xlsx", SHA_NOV);
        long novJob = job(novSubmission);
        IngestionResult novResult = complete(novJob, data(SHA_NOV, List.of(
                line("1:10", "REVENUE", SALES_KEY, "매출", 10, "A-1",
                        value(10, "ACTUAL", "1000.000000", "N10"), value(11, "ESTIMATE", "1300", "O10"),
                        priorYear(10, "900", "B10")),
                line("2:5", "COST", RENT_KEY, "비용", 5, "C-9",
                        value(11, "ESTIMATE", "50", "O5"),
                        new Value(2026, 12, "ESTIMATE", null, "MISSING", "STORED_BLANK", "P5", "slot:x"))),
                List.of(new ReportItem(RENT_KEY, "COST", "임차료", List.of("판관비", "임차료"), "RESOLVED"))));
        assertThat(novResult.diagnostics()).isEmpty();
        assertThat(novResult.valueCount()).isEqualTo(4);

        assertThatThrownBy(() -> validationService.validate(novSubmission, token(novSubmission),
                new AuthenticatedUser(outsider, UserRole.ORG_MANAGER, otherOrgId)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("FORBIDDEN");
        assertThatThrownBy(() -> validationService.validate(novSubmission, "st_stale", managerUser))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("RESOURCE_CHANGED");

        ValidationResponse nov = validationService.validate(novSubmission, token(novSubmission), managerUser);
        assertThat(nov.baseline().kind()).isEqualTo(BaselineKind.PREV_MONTH_APPROVED);
        assertThat(nov.baseline().submissionId()).isEqualTo(String.valueOf(octSubmission));
        assertThat(nov.baseline().label()).isEqualTo("2026-10-01");
        // 11월 추정 매출, 11월 추정 임차료. 전년 실적은 금액이 바뀌었어도 검토 대상이 아니다(#59)
        assertThat(nov.progress().required()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from review_item where submission_id = ? and year <> 2026",
                Integer.class, novSubmission)).isZero();
        assertThat(nov.progress().answered()).isZero();
        assertThat(nov.canSubmit()).isFalse();

        Map<String, Object> inherited = jdbc.queryForMap("""
                select e.verdict, e.answer, e.inherited_from_id from review_explanation e
                join review_item i on i.id = e.review_item_id
                where i.submission_id = ? and i.month = 10 and i.scenario = 'ACTUAL'
                """, novSubmission);
        assertThat(inherited).containsEntry("verdict", "ACCEPTED").containsEntry("answer", "대형 수주 반영");
        assertThat(((Number) inherited.get("inherited_from_id")).longValue()).isPositive();

        // 다시 실행해도 같은 결과(이미 쓴 답은 유지)
        jdbc.update("""
                update review_explanation set answer = '단가 인상' where round = 1 and verdict = 'PENDING'
                  and review_item_id in (select id from review_item where submission_id = ? and report_item_id =
                    (select id from report_item where code = ?))
                """, novSubmission, SALES_KEY);
        ValidationResponse again = validationService.validate(novSubmission, token(novSubmission), managerUser);
        assertThat(again.progress().required()).isEqualTo(2);
        assertThat(again.progress().answered()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from review_item where submission_id = ?", Integer.class,
                novSubmission)).isEqualTo(3);

        // 보고 항목 계층: 말단과 상위 경로가 함께 만들어진다
        assertThat(jdbc.queryForObject("""
                select p.name from report_item c join report_item p on p.id = c.parent_id where c.code = ?
                """, String.class, RENT_KEY)).isEqualTo("판관비");
    }

    @Test
    void 조직이_다르거나_요청_연도_밖이면_수정_필요로_남고_소명_단계로_못_넘어간다() {
        long orgId = insert("insert into organization(code, name) values ('ORG9', '검증사업부') returning id");
        long planner = insert("insert into app_user(email, name, role) values ('p9@x.io', '기획', 'PLANNER') returning id");
        long manager = insert("insert into app_user(email, name, role, organization_id) values ('o9@x.io', '담당', 'ORG_MANAGER', "
                + orgId + ") returning id");
        long plan = insert("insert into annual_plan(year, status) values (2030, 'COLLECTING') "
                + "on conflict (year) do update set year = excluded.year returning id");
        long req = request(plan, 3, planner);
        jdbc.update("insert into request_target(request_id, organization_id) values (?, ?)", req, orgId);
        long submission = draft(req, orgId, manager, "drafts/bad.xlsx", SHA_OCT);
        long jobId = job(submission);

        ParserSubmissionData bad = new ParserSubmissionData("financial-submission/1.0", SHA_OCT,
                new Owner("OTHER", "다른조직", "RESOLVED"),
                List.of(new ReportItem(SALES_KEY, "REVENUE", "제품매출", List.of("매출", "제품매출"), "RESOLVED")),
                List.of(line("1:10", "REVENUE", SALES_KEY, "매출", 10, "A-1", value(3, "ACTUAL", "1", "N10"))),
                List.of());
        IngestionResult result = complete(jobId, bad);

        // 이전 연도(2026) 열만 있어 요청 연도(2030) 양식이 아니다
        assertThat(result.diagnostics()).extracting("rule")
                .containsExactlyInAnyOrder("ORGANIZATION_MISMATCH", "PERIOD_OUT_OF_RANGE");
        assertThat(parsingJobRepository.findById(jobId).orElseThrow().getErrorCode()).isEqualTo("VALIDATION_FAILED");
        assertThatThrownBy(() -> validationService.validate(submission, token(submission),
                new AuthenticatedUser(manager, UserRole.ORG_MANAGER, orgId)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 요청_연도_이후_금액_열은_기간_일치_오류다() {
        long orgId = insert("insert into organization(code, name) values ('ORG8', '미래사업부') returning id");
        long planner = insert("insert into app_user(email, name, role) values ('p8@x.io', '기획', 'PLANNER') returning id");
        long manager = insert("insert into app_user(email, name, role, organization_id) values ('o8@x.io', '담당', 'ORG_MANAGER', "
                + orgId + ") returning id");
        long plan = insert("insert into annual_plan(year, status) values (2026, 'CONFIRMED') "
                + "on conflict (year) do update set year = excluded.year returning id");
        long req = request(plan, 4, planner);
        jdbc.update("insert into request_target(request_id, organization_id) values (?, ?)", req, orgId);
        long submission = draft(req, orgId, manager, "drafts/future.xlsx", SHA_OCT);

        IngestionResult result = complete(job(submission), new ParserSubmissionData("financial-submission/1.0",
                SHA_OCT, new Owner("ORG8", "미래사업부", "RESOLVED"),
                List.of(new ReportItem(SALES_KEY, "REVENUE", "제품매출", List.of("매출", "제품매출"), "RESOLVED")),
                List.of(line("1:10", "REVENUE", SALES_KEY, "매출", 10, "A-1", value(4, "ACTUAL", "1", "N10"),
                        new Value(2027, 1, "PLAN", "2", "AVAILABLE", "PRESENT", "Z10", "rec:Z10"))),
                List.of()));

        assertThat(result.diagnostics()).extracting("rule").containsExactly("PERIOD_OUT_OF_RANGE");
        assertThat(result.diagnostics()).extracting("sourceCell").containsExactly("매출!Z10");
        assertThat(result.valueCount()).isEqualTo(1);
    }

    @Test
    void 요청_연도_금액이_모두_빈칸이면_소명_대상_없이_제출할_수_있다() {
        long orgId = insert("insert into organization(code, name) values ('ORG7', '빈칸사업부') returning id");
        long planner = insert("insert into app_user(email, name, role) values ('p7@x.io', '기획', 'PLANNER') returning id");
        long manager = insert("insert into app_user(email, name, role, organization_id) values ('o7@x.io', '담당', 'ORG_MANAGER', "
                + orgId + ") returning id");
        long plan = insert("insert into annual_plan(year, status) values (2026, 'CONFIRMED') "
                + "on conflict (year) do update set year = excluded.year returning id");
        long req = request(plan, 5, planner);
        jdbc.update("insert into request_target(request_id, organization_id) values (?, ?)", req, orgId);
        long submission = draft(req, orgId, manager, "drafts/blank.xlsx", SHA_OCT);
        storeFile("drafts/blank.xlsx"); // 제출하면 보존 위치로 복사하므로 실제 파일이 있어야 한다

        // 빈 양식: 요청 연도 칸은 모두 빈칸, 전년 실적만 있음
        IngestionResult result = complete(job(submission), new ParserSubmissionData("financial-submission/1.0",
                SHA_OCT, new Owner("ORG7", "빈칸사업부", "RESOLVED"),
                List.of(new ReportItem(SALES_KEY, "REVENUE", "제품매출", List.of("매출", "제품매출"), "RESOLVED")),
                List.of(line("1:10", "REVENUE", SALES_KEY, "매출", 10, "A-1", priorYear(5, "1", "B10"),
                        new Value(2026, 5, "ACTUAL", null, "MISSING", "STORED_BLANK", "N10", "slot:n10"))),
                List.of()));
        assertThat(result.diagnostics()).isEmpty();

        AuthenticatedUser user = new AuthenticatedUser(manager, UserRole.ORG_MANAGER, orgId);
        ValidationResponse validation = validationService.validate(submission, token(submission), user);
        assertThat(validation.progress().required()).isZero();
        assertThat(validation.canSubmit()).isTrue();
        assertThat(submitService.preview(submission, user).canSubmit()).isTrue();
        Boolean statusCanSubmit = tx.execute(st -> queryService.toResponse(
                submissionRepository.findById(submission).orElseThrow()).validation().canSubmit());
        assertThat(statusCanSubmit).isTrue(); // UP-04도 같은 기준

        submitService.submit(submission, user, null, token(submission));
        assertThat(jdbc.queryForObject("select status from submission where id = ?", String.class, submission))
                .isEqualTo("PENDING");
    }

    private static void storeFile(String key) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "skala-test-storage", key);
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.write(path, new byte[] {'P', 'K', 3, 4});
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String token(long submissionId) {
        return tx.execute(s -> SubmissionStateToken.of(submissionRepository.findById(submissionId).orElseThrow(),
                parsingJobRepository.findBySubmissionId(submissionId).orElse(null)));
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private long request(long plan, int month, long planner) {
        return jdbc.queryForObject("""
                insert into submission_request(annual_plan_id, type, month, status, requested_by, requested_at, period_key)
                values (?, 'MONTHLY_ACTUAL', ?, 'OPEN', ?, now(), ?) returning id
                """, Long.class, plan, month, planner, "M" + month + "-" + java.util.UUID.randomUUID().toString().substring(0, 12));
    }

    private long draft(long request, long org, long user, String key, String sha) {
        jdbc.update("insert into stored_object(object_key, content_type, size_bytes, sha256) values (?, 'application/xlsx', 1, ?)",
                key, sha);
        return jdbc.queryForObject("""
                insert into submission(request_id, organization_id, status, original_filename, source_object_key,
                  source_sha256, uploaded_by, uploaded_at)
                values (?, ?, 'DRAFT', 'f.xlsx', ?, ?, ?, now()) returning id
                """, Long.class, request, org, key, sha, user);
    }

    private long job(long submission) {
        return jdbc.queryForObject("""
                insert into parsing_job(submission_id, status, engine, attempt_no) values (?, 'RUNNING', 'stdlib-bridge', 1)
                returning id
                """, Long.class, submission);
    }

    private IngestionResult complete(long jobId, ParserSubmissionData data) {
        return parsingResultService.apply(jobId, 1, new ParserBridgeResponse("1.0", "test", "preview-records/1.0",
                "SUCCEEDED", null, List.of(), data)).orElseThrow();
    }

    private static ParserSubmissionData data(String sha, List<Line> lines) {
        return data(sha, lines, List.of());
    }

    private static ParserSubmissionData data(String sha, List<Line> lines, List<ReportItem> extra) {
        List<ReportItem> items = new java.util.ArrayList<>(List.of(
                new ReportItem(SALES_KEY, "REVENUE", "제품매출", List.of("매출", "제품매출"), "RESOLVED")));
        items.addAll(extra);
        return new ParserSubmissionData("financial-submission/1.0", sha, new Owner("ORG1", "테스트사업부", "RESOLVED"),
                items, lines, List.of());
    }

    private static Line line(String key, String domain, String reportKey, String sheet, int row, String detailId,
                             Value... values) {
        return new Line(key, domain, new Source(sheet, row), new Account("ACC-" + row, "계정" + row), detailId, null,
                reportKey, "RESOLVED", "관리분류", "실적기준", null, "T1", "K1", "거래내역",
                "담당자" + row, "팀" + row, "과제" + row, "본부" + row, List.of(values));
    }

    private static Value priorYear(int month, String amount, String cell) {
        return new Value(2025, month, "ACTUAL", amount, "AVAILABLE", "PRESENT", cell, "rec:" + cell);
    }

    private static Value value(int month, String scenario, String amount, String cell) {
        int year = 2026;
        return new Value(year, month, scenario, amount, "AVAILABLE", "PRESENT", cell, "rec:" + cell);
    }
}
