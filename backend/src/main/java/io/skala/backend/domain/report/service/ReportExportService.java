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
 * 원 금액은 15자리 이하 정수만 숫자로 기록하고 소수·큰 정수는 정밀 원문 문자열을 보존한다.
 * won이 없으면 기존 화면 글자를 사용하며 PNG·PDF는 화면(브라우저)에서 만든다.
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

    /** 목적: 기존 보고서 조회를 채워 XLSX를 반환한다. Args: user 기획자, request 저장 틀·기간.
     * Returns: 메모리 XLSX. Raises: 권한/자료/외부 조회 오류.
     * Side Effects: DB 읽기·기존 AI 데이터 API 호출만, 파일·업무 저장 없음. */
    public Export export(AuthenticatedUser user, ExportRequest request) {
        TemplateResponse template = templates.get(user, parseId(request.templateId()), request.version());
        List<JsonNode> blocks = selectedBlocks(template,request);
        int year = request.period().year();
        int month = request.period().month();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("base", Map.of("year", year, "month", month));
        body.put("blocks", metrics.resolveMetrics(blocks));
        JsonNode filled = reportAi.post("/v1/data", body, user.id());
        return render(template,request,blocks,filled);
    }

    /** 목적: 서버가 검증한 성공 관찰을 다시 조회하지 않고 같은 XLSX 렌더러에 전달한다.
     * Args: user 기획자, request 실제 조회된 저장 틀 버전·블록·기간, filled 서버의 성공 데이터 관찰.
     * Returns: 미리보기와 같은 채운 값의 메모리 XLSX.
     * Raises: 권한/틀/입력 불일치 또는 렌더링 오류.
     * Side Effects: 저장 틀 DB 읽기와 메모리 렌더링만. 공개 HTTP 입력으로 filled를 받지 않는다. */
    public Export exportFilled(AuthenticatedUser user, ExportRequest request, JsonNode filled) {
        TemplateResponse template = templates.get(user,parseId(request.templateId()),request.version());
        List<JsonNode> blocks = selectedBlocks(template,request);
        if (filled == null || !filled.path("blocks").isArray() || filled.path("blocks").size()!=blocks.size())
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"채운 보고서 블록이 자료 틀과 다릅니다.");
        for (int i=0;i<blocks.size();i++) {
            if (!blocks.get(i).path("id").equals(filled.path("blocks").get(i).path("id")))
                throw new BusinessException(ErrorCode.INVALID_REQUEST,"채운 보고서의 블록 순서가 다릅니다.");
        }
        return render(template,request,blocks,filled);
    }

    /** 목적: 기존 자료 틀에서 요청한 블록만 순서대로 선택한다. Args: template 저장 틀, request 블록 선택.
     * Returns: 실제 블록 목록. Raises: 미존재 404. Side Effects: 없음. */
    private List<JsonNode> selectedBlocks(TemplateResponse template, ExportRequest request) {
        List<JsonNode> blocks = new ArrayList<>();
        for (JsonNode block : template.definition().path("blocks")) {
            if (request.blockId() == null || request.blockId().equals(block.path("id").asText())) {
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "내보낼 블록이 없습니다.");
        }
        return blocks;
    }

    /** 목적: 기존 보고서 값·서식 규칙으로 workbook을 메모리에 만든다.
     * Args: template 저장 틀, request 기간, blocks 선택 블록, filled 검증된 채운 값.
     * Returns: 파일명·XLSX 바이트. Raises: UncheckedIOException 생성 오류.
     * Side Effects: 메모리만 사용하고 파일 저장·업무 변경 없음. */
    private Export render(TemplateResponse template, ExportRequest request, List<JsonNode> blocks, JsonNode filled) {
        int year = request.period().year();
        int month = request.period().month();
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
            if (filled.hasNonNull("masterId")) {
                for (String[] kv : new String[][] {{"기준 마스터",filled.path("masterId").asText()},
                        {"보고서 조회 버전",filled.path("sourceVersion").asText()},
                        {"데이터 조회 시각",filled.path("capturedAt").asText()},
                        {"비교 기준", "계획·전월 비교값과 소명은 조회 시점 기준"}}) {
                    Row row=info.createRow(i++); row.createCell(0).setCellValue(kv[0]); row.createCell(1).setCellValue(kv[1]);
                }
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

    /** 목적: 원 금액의 정밀도를 보존하고 결측·머리 칸의 기존 표시를 유지한다.
     * Args: target Excel 셀, cell 채운 보고서 셀, st 서식.
     * Returns: 없음. Raises: POI 셀 기록 오류.
     * Side Effects: 메모리 workbook만 변경하며 소수·큰 정수는 원문 문자열로 기록한다. */
    private void cell(Cell target, JsonNode cell, Styles st) {
        if (cell.hasNonNull("won")) {
            try {
                writeNumber(target,cell.get("won").asText(),st);
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

    /** 목적: SQL 표의 실제 숫자는 정밀하게 기록하고 기존 문자열은 그대로 유지한다.
     * Args: target Excel 셀, v 원문 JSON 값, st 서식.
     * Returns: 없음. Raises: 숫자 변환 또는 POI 기록 오류.
     * Side Effects: 메모리 셀만 변경하며 숫자처럼 보이는 SQL 문자열을 숫자로 바꾸지 않는다. */
    private void value(Cell target, JsonNode v, Styles st) {
        if (v.isNumber()) {
            writeNumber(target,v.asText(),st);
        } else if (!v.isNull()) {
            target.setCellValue(trim(v.asText()));
        }
    }

    /** 목적: Excel 숫자 정밀도·정수 서식 때문에 원문 수치가 변하지 않게 기록한다.
     * Args: target Excel 셀, source 원문 숫자 문자열, st 정수 원 단위 서식.
     * Returns: 없음. Raises: NumberFormatException 숫자 형식 오류 또는 POI 기록 오류.
     * Side Effects: 15자리 이하 정수만 숫자 셀로, 소수·큰 정수는 원문 문자열 셀로 메모리에 기록한다. */
    private void writeNumber(Cell target, String source, Styles st) {
        BigDecimal number=new BigDecimal(source);
        long integerDigits=(long) number.precision()-number.scale();
        if (number.scale()<=0 && integerDigits<=15) {
            target.setCellValue(number.doubleValue());
            target.setCellStyle(st.won);
        } else {
            target.setCellValue(trim(source));
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
