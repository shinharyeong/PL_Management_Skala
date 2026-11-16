package io.skala.backend.demo;

import io.skala.backend.submission.SubmissionService;
import java.util.Objects;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 명시한 local+demo에서만 합성 조직·사용자·연간 수집 요청을 처음 한 번 준비한다. */
@Component
@Profile("local & demo")
public class DemoBootstrap implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final DemoAccounts accounts;
    private final SubmissionService submissions;

    /** 부트스트랩 의존성을 받는다.
     * Args: jdbc DB, transactions 트랜잭션, accounts 개발 계정, submissions 업무 서비스.
     * Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public DemoBootstrap(JdbcClient jdbc, TransactionTemplate transactions, DemoAccounts accounts, SubmissionService submissions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.accounts = accounts;
        this.submissions = submissions;
    }

    /** 기존 자료·요청 대상을 변경하지 않고 기본 체험과 17개 사업부 계정을 준비한다.
     * Args: arguments 시작 인자이며 사용하지 않음. Returns: 없음.
     * Raises: IllegalStateException 기존 자료 충돌, 업무/DB 오류. Side Effects: 없던 DB 행만 생성.
     */
    @Override
    public void run(ApplicationArguments arguments) {
        transactions.executeWithoutResult(transaction -> {
            long organization = organization("D100", "네트워크인프라부문");
            long other = organization("D200", "미디어사업부");
            user(accounts.email("PLANNER"), "PLANNER", "경영기획 담당자 (데모)", null);
            user(accounts.email("ORG_MANAGER"), "ORG_MANAGER", "조직 담당자 (데모)", organization);
            var planner = accounts.actor("PLANNER");
            var existing = jdbc.sql("SELECT id,type,requested_by FROM submission_request WHERE period_key='2027-PLAN'")
                    .query().listOfRows();
            if (existing.isEmpty()) {
                submissions.createRequest(planner, "demo-bootstrap-plan-2027-v1",
                        new SubmissionService.RequestInput("ANNUAL_PLAN", 2027, null,
                                "체험용 2027 연간 계획입니다. 합성 양식을 내려받아 파싱을 확인하세요.", null));
            } else {
                var request = existing.getFirst();
                long targets = jdbc.sql("SELECT COUNT(*) FROM request_target WHERE request_id=? AND organization_id IN (?,?)")
                        .params(request.get("id"), organization, other).query(Long.class).single();
                if (!"ANNUAL_PLAN".equals(request.get("type"))
                        || !planner.id().equals(request.get("requested_by").toString()) || targets != 2) {
                    throw new IllegalStateException("기존 2027 요청을 데모 요청으로 변경할 수 없습니다.");
                }
            }
            for (int index = 0; index < DemoAccounts.ORGANIZATION_CODES.size(); index++) {
                String code = DemoAccounts.ORGANIZATION_CODES.get(index);
                String name = "사업부" + (index + 1);
                long organizationId = organization(code, name);
                user(accounts.organizationEmail(code), "ORG_MANAGER", name + " 담당자 (데모)", organizationId);
            }
        });
    }

    /** 동일한 조직은 재사용하고 코드 충돌은 덮어쓰지 않는다.
     * Args: code 합성 코드, name 합성 이름. Returns: 조직 ID.
     * Raises: IllegalStateException 기존 이름·활성 상태 충돌, DB 오류. Side Effects: 없던 조직만 INSERT.
     */
    private long organization(String code, String name) {
        var rows = jdbc.sql("SELECT id,name,active FROM organization WHERE code=?").param(code).query().listOfRows();
        if (rows.isEmpty()) {
            jdbc.sql("INSERT INTO organization(code,name,active) VALUES (?,?,TRUE)").params(code, name).update();
            return jdbc.sql("SELECT id FROM organization WHERE code=?").param(code).query(Long.class).single();
        }
        var row = rows.getFirst();
        if (!name.equals(row.get("name")) || !Boolean.TRUE.equals(row.get("active"))) {
            throw new IllegalStateException("기존 조직과 데모 조직 설정이 충돌합니다.");
        }
        return ((Number) row.get("id")).longValue();
    }

    /** 이메일이 같은 기존 사용자의 역할·조직·이름을 임의 변경하지 않는다.
     * Args: email 허용된 합성 이메일, role 역할, name 데모 표시명, organizationId 담당 조직 또는 null.
     * Returns: 없음. Raises: IllegalStateException 계정 충돌, DB 오류. Side Effects: 없던 사용자만 INSERT.
     */
    private void user(String email, String role, String name, Long organizationId) {
        var rows = jdbc.sql("SELECT name,role,organization_id,active FROM app_user WHERE email=?")
                .param(email).query().listOfRows();
        if (rows.isEmpty()) {
            jdbc.sql("INSERT INTO app_user(email,name,role,organization_id,active) VALUES (?,?,?,?,TRUE)")
                    .params(email, name, role, organizationId).update();
            return;
        }
        var row = rows.getFirst();
        Long actual = row.get("organization_id") == null ? null : ((Number) row.get("organization_id")).longValue();
        if (!role.equals(row.get("role")) || !name.equals(row.get("name"))
                || !Objects.equals(organizationId, actual) || !Boolean.TRUE.equals(row.get("active"))) {
            throw new IllegalStateException("기존 이메일을 데모 사용자로 덮어쓸 수 없습니다.");
        }
    }
}
