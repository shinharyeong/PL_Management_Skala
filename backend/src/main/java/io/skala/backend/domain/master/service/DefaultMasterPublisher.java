package io.skala.backend.domain.master.service;

import io.skala.backend.domain.master.dto.PublishedMaster;
import io.skala.backend.domain.request.value.RequestType;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월간(master_version·master_source)과 연간(annual_master·annual_master_source)을 같은 규칙으로 발행한다.
 * 같은 요청의 승인이 동시에 들어와도 버전이 꼬이지 않도록 요청 행을 잠근 뒤 계산한다.
 */
@Service
@RequiredArgsConstructor
public class DefaultMasterPublisher implements MasterPublisher {

    /** 승인본 구성으로 master를 만드는 규칙 버전(content_sha256 계산 방식 포함) */
    static final String CALCULATION_VERSION = "master-sources/1.0";

    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PublishedMaster> publishIfComplete(Long approvedSubmissionId) {
        // 호출한 쪽의 승인 변경(JPA)이 아래 SQL 조회에 보이도록 먼저 반영한다.
        entityManager.flush();
        Approved approved = jdbc.query("""
                        SELECT s.request_id, s.status, s.reviewed_by, r.type, r.month, p.id AS plan_id, p.year
                          FROM submission s
                          JOIN submission_request r ON r.id = s.request_id
                          JOIN annual_plan p ON p.id = r.annual_plan_id
                         WHERE s.id = ?""",
                (rs, i) -> new Approved(rs.getLong("request_id"), rs.getString("status"), (Long) rs.getObject("reviewed_by"),
                        RequestType.valueOf(rs.getString("type")), (Integer) rs.getObject("month"), rs.getLong("plan_id"),
                        rs.getInt("year")),
                approvedSubmissionId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("submission not found: " + approvedSubmissionId));
        if (!"APPROVED".equals(approved.status()) || approved.reviewedBy() == null) {
            throw new IllegalStateException("승인 처리(status=APPROVED, reviewed_by)가 끝난 제출본만 master 발행을 확인할 수 있습니다: "
                    + approvedSubmissionId);
        }
        jdbc.queryForObject("SELECT id FROM submission_request WHERE id = ? FOR UPDATE", Long.class, approved.requestId());

        List<Source> sources = jdbc.query("""
                SELECT t.organization_id, a.id AS submission_id, a.source_sha256
                  FROM request_target t
                  LEFT JOIN LATERAL (
                       SELECT s.id, s.source_sha256 FROM submission s
                        WHERE s.request_id = t.request_id AND s.organization_id = t.organization_id AND s.status = 'APPROVED'
                        ORDER BY s.approved_seq DESC, s.seq DESC LIMIT 1) a ON true
                 WHERE t.request_id = ? AND t.selected AND NOT t.excluded
                 ORDER BY t.organization_id""",
                (rs, i) -> new Source(rs.getLong("organization_id"), (Long) rs.getObject("submission_id"),
                        rs.getString("source_sha256")),
                approved.requestId());
        if (sources.isEmpty() || sources.stream().anyMatch(s -> s.submissionId() == null)) {
            return Optional.empty();
        }

        Tables t = approved.type() == RequestType.ANNUAL_PLAN ? Tables.ANNUAL : Tables.MONTHLY;
        Previous previous = jdbc.query("SELECT id, seq FROM " + t.master + " WHERE request_id = ? ORDER BY seq DESC LIMIT 1",
                (rs, i) -> new Previous(rs.getLong("id"), rs.getInt("seq")), approved.requestId()).stream().findFirst().orElse(null);
        if (previous != null && sameSources(t, previous.id(), sources)) {
            return Optional.empty();
        }

        int seq = previous == null ? 1 : previous.seq() + 1;
        Long masterId = jdbc.queryForObject("INSERT INTO " + t.master + """
                         (request_id, seq, previous_master_id, trigger_submission_id, content_sha256, calculation_version,
                          created_by, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                approved.requestId(), seq, previous == null ? null : previous.id(), approvedSubmissionId, contentSha256(sources),
                CALCULATION_VERSION, approved.reviewedBy(), OffsetDateTime.now(clock));
        jdbc.batchUpdate("INSERT INTO " + t.source + " (master_id, organization_id, submission_id) VALUES (?, ?, ?)",
                sources.stream().map(s -> new Object[]{masterId, s.organizationId(), s.submissionId()}).toList());

        if (t == Tables.ANNUAL) {
            jdbc.update("""
                    UPDATE annual_plan SET status = 'CONFIRMED', confirmed_by = ?, confirmed_at = ?
                     WHERE id = ? AND status = 'COLLECTING'""", approved.reviewedBy(), OffsetDateTime.now(clock), approved.planId());
            return Optional.of(new PublishedMaster(masterId, approved.type(), MasterLabels.annual(approved.year(), seq)));
        }
        return Optional.of(new PublishedMaster(masterId, approved.type(), MasterLabels.monthly(approved.month(), seq)));
    }

    private boolean sameSources(Tables t, long masterId, List<Source> sources) {
        List<String> previous = jdbc.query("SELECT organization_id, submission_id FROM " + t.source
                        + " WHERE master_id = ? ORDER BY organization_id",
                (rs, i) -> rs.getLong("organization_id") + ":" + rs.getLong("submission_id"), masterId);
        return previous.equals(sources.stream().map(s -> s.organizationId() + ":" + s.submissionId()).toList());
    }

    /** 조직 순으로 "조직:제출본:원본 해시"를 이어 붙인 SHA-256 */
    static String contentSha256(List<Source> sources) {
        StringBuilder text = new StringBuilder();
        sources.forEach(s -> text.append(s.organizationId()).append(':').append(s.submissionId()).append(':')
                .append(s.sourceSha256()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private enum Tables {
        MONTHLY("master_version", "master_source"),
        ANNUAL("annual_master", "annual_master_source");

        final String master;
        final String source;

        Tables(String master, String source) {
            this.master = master;
            this.source = source;
        }
    }

    private record Approved(long requestId, String status, Long reviewedBy, RequestType type, Integer month, long planId,
                            int year) {
    }

    record Source(long organizationId, Long submissionId, String sourceSha256) {
    }

    private record Previous(long id, int seq) {
    }
}
