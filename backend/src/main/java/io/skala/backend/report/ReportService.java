package io.skala.backend.report;

import static io.skala.backend.submission.ApiData.*;

import io.skala.backend.common.ApiException;
import io.skala.backend.master.MonthlyMasterService;
import io.skala.backend.security.Actor;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 사용자가 선택한 불변 월 마스터와 그 출처의 수용 소명만 보고 데이터로 묶는다. */
@Service
public class ReportService {
    public record Input(@NotNull @Size(min=1,max=24) List<@NotBlank @Pattern(regexp="[1-9][0-9]*") String> masterIds,
                        Boolean includeExplanations) {}
    private final MonthlyMasterService masters;
    private final JdbcClient jdbc;

    /** 보고 데이터에 필요한 고정 마스터·검토 조회 의존성을 연결한다.
     * Args: masters 마스터 검증·집계, jdbc 소명 읽기. Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public ReportService(MonthlyMasterService masters, JdbcClient jdbc) { this.masters = masters; this.jdbc = jdbc; }

    /** 서로 다른 연월의 선택 마스터를 같은 DB 스냅샷에서 읽는다.
     * Args: actor 기획자, input 선택 masterIds와 수용 소명 포함 여부.
     * Returns: 기간별 실제 보고값·출처·계획 비교·수용 소명. Raises: 권한·중복 기간·없는 마스터·고정 근거 오류.
     * Side Effects: 읽기만 수행하며 최신 버전으로 자동 교체하거나 보고서 파일·DB 행을 생성하지 않는다.
     */
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(Actor actor, Input input) {
        actor.requireRole("PLANNER");
        if (input == null || input.masterIds() == null || input.masterIds().isEmpty() || input.masterIds().size() > 24)
            throw invalid("마스터를 1~24개 선택해 주세요.");
        var ids = new HashSet<Long>(); var months = new HashSet<String>();
        var periods = new ArrayList<Map<String,Object>>(); var versions = new ArrayList<Map<String,Object>>();
        var contingencies = new LinkedHashMap<String,Object>(); var explanations = new ArrayList<Map<String,Object>>();
        for (String masterId : input.masterIds()) {
            if (!ids.add(id(masterId))) throw invalid("같은 마스터를 중복 선택할 수 없습니다.");
            var bundle = masters.reportData(actor, masterId);
            var master = (Map<String,Object>) bundle.get("master");
            if (!months.add(master.get("year") + ":" + master.get("month"))) throw invalid("같은 연월에는 하나의 마스터 버전만 선택해 주세요.");
            var period = object("masterId", master.get("id"), "year", master.get("year"), "month", master.get("month"),
                    "versionLabel", master.get("versionLabel"), "scenario", "ACTUAL", "totalRows", bundle.get("totalRows"),
                    "organizations", bundle.get("organizations"), "organizationSummaries", master.get("organizations"),
                    "totals", master.get("totals"), "planTotals", master.get("planTotals"), "comparisons", master.get("comparisons"),
                    "sources", master.get("sources"));
            periods.add(period);
            versions.add(object("masterId", master.get("id"), "version", master.get("calculationVersion")));
            var contingency = (Map<String,Object>) master.get("annualContingency");
            if (contingency != null) contingencies.put(contingency.get("annualPlanId").toString(),
                    object("annualPlanId", contingency.get("annualPlanId"), "year", master.get("year"), "amount", contingency.get("amount"), "scope", "ANNUAL"));
            if (Boolean.TRUE.equals(input.includeExplanations())) explanations.addAll(explanations(master));
        }
        periods.sort(Comparator.comparingInt((Map<String,Object> p) -> ((Number)p.get("year")).intValue())
                .thenComparingInt(p -> ((Number)p.get("month")).intValue()));
        return object("generatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString(), "masterIds", List.copyOf(input.masterIds()),
                "calculationVersions", versions, "periods", periods, "annualContingencies", new ArrayList<>(contingencies.values()), "explanations", explanations);
    }

