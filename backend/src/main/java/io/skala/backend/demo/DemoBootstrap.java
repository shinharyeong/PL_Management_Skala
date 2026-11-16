package io.skala.backend.demo;

import io.skala.backend.submission.SubmissionService;
import java.util.Objects;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 명시한 local+demo에서만 사업 조직·담당자와 연간 수집 요청을 준비하고 과거 데모 조직은 비활성화한다. */
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

    /** 17개 사업부를 준비하고 이력을 보존한 채 미확정·미발행 연간 요청에서 과거 데모 대상을 제외한다.
     * Args: arguments 시작 인자이며 사용하지 않음. Returns: 없음.
     * Raises: IllegalStateException 기존 자료 충돌, 업무/DB 오류.
     * Side Effects: 하나의 DB 트랜잭션에서 사업 조직·계정·기본 요청 생성, 과거 데모 비활성화, 수집 대상 보정.
     */
    @Override
    public void run(ApplicationArguments arguments) {
        transactions.executeWithoutResult(transaction -> {
            user(accounts.email("PLANNER"), "PLANNER", "경영기획 담당자 (데모)", null);
            for (int index = 0; index < DemoAccounts.ORGANIZATION_CODES.size(); index++) {
                String code = DemoAccounts.ORGANIZATION_CODES.get(index);
                String name = "사업부" + (index + 1);
                long organizationId = organization(code, name);
                user(accounts.organizationEmail(code), "ORG_MANAGER", name + " 담당자 (데모)", organizationId);
            }
            retireLegacyOrganizations();
            var planner = accounts.actor("PLANNER");
            var existing = jdbc.sql("SELECT id,type,requested_by FROM submission_request WHERE period_key='2027-PLAN'")
                    .query().listOfRows();
            if (existing.isEmpty()) {
                submissions.createRequest(planner, "demo-bootstrap-plan-2027-v1",
                        new SubmissionService.RequestInput("ANNUAL_PLAN", 2027, null,
                                "체험용 2027 연간 계획입니다. 합성 양식을 내려받아 파싱을 확인하세요.", null));
            } else {
                var request = existing.getFirst();
                if (!"ANNUAL_PLAN".equals(request.get("type"))
                        || !planner.id().equals(request.get("requested_by").toString())) {
                    throw new IllegalStateException("기존 2027 요청을 데모 요청으로 변경할 수 없습니다.");
                }
            }
            reconcileCollectingTargets();
        });
    }

    /** 과거 체험 조직과 그 소속 계정의 접근을 막고 원본·제출·외래 키는 보존한다.
     * Args: 없음. Returns: 없음. Raises: DB 오류.
     * Side Effects: 기존 D100/D200 조직 및 소속 사용자 active를 FALSE로 변경하며 행은 삭제하지 않는다.
     */
    private void retireLegacyOrganizations() {
        jdbc.sql("""
                UPDATE app_user SET active=FALSE
                WHERE active=TRUE AND organization_id IN (SELECT id FROM organization WHERE code IN ('D100','D200'))
                """).update();
        jdbc.sql("UPDATE organization SET active=FALSE WHERE active=TRUE AND code IN ('D100','D200')").update();
    }

    /** 미확정·미발행 연간 요청에서 과거 데모 대상만 제외하고 누락된 사업 조직 대상을 추가한다.
     * Args: 없음. Returns: 없음. Raises: DB 오류.
     * Side Effects: 수집 중 요청의 대상에만 UPDATE/INSERT; 제출·원본·확정 계획·발행 마스터는 변경하지 않는다.
     */
    private void reconcileCollectingTargets() {
        var requests = jdbc.sql("""
                SELECT r.id FROM submission_request r JOIN annual_plan p ON p.id=r.annual_plan_id
                WHERE r.type='ANNUAL_PLAN' AND p.status='COLLECTING'
                  AND NOT EXISTS (SELECT 1 FROM annual_master m WHERE m.request_id=r.id)
                """).query(Long.class).list();
        for (Long requestId : requests) {
            jdbc.sql("""
                    UPDATE request_target SET excluded=TRUE WHERE request_id=? AND excluded=FALSE
                    AND organization_id IN (SELECT id FROM organization WHERE code IN ('D100','D200'))
                    """).param(requestId).update();
            for (String code : DemoAccounts.ORGANIZATION_CODES) {
                jdbc.sql("""
                        INSERT INTO request_target(request_id,organization_id)
                        SELECT ?,o.id FROM organization o WHERE o.code=? AND o.active=TRUE
                        AND NOT EXISTS (SELECT 1 FROM request_target t WHERE t.request_id=? AND t.organization_id=o.id)
                        """).params(requestId, code, requestId).update();
            }
        }
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
