package io.skala.backend.domain.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.skala.backend.domain.report.dto.ExportRequest;
import io.skala.backend.domain.report.dto.TemplateResponse;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/**
 * RPT-16 XLSX 내보내기(FR-RPT-009). 결과는 저장하지 않는다.
 * 화면에서 받은 숫자를 쓰지 않고, 저장된 틀과 기준 월로 report-ai에 다시 채워(RPT-10과 같은 경로) 원 단위 값 그대로 넣는다.
 * 셀의 won(원 단위)이 있으면 숫자로, 없으면 화면 글자로. PNG·PDF는 화면(브라우저)에서 만든다.
 */
@Service
@RequiredArgsConstructor
public class ReportExportService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MAX_CELL_TEXT = 32_767;          // Excel 셀 글자 상한

    private final ReportTemplateService templates;
    private final ReportMetricService metrics;
    private final ReportAiClient reportAi;
    private final Clock clock;

    public record Export(String filename, byte[] content) {
    }

    public Export export(AuthenticatedUser user, ExportRequest request) {
        TemplateResponse template = templates.get(user, parseId(request.templateId()), request.version());
        List<JsonNode> blocks = new ArrayList<>();
        for (JsonNode block : template.definition().path("blocks")) {
            if (request.blockId() == null || request.blockId().equals(block.path("id").asText())) {
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "내보낼 블록이 없습니다.");
        }
        int year = request.period().year();
        int month = request.period().month();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("base", Map.of("year", year, "month", month));
        body.put("blocks", metrics.resolveMetrics(blocks));
        JsonNode filled = reportAi.post("/v1/data", body, user.id());

        Map<String, JsonNode> byId = new LinkedHashMap<>();
        filled.path("blocks").forEach(b -> byId.put(b.path("id").asText(), b));
        String status = filled.path("dataStatus").asText("NONE");
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            Sheet sheet = wb.createSheet("보고서");
            int r = 0;
            r = text(sheet, r, template.name() + " · " + year + "년 " + month + "월" + ("PROVISIONAL".equals(status) ? " (잠정)" : ""), st.title);
            r = text(sheet, r, "금액은 원 단위 값입니다(화면 표시는 억원 등으로 반올림). 계산할 수 없는 칸은 '데이터 없음'.", st.note) + 1;
            for (JsonNode block : blocks) {
                r = block(sheet, r, block, byId.get(block.path("id").asText()), st) + 1;
            }
            sheet.setColumnWidth(0, 30 * 256);
            for (int c = 1; c < 12; c++) {
                sheet.setColumnWidth(c, 16 * 256);
            }
            Sheet info = wb.createSheet("정보");
            int i = 0;
            for (String[] kv : new String[][] {{"자료 틀", template.name()}, {"틀 버전", String.valueOf(template.version())},
                    {"기준 월", year + "-" + String.format("%02d", month)}, {"데이터 상태", status},
                    {"만든 시각(KST)", clock.instant().atZone(KST).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}}) {
                Row row = info.createRow(i++);
                row.createCell(0).setCellValue(kv[0]);
                row.createCell(1).setCellValue(kv[1]);
            }
            info.setColumnWidth(0, 18 * 256);
            info.setColumnWidth(1, 40 * 256);
            wb.write(out);
            String name = template.name().replaceAll("[\\\\/:*?\"<>|]", "_");
            return new Export(name + "_" + year + "-" + String.format("%02d", month) + ".xlsx", out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 블록 하나: 제목 줄 + 내용(글자·표·SQL 표·숫자 카드·차트 값) */
    private int block(Sheet sheet, int r, JsonNode block, JsonNode filled, Styles st) {
        String label = firstText(block, "title", "label", "text");
        r = text(sheet, r, label != null && !label.isBlank() ? label : block.path("kind").asText() + " " + block.path("id").asText(), st.header);
        if (filled == null) {
            return text(sheet, r, "데이터 없음", st.missing);
        }
        if (filled.has("text") && !filled.has("rows") && !filled.has("table") && !filled.has("cards")) {
            String t = filled.path("text").asText("");
            return t.equals(label) ? r : text(sheet, r, t, null);
        }
        if (filled.path("table").isObject()) {                  // 확정 SQL 결과: 열 이름 + 값
            JsonNode table = filled.get("table");
            Row head = sheet.createRow(r++);
            int c = 0;
            for (JsonNode col : table.path("columns")) {
                styled(head.createCell(c++), col.asText(), st.bold);
            }
            for (JsonNode row : table.path("rows")) {
                Row x = sheet.createRow(r++);
                c = 0;
                for (JsonNode v : row) {
                    value(x.createCell(c++), v, st);
                }
            }
            return r;
        }
        if (filled.path("rows").isArray()) {                    // 표·지표 표·비용 상세·소명 목록·차트 값
            for (JsonNode row : filled.get("rows")) {
                Row x = sheet.createRow(r++);
                if (row.isArray()) {
                    int c = 0;
                    for (JsonNode cell : row) {
                        cell(x.createCell(c++), cell, st);
                    }
                } else {                                         // 차트 점 {name, won, text}
                    x.createCell(0).setCellValue(row.path("name").asText(""));
                    cell(x.createCell(1), row, st);
                }
            }
            return r;
        }
        if (filled.path("cards").isArray()) {                   // 숫자 카드
            for (JsonNode card : filled.get("cards")) {
                Row x = sheet.createRow(r++);
                x.createCell(0).setCellValue(card.path("label").asText(""));
                cell(x.createCell(1), card, st);
            }
            return r;
        }
        return text(sheet, r, "데이터 없음", st.missing);
    }

    /** 셀: won이 있으면 원 단위 숫자, 머리 칸은 굵게, 계산할 수 없으면 '데이터 없음' */
    private void cell(Cell target, JsonNode cell, Styles st) {
        if (cell.hasNonNull("won")) {
            try {
                target.setCellValue(new BigDecimal(cell.get("won").asText()).doubleValue());
                target.setCellStyle(st.won);
                return;
            } catch (NumberFormatException ignored) {
                // 글자로 넣는다
            }
        }
        String text = cell.path("text").asText(cell.isTextual() ? cell.asText() : "");
        if (cell.path("missing").asBoolean(false) && text.isBlank()) {
            text = "데이터 없음";
        }
        styled(target, text, cell.path("hdr").asBoolean(false) ? st.bold : null);
    }

    private void value(Cell target, JsonNode v, Styles st) {
        if (v.isNumber()) {
            target.setCellValue(v.decimalValue().doubleValue());
            target.setCellStyle(st.won);
        } else if (!v.isNull()) {
            target.setCellValue(trim(v.asText()));
        }
    }

    private static int text(Sheet sheet, int r, String text, CellStyle style) {
        styled(sheet.createRow(r).createCell(0), text, style);
        return r + 1;
    }

    private static void styled(Cell cell, String text, CellStyle style) {
        cell.setCellValue(trim(text));
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.length() > MAX_CELL_TEXT ? s.substring(0, MAX_CELL_TEXT) : s;
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String k : keys) {
            if (node.hasNonNull(k) && node.get(k).isTextual() && !node.get(k).asText().isBlank()) {
                return node.get(k).asText();
            }
        }
        return null;
    }

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "templateId 형식이 올바르지 않습니다.");
        }
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle bold;
        final CellStyle note;
        final CellStyle missing;
        final CellStyle won;

        Styles(XSSFWorkbook wb) {
            title = font(wb, true, (short) 14);
            header = font(wb, true, (short) 11);
            bold = font(wb, true, (short) 10);
            note = font(wb, false, (short) 9);
            missing = font(wb, false, (short) 10);
            won = wb.createCellStyle();
            won.setDataFormat(wb.createDataFormat().getFormat("#,##0;-#,##0"));
        }

        private static CellStyle font(XSSFWorkbook wb, boolean boldFont, short size) {
            CellStyle style = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(boldFont);
            f.setFontHeightInPoints(size);
            style.setFont(f);
            return style;
        }
    }
}
