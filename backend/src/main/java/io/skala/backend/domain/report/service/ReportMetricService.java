package io.skala.backend.domain.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.skala.backend.domain.report.dto.MetricDtos;
import io.skala.backend.domain.report.exception.ReportErrorCode;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.response.PageResponse;
import io.skala.backend.global.security.AuthenticatedUser;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPT-08·09 지표 사전(FR-RPT-005·006). SQL 검사는 report-ai의 sqlguard(/v1/sql-check)에 맡긴다(검사기를 두 벌 두지 않음).
 * RPT-10에서 블록의 metricId를 등록된 SQL로 바꿔 넘긴다(블록에 SQL을 직접 저장하지 않아도 지표를 고치면 함께 바뀜).
 */
@Service
@RequiredArgsConstructor
public class ReportMetricService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final ReportAiClient reportAi;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<MetricDtos.Metric> list(AuthenticatedUser user, String q, String cursor, Integer limit) {
        user.requireRole(UserRole.PLANNER);
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        long after = cursor == null || cursor.isBlank() ? 0 : parseId(cursor, "cursor");
        String like = q == null || q.isBlank() ? null : "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<MetricDtos.Metric> rows = jdbc.query("""
                SELECT id, code, name, description, created_at FROM metric
                 WHERE active AND id > ? AND (?::text IS NULL OR code ILIKE ? OR name ILIKE ? OR description ILIKE ?)
                 ORDER BY id LIMIT ?""",
                (rs, n) -> new MetricDtos.Metric(String.valueOf(rs.getLong("id")), rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getObject("created_at", OffsetDateTime.class)),
                after, like, like, like, like, size + 1);
        String next = rows.size() > size ? rows.get(size - 1).id() : null;
        return new PageResponse<>(rows.size() > size ? rows.subList(0, size) : rows, next);
    }

    @Transactional
    public MetricDtos.Metric create(AuthenticatedUser user, MetricDtos.CreateRequest request) {
        user.requireRole(UserRole.PLANNER);
        JsonNode check = reportAi.post("/v1/sql-check", Map.of("sql", request.sqlText()), user.id());
        if (!check.path("ok").asBoolean(false)) {
            throw ReportErrorCode.SQL_NOT_ALLOWED.exception(check.path("message").asText(null));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            long id = jdbc.queryForObject("""
                    INSERT INTO metric(code, name, description, sql_text, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, request.code(), request.name().strip(),
                    request.description(), check.path("sql").asText(request.sqlText().strip()), user.id(), now, now);
            return new MetricDtos.Metric(String.valueOf(id), request.code(), request.name().strip(), request.description(), now);
        } catch (DuplicateKeyException e) {
            throw ReportErrorCode.METRIC_CODE_EXISTS.exception();
        }
    }

    /** RPT-10: 블록의 metricId를 등록된 SQL로 바꾼다(sql을 직접 가진 블록은 그대로). 없는 지표면 400 */
    @Transactional(readOnly = true)
    public List<JsonNode> resolveMetrics(List<JsonNode> blocks) {
        Map<Long, String> cache = new HashMap<>();
        List<JsonNode> out = new ArrayList<>(blocks.size());
        for (JsonNode block : blocks) {
            if (block == null || !block.isObject() || !block.hasNonNull("metricId") || block.hasNonNull("sql")) {
                out.add(block);
                continue;
            }
            long id = parseId(block.get("metricId").asText(), "metricId");
            String sql = cache.computeIfAbsent(id, k -> jdbc.queryForList(
                            "SELECT sql_text FROM metric WHERE id = ? AND active", String.class, k).stream().findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "지표를 찾을 수 없습니다: " + k)));
            ObjectNode copy = ((ObjectNode) block).deepCopy();
            copy.put("sql", sql);
            out.add(copy);
        }
        return out;
    }

    private static long parseId(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, field + " 형식이 올바르지 않습니다.");
        }
    }
}
