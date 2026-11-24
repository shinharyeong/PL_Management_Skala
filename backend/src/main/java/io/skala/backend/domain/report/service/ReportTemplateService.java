package io.skala.backend.domain.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.skala.backend.domain.report.dto.ReportPeriodResponse;
import io.skala.backend.domain.report.dto.TemplateCreateRequest;
import io.skala.backend.domain.report.dto.TemplateResponse;
import io.skala.backend.domain.report.dto.TemplateVersionRequest;
import io.skala.backend.domain.report.exception.ReportErrorCode;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.response.PageResponse;
import io.skala.backend.global.security.AuthenticatedUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPT-03~07 자료 틀 저장·불러오기와 기준 월 선택지(FR-RPT-002·003·004).
 *
 * <p>틀 정의(definition)는 AI 모델 형식 그대로 jsonb에 넣고 꺼낸다. 백엔드는 형식 검사(JSON 스키마)와 버전 관리만 한다.
 * 저장할 때마다 새 버전이 쌓이고 이전 버전은 남는다. 틀은 경영기획팀이 함께 쓴다(목록은 사용 중인 틀 전부).
 */
@Service
@RequiredArgsConstructor
public class ReportTemplateService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TemplateDefinitionValidator validator;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<TemplateResponse> list(AuthenticatedUser user, String cursor, Integer limit) {
        user.requireRole(UserRole.PLANNER);
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        long after = cursor == null || cursor.isBlank() ? Long.MAX_VALUE : parseId(cursor, "cursor");
        List<TemplateResponse> rows = jdbc.query("""
                SELECT t.id, t.name, t.created_at, t.updated_at, max(v.version) AS latest
                  FROM report_template t JOIN report_template_version v ON v.template_id = t.id
                 WHERE t.active AND t.id < ?
                 GROUP BY t.id ORDER BY t.id DESC LIMIT ?""",
                (rs, n) -> new TemplateResponse(String.valueOf(rs.getLong("id")), rs.getString("name"), rs.getInt("latest"),
                        null, null, time(rs, "created_at"), time(rs, "updated_at")), after, size + 1);
        String next = rows.size() > size ? rows.get(size - 1).id() : null;
        return new PageResponse<>(rows.size() > size ? rows.subList(0, size) : rows, next);
    }

    @Transactional
    public TemplateResponse create(AuthenticatedUser user, TemplateCreateRequest request) {
        user.requireRole(UserRole.PLANNER);
        Long pdfJob = null;
        if (request.sourcePdfJobId() != null) {
            pdfJob = parseId(request.sourcePdfJobId(), "sourcePdfJobId");
            if (jdbc.queryForObject("SELECT count(*) FROM pdf_job WHERE id = ?", Integer.class, pdfJob) == 0) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "PDF 분석 작업을 찾을 수 없습니다.");
            }
        }
        String name = request.name().strip();
        OffsetDateTime now = OffsetDateTime.now(clock);
        long id = jdbc.queryForObject("""
                INSERT INTO report_template(name, created_by, created_at, updated_at) VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, name, user.id(), now, now);
        JsonNode definition = normalize(request.definition(), id, name);
        validator.validate(definition);
        insertVersion(id, 1, pdfJob, definition, user.id(), now);
        return get(user, id, 1);
    }

    @Transactional
    public TemplateResponse addVersion(AuthenticatedUser user, long templateId, TemplateVersionRequest request) {
        user.requireRole(UserRole.PLANNER);
        Map<String, Object> template = jdbc.queryForList(
                "SELECT name FROM report_template WHERE id = ? AND active FOR UPDATE", templateId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "자료 틀을 찾을 수 없습니다."));
        int latest = jdbc.queryForObject("SELECT max(version) FROM report_template_version WHERE template_id = ?",
                Integer.class, templateId);
        if (request.expectedVersion() != latest) {
            throw ReportErrorCode.REPORT_REVISION_CHANGED.exception();
        }
        JsonNode definition = normalize(request.definition(), templateId, (String) template.get("name"));
        validator.validate(definition);
        OffsetDateTime now = OffsetDateTime.now(clock);
        insertVersion(templateId, latest + 1, null, definition, user.id(), now);
        jdbc.update("UPDATE report_template SET updated_at = ? WHERE id = ?", now, templateId);
        return get(user, templateId, latest + 1);
    }

    @Transactional(readOnly = true)
    public TemplateResponse get(AuthenticatedUser user, long templateId, Integer version) {
        user.requireRole(UserRole.PLANNER);
        return jdbc.query("""
                SELECT t.id, t.name, t.created_at, t.updated_at, v.version, v.definition::text AS definition,
                       (SELECT max(version) FROM report_template_version WHERE template_id = t.id) AS latest
                  FROM report_template t JOIN report_template_version v ON v.template_id = t.id
                 WHERE t.id = ? AND t.active
                   AND v.version = coalesce(?, (SELECT max(version) FROM report_template_version WHERE template_id = t.id))""",
                (rs, n) -> new TemplateResponse(String.valueOf(rs.getLong("id")), rs.getString("name"), rs.getInt("latest"),
                        rs.getInt("version"), readJson(rs.getString("definition")), time(rs, "created_at"),
                        time(rs, "updated_at")), templateId, version).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "자료 틀을 찾을 수 없습니다."));
    }

    /**
     * RPT-07. 월별 상태는 보고서용 뷰 dim_version(홈과 같은 규칙)을 그대로 쓴다.
     * default = 가장 최근 확정 월(없으면 가장 최근 잠정 월). year가 없으면 올해(KST)
     */
    @Transactional(readOnly = true)
    public ReportPeriodResponse periods(AuthenticatedUser user, Integer year) {
        user.requireRole(UserRole.PLANNER);
        int target = year != null ? year : LocalDate.now(clock.withZone(KST)).getYear();
        if (target < 2000 || target > 9999) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "year는 2000~9999입니다.");
        }
        Map<Integer, String> states = new HashMap<>();
        jdbc.query("SELECT month, kind FROM report.dim_version WHERE year = ? AND month IS NOT NULL", rs -> {
            states.put(rs.getInt("month"), "master".equals(rs.getString("kind")) ? "CONFIRMED" : "PROVISIONAL");
        }, target);
        Map<Integer, Long> masters = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (r.month) r.month, m.id FROM master_version m
                  JOIN submission_request r ON r.id = m.request_id JOIN annual_plan p ON p.id = r.annual_plan_id
                 WHERE p.year = ? AND r.type = 'MONTHLY_ACTUAL' ORDER BY r.month, m.seq DESC""",
                rs -> {
                    masters.put(rs.getInt("month"), rs.getLong("id"));
                }, target);
        List<ReportPeriodResponse.Month> months = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            Long master = masters.get(month);
            months.add(new ReportPeriodResponse.Month(month, states.getOrDefault(month, "NONE"),
                    master == null ? null : String.valueOf(master)));
        }
        ReportPeriodResponse.YearMonth latest = jdbc.query("""
                SELECT year, month FROM report.dim_version WHERE month IS NOT NULL
                 ORDER BY CASE kind WHEN 'master' THEN 0 ELSE 1 END, year DESC, month DESC LIMIT 1""",
                (rs, n) -> new ReportPeriodResponse.YearMonth(rs.getInt("year"), rs.getInt("month"))).stream()
                .findFirst().orElse(null);
        return new ReportPeriodResponse(latest, target, months);
    }

    /** definition의 id·name을 서버 값으로 맞춘다(목록·불러오기와 어긋나지 않게). 그 밖의 내용은 그대로 */
    private JsonNode normalize(JsonNode definition, long templateId, String name) {
        if (definition == null || !definition.isObject()) {
            validator.validate(definition);
        }
        ObjectNode copy = ((ObjectNode) definition).deepCopy();
        copy.put("id", String.valueOf(templateId));
        copy.put("name", name);
        return copy;
    }

    private void insertVersion(long templateId, int version, Long pdfJob, JsonNode definition, long userId,
                               OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO report_template_version(template_id, version, source_pdf_job_id, definition, created_by, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)""", templateId, version, pdfJob, definition.toString(), userId, now);
    }

    private JsonNode readJson(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 자료 틀을 읽을 수 없습니다.", e);
        }
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    private static long parseId(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, field + " 형식이 올바르지 않습니다.");
        }
    }
}
