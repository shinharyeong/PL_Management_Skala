package io.skala.backend.domain.decisionagent.service;

import io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.Snapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 연월 최신 뷰를 쓰는 기존 보고서 서비스가 선택한 마스터와 일치하는지 확인한다. */
@Service
@RequiredArgsConstructor
public class DecisionReportBasisGuard {
    private final JdbcTemplate jdbc;

    public record SourceBasis(String version, String fingerprint) { }
    private record Source(long requestId, String organizationId, String submissionId, Long planId) { }

    /** 목적: 새 읽기 스냅샷에서 최신 마스터·원천·비교 계획·검토 자료를 대조한다.
     * Args: snapshot 대화의 고정 마스터. Returns: 원천 version과 변경 감지 fingerprint.
     * Raises: ToolFailure 구마스터·조직/제출본 불일치, DB 조회 오류.
     * Side Effects: 별도 읽기 전용 트랜잭션의 SELECT만 수행하며 잠금·상태 변경 없음. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public SourceBasis check(Snapshot snapshot) {
        List<long[]> masters = jdbc.query("""
                SELECT m.id,m.request_id FROM master_version m
                JOIN submission_request r ON r.id=m.request_id JOIN annual_plan p ON p.id=r.annual_plan_id
                WHERE r.type='MONTHLY_ACTUAL' AND p.year=? AND r.month=?
                  AND NOT EXISTS (SELECT 1 FROM master_version newer WHERE newer.request_id=m.request_id AND newer.seq>m.seq)
                ORDER BY m.id""", (rs, n) -> new long[]{rs.getLong(1),rs.getLong(2)}, snapshot.year(), snapshot.baseMonth());
        if (masters.size() != 1 || !snapshot.masterId().equals(Long.toString(masters.getFirst()[0])))
            throw new DecisionAssistantTools.ToolFailure("기존 보고서는 해당 월의 최신 마스터를 사용합니다. 선택한 마스터와 달라 최신 기준을 선택한 뒤 다시 요청해 주세요.");
        String version = "master-" + snapshot.year() + "-" + String.format("%02d", snapshot.baseMonth());
        List<Source> rows = jdbc.query("""
                SELECT request_id,organization_id,submission_id,plan_submission_id FROM report.report_source
                WHERE version=? AND kind='master' ORDER BY organization_id,submission_id""",
                (rs,n) -> new Source(rs.getLong(1),Long.toString(rs.getLong(2)),Long.toString(rs.getLong(3)),
                        (Long) rs.getObject(4)), version);
        Set<String> organizations = new TreeSet<>(), submissions = new TreeSet<>();
        List<String> sourceParts = new ArrayList<>();
        for (Source source : rows) {
            if (source.requestId() != masters.getFirst()[1] || !organizations.add(source.organizationId()))
                throw new DecisionAssistantTools.ToolFailure("보고서 원천의 요청 또는 조직 범위가 선택한 기준과 다릅니다.");
            submissions.add(source.submissionId());
            sourceParts.add(source.requestId()+":"+source.organizationId()+":"+source.submissionId()+":"+source.planId());
        }
        Set<String> expectedOrganizations = new TreeSet<>();
        snapshot.organizations().forEach(value -> expectedOrganizations.add(value.id()));
        if (rows.isEmpty() || !organizations.equals(expectedOrganizations)
                || !submissions.equals(new TreeSet<>(snapshot.sourceSubmissionIds())))
            throw new DecisionAssistantTools.ToolFailure("보고서의 조직·제출본이 고정 마스터와 일치하지 않습니다.");
        String contents = jdbc.queryForObject("""
                WITH sources AS (
                    SELECT submission_id AS id FROM report.report_source WHERE version=?
                    UNION SELECT plan_submission_id FROM report.report_source WHERE version=? AND plan_submission_id IS NOT NULL
                ), lines AS (SELECT l.* FROM financial_line l JOIN sources s ON s.id=l.submission_id),
                items AS (SELECT i.* FROM review_item i JOIN sources s ON s.id=i.submission_id),
                explanations AS (SELECT e.* FROM review_explanation e JOIN items i ON i.id=e.review_item_id)
                SELECT concat_ws('|',
                  (SELECT md5(coalesce(string_agg(md5(row_to_json(l)::text),'' ORDER BY l.id),'')) FROM lines l),
                  (SELECT md5(coalesce(string_agg(md5(row_to_json(v)::text),'' ORDER BY v.id),'')) FROM financial_value v JOIN lines l ON l.id=v.line_id),
                  (SELECT md5(coalesce(string_agg(md5(row_to_json(i)::text),'' ORDER BY i.id),'')) FROM items i),
                  (SELECT md5(coalesce(string_agg(md5(row_to_json(g)::text),'' ORDER BY g.id),'')) FROM gap_comparison g JOIN items i ON i.id=g.review_item_id),
                  (SELECT md5(coalesce(string_agg(md5(row_to_json(e)::text),'' ORDER BY e.id),'')) FROM explanations e),
                  (SELECT md5(coalesce(string_agg(md5(row_to_json(c)::text),'' ORDER BY c.id),'')) FROM explanation_classification c JOIN explanations e ON e.id=c.review_explanation_id),
                  (SELECT md5(coalesce(string_agg(md5(row_to_json(c)::text),'' ORDER BY c.id),'')) FROM explanation_category c))
                """, String.class, version, version);
        return new SourceBasis(version, digest(snapshot.masterId()+"|"+String.join("|",sourceParts)+"|"+contents));
    }

    /** 목적: 원천 변경 시 기존 조회 결과의 채택을 거부한다.
     * Args: before 조회 전, after 조회 후 새 스냅샷. Returns: 없음.
     * Raises: ToolFailure 원천 변경. Side Effects: 없음. */
    public void requireSame(SourceBasis before, SourceBasis after) {
        if (!before.equals(after))
            throw new DecisionAssistantTools.ToolFailure("조회 중 기준·재무 원천 또는 소명이 변경됐습니다. 보고서를 다시 조회해 주세요.");
    }

    /** 목적: 원문을 공개하지 않는 변경 감지 값을 만든다. Args: value 원천 식별 문자열.
     * Returns: SHA-256 hex. Raises: IllegalStateException 실행 환경 오류. Side Effects: 없음. */
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256을 사용할 수 없습니다.",error); }
    }
}