    /** 해당 마스터의 실제 source 제출본·대상 월에 고정된 수용 소명만 읽는다.
     * Args: master 검증된 마스터 상세. Returns: 수용 소명 배열. Raises: DB 읽기 오류.
     * Side Effects: DB 읽기만 수행하며 다른 버전·미수용·미래 추정 소명을 섞지 않는다.
     */
    private List<Map<String,Object>> explanations(Map<String,Object> master) {
        var result = new ArrayList<Map<String,Object>>();
        for (var source : (List<Map<String,Object>>) master.get("sources")) {
            var rows = jdbc.sql("""
                    SELECT i.id AS review_id,i.submission_id,i.report_item_id,s.organization_id,i.year,i.month,i.scenario,
                        e.id AS explanation_id,e.round,e.answer,e.answered_at,e.reviewed_at,e.inherited_from_id,
                        a.id AS author_id,a.name AS author_name,a.email AS author_email,a.role AS author_role,
                        r.id AS reviewer_id,r.name AS reviewer_name,r.email AS reviewer_email,r.role AS reviewer_role,
                        previous.review_item_id AS previous_review_id,prior_item.submission_id AS previous_submission_id,
                        prior_submission.seq AS previous_seq,prior_request.type AS previous_type,
                        prior_request.month AS previous_month,prior_plan.year AS previous_year
                    FROM review_item i JOIN review_explanation e ON e.review_item_id=i.id AND e.round=i.current_round
                    JOIN submission s ON s.id=i.submission_id
                    LEFT JOIN app_user a ON a.id=e.answered_by LEFT JOIN app_user r ON r.id=e.reviewed_by
                    LEFT JOIN review_explanation previous ON previous.id=e.inherited_from_id
                    LEFT JOIN review_item prior_item ON prior_item.id=previous.review_item_id
                    LEFT JOIN submission prior_submission ON prior_submission.id=prior_item.submission_id
                    LEFT JOIN submission_request prior_request ON prior_request.id=prior_submission.request_id
                    LEFT JOIN annual_plan prior_plan ON prior_plan.id=prior_request.annual_plan_id
                    WHERE i.submission_id=? AND s.organization_id=? AND i.year=? AND i.month=?
                      AND i.scenario='ACTUAL' AND i.active=TRUE AND e.verdict='ACCEPTED' AND e.answer IS NOT NULL
                    ORDER BY i.report_item_id,i.id
                    """).params(id(source.get("submissionId").toString()), id(source.get("organizationId").toString()), master.get("year"), master.get("month")).query().listOfRows();
            for (var row : rows) result.add(object("masterId", master.get("id"), "submissionId", row.get("submission_id").toString(),
                    "reviewItemId", row.get("review_id").toString(), "reportItemId", row.get("report_item_id").toString(),
                    "organizationId", row.get("organization_id").toString(), "year", row.get("year"), "month", row.get("month"), "scenario", "ACTUAL",
                    "explanationId", row.get("explanation_id").toString(), "round", row.get("round"), "answer", row.get("answer"),
                    "answeredBy", person(row, "author"), "answeredAt", instant(row.get("answered_at")),
                    "reviewedBy", person(row, "reviewer"), "reviewedAt", instant(row.get("reviewed_at")),
                    "inheritedFrom", row.get("inherited_from_id") == null ? null : object("explanationId", row.get("inherited_from_id").toString(),
                            "reviewItemId", row.get("previous_review_id").toString(), "submissionId", row.get("previous_submission_id").toString(),
                            "versionLabel", row.get("previous_year") + "-" + ("ANNUAL_PLAN".equals(row.get("previous_type")) ? "PLAN" : String.format("%02d", row.get("previous_month"))) + "-" + String.format("%02d", row.get("previous_seq")))));
        }
        return result;
    }

    /** 소명 행위자의 안전한 표시 필드만 반환한다.
     * Args: row 조회행, prefix author/reviewer. Returns: ActorRef/null. Raises: 없음. Side Effects: 없음.
     */
    private Map<String,Object> person(Map<String,Object> row, String prefix) {
        return row.get(prefix + "_id") == null ? null : object("id", row.get(prefix + "_id").toString(), "name", row.get(prefix + "_name"), "email", row.get(prefix + "_email"), "role", row.get(prefix + "_role"));
    }

    /** 보고 입력 오류를 공통 계약으로 만든다.
     * Args: message 조치 안내. Returns: 400 예외. Raises: 없음. Side Effects: 없음.
     */
    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
}
