package io.skala.backend.domain.master.service;

import io.skala.backend.domain.master.dto.AnnualMasterResponse;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 연간 master 버전 목록·계획값 엑셀. 확정 master의 출처 제출본만 조회한다. */
@Service
@RequiredArgsConstructor
public class AnnualMasterQueryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AnnualMasterResponse.ListResponse list(AuthenticatedUser actor, Integer year) {
        actor.requireRole(UserRole.PLANNER);
        int target = year != null ? year : LocalDate.now(clock.withZone(KST)).getYear();
        if (target < 2000 || target > 9999) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        List<AnnualMasterResponse> items = jdbc.query("""
                SELECT m.id,m.seq,m.created_at,u.name,
                       (SELECT count(*) FROM annual_master_source s WHERE s.master_id=m.id) AS org_count
                  FROM annual_master m JOIN submission_request r ON r.id=m.request_id
                  JOIN annual_plan p ON p.id=r.annual_plan_id
                  JOIN app_user u ON u.id=m.created_by
                 WHERE p.year=? ORDER BY m.seq DESC,m.id DESC
                """, (rs,i)->new AnnualMasterResponse(String.valueOf(rs.getLong("id")),target,
                        rs.getInt("seq"),MasterLabels.annual(target,rs.getInt("seq")),
                        rs.getObject("created_at",OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC),
                        rs.getString("name"),rs.getInt("org_count")),target);
        boolean requested = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM submission_request r JOIN annual_plan p ON p.id=r.annual_plan_id
                 WHERE p.year=? AND r.type='ANNUAL_PLAN')
                """,Boolean.class,target));
        return new AnnualMasterResponse.ListResponse(target,items.isEmpty()?(requested?"WAITING":"NO_REQUEST"):"CONFIRMED",items);
    }

    /** 다운로드 파일 이름. 없는 master면 404 */
    @Transactional(readOnly = true)
    public String filename(AuthenticatedUser actor, Long masterId) {
        actor.requireRole(UserRole.PLANNER);
        return jdbc.query("""
                SELECT p.year, m.seq FROM annual_master m
                  JOIN submission_request r ON r.id = m.request_id
                  JOIN annual_plan p ON p.id = r.annual_plan_id
                 WHERE m.id = ?""", (rs, i) -> "%d년 연간계획 %s.xlsx".formatted(rs.getInt("year"),
                        MasterLabels.annual(rs.getInt("year"), rs.getInt("seq"))), masterId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * master 엑셀: '손익' 시트(조직 × 보고 항목 × 시나리오 × 연월 금액 합계)와 '출처' 시트(조직별 승인본).
     * 금액은 원 단위 그대로, 값이 없는 칸은 만들지 않는다. 양식의 전년 실적 등 요청 연도가 아닌 참고값은 넣지 않는다.
     */
    @Transactional(readOnly = true)
    public void writeExcel(Long masterId, OutputStream out) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200)) {
            Sheet values = workbook.createSheet("손익");
            header(values, "조직 코드", "조직명", "보고 항목 코드", "보고 항목", "시나리오", "연도", "월", "금액");
            int[] row = {1};
            jdbc.query("""
                    SELECT o.code AS org_code, o.name AS org_name, ri.code AS item_code, ri.name AS item_name,
                           v.scenario, v.year, v.month, sum(v.amount) AS amount
                      FROM annual_master_source ms
                      JOIN annual_master m ON m.id = ms.master_id
                      JOIN submission_request r ON r.id = m.request_id
                      JOIN annual_plan p ON p.id = r.annual_plan_id
                      JOIN organization o ON o.id = ms.organization_id
                      JOIN financial_line l ON l.submission_id = ms.submission_id
                      JOIN report_item ri ON ri.id = l.report_item_id
                      JOIN financial_value v ON v.line_id = l.id
                     WHERE ms.master_id = ? AND v.year = p.year AND v.scenario = 'PLAN'
                     GROUP BY o.code, o.name, ri.code, ri.name, ri.display_order, v.scenario, v.year, v.month
                     ORDER BY o.code, ri.display_order, ri.code, v.scenario, v.year, v.month""", rs -> {
                Row r = values.createRow(row[0]++);
                r.createCell(0).setCellValue(rs.getString("org_code"));
                r.createCell(1).setCellValue(rs.getString("org_name"));
                r.createCell(2).setCellValue(rs.getString("item_code"));
                r.createCell(3).setCellValue(rs.getString("item_name"));
                r.createCell(4).setCellValue(rs.getString("scenario"));
                r.createCell(5).setCellValue(rs.getInt("year"));
                r.createCell(6).setCellValue(rs.getInt("month"));
                writeAmount(r, 7, rs.getBigDecimal("amount"));
            }, masterId);

            Sheet sources = workbook.createSheet("출처");
            header(sources, "조직 코드", "조직명", "제출본 ID", "회차", "승인 회차", "파일명", "승인 시각(KST)");
            int[] sourceRow = {1};
            jdbc.query("""
                    SELECT o.code, o.name, s.id, s.seq, s.approved_seq, s.original_filename, s.reviewed_at
                      FROM annual_master_source ms
                      JOIN organization o ON o.id = ms.organization_id
                      JOIN submission s ON s.id = ms.submission_id
                     WHERE ms.master_id = ?
                     ORDER BY o.code""", rs -> {
                Row r = sources.createRow(sourceRow[0]++);
                r.createCell(0).setCellValue(rs.getString("code"));
                r.createCell(1).setCellValue(rs.getString("name"));
                r.createCell(2).setCellValue(rs.getLong("id"));
                r.createCell(3).setCellValue(rs.getInt("seq"));
                r.createCell(4).setCellValue(rs.getInt("approved_seq"));
                r.createCell(5).setCellValue(rs.getString("original_filename"));
                OffsetDateTime reviewedAt = rs.getObject("reviewed_at", OffsetDateTime.class);
                r.createCell(6).setCellValue(reviewedAt == null ? "" : reviewedAt.atZoneSameInstant(KST).toLocalDateTime().toString());
            }, masterId);
            workbook.write(out);
        }
    }

    /**
     * 엑셀 숫자 칸은 double(유효숫자 15자리)이라, 원 단위 정수로 15자리 이하면 숫자로 쓰고
     * 소수가 있거나 더 긴 금액은 값이 바뀌지 않도록 문자열로 쓴다.
     */
    static void writeAmount(Row row, int column, BigDecimal amount) {
        BigDecimal value = amount.stripTrailingZeros();
        if (value.scale() <= 0 && value.precision() - value.scale() <= 15) {
            row.createCell(column).setCellValue(value.doubleValue());
        } else {
            row.createCell(column).setCellValue(value.toPlainString());
        }
    }

    private static void header(Sheet sheet, String... names) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < names.length; i++) {
            row.createCell(i).setCellValue(names[i]);
        }
    }
}
