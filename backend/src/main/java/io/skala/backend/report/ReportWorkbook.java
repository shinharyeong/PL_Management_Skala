package io.skala.backend.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 검증된 보고 JSON의 표와 출처를 추가 의존성 없이 메모리 XLSX로 변환한다. */
public final class ReportWorkbook {
    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    /** 정적 변환기 인스턴스 생성을 제한한다.
     * Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    private ReportWorkbook() {}

    /** 원 단위 보고값·소명·고정 출처의 독립 시트를 만든다.
     * Args: report ReportService가 반환한 실제 보고 데이터.
     * Returns: XLSX ZIP 바이트. Raises: IllegalStateException 메모리 ZIP 오류.
     * Side Effects: 메모리만 사용하며 파일·캐시를 생성하지 않는다. Excel 15자리 정밀도를 넘는 금액은 텍스트로 보존한다.
     */
    public static byte[] write(Map<String,Object> report) {
        var sheets = new LinkedHashMap<String,List<List<Object>>>();
        var totals = rows("마스터 ID", "연도", "월", "항목 ID", "항목", "구분", "값 상태", "금액(원)");
        var organizations = rows("마스터 ID", "연도", "월", "조직 ID", "항목 ID", "항목", "구분", "값 상태", "금액(원)");
        var explanations = rows("마스터 ID", "제출 ID", "조직 ID", "항목 ID", "연도", "월", "소명 ID", "회차", "수용 소명", "답변 시각", "수용 시각");
        var sources = rows("마스터 ID", "연도", "월", "조직 ID", "조직 코드", "조직명", "제출 ID", "제출 버전", "확정 계획 제출 ID");
        var metadata = rows("항목", "값");
        metadata.add(row("생성 시각", report.get("generatedAt")));
        metadata.add(row("금액 단위", "원 · 15자리 초과 금액은 정밀도 보존을 위해 텍스트로 표시"));
        for (var version : maps(report.get("calculationVersions"))) metadata.add(row("마스터 " + version.get("masterId") + " 계산 버전", version.get("version")));
        for (var c : maps(report.get("annualContingencies"))) metadata.add(row(c.get("year") + "년 전사 Contingency(손익에서 차감하지 않음)", money(c.get("amount"))));
        for (var period : maps(report.get("periods"))) {
            for (var value : maps(period.get("totalRows"))) totals.add(row(period.get("masterId"), period.get("year"), period.get("month"), value.get("reportItemId"), value.get("reportItemName"), value.get("kind"), value.get("valueStatus"), money(value.get("amount"))));
            for (var organization : maps(period.get("organizations"))) for (var value : maps(organization.get("rows")))
                organizations.add(row(period.get("masterId"), period.get("year"), period.get("month"), organization.get("organizationId"), value.get("reportItemId"), value.get("reportItemName"), value.get("kind"), value.get("valueStatus"), money(value.get("amount"))));
            for (var source : maps(period.get("sources"))) sources.add(row(period.get("masterId"), period.get("year"), period.get("month"), source.get("organizationId"), source.get("organizationCode"), source.get("organizationName"), source.get("submissionId"), source.get("versionLabel"), source.get("planSubmissionId")));
        }
        for (var e : maps(report.get("explanations"))) explanations.add(row(e.get("masterId"), e.get("submissionId"), e.get("organizationId"), e.get("reportItemId"), e.get("year"), e.get("month"), e.get("explanationId"), e.get("round"), e.get("answer"), e.get("answeredAt"), e.get("reviewedAt")));
        sheets.put("전사 손익", totals); sheets.put("조직별 손익", organizations); sheets.put("수용 소명", explanations); sheets.put("고정 출처", sources); sheets.put("보고 정보", metadata);
        return tables(sheets);
    }

    /** 검증된 업무 표를 재사용 가능한 메모리 XLSX로 만든다.
     * Args: sheets 내부에서 정한 안전한 시트명과 셀 행들. Returns: XLSX 바이트.
     * Raises: IllegalStateException ZIP 구성 실패. Side Effects: 메모리만 사용하며 문자열을 수식으로 실행하지 않는다.
     */
    public static byte[] tables(Map<String,List<List<Object>>> sheets) {
        var entries = new LinkedHashMap<String,String>();
        var types = new StringBuilder("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
        var workbook = new StringBuilder("<workbook xmlns=\"").append(MAIN).append("\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        var relations = new StringBuilder("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        int index = 0;
        for (var sheet : sheets.entrySet()) {
            index++;
            workbook.append("<sheet name=\"").append(sheet.getKey()).append("\" sheetId=\"").append(index).append("\" r:id=\"rId").append(index).append("\"/>");
            relations.append("<Relationship Id=\"rId").append(index).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(index).append(".xml\"/>");
            types.append("<Override PartName=\"/xl/worksheets/sheet").append(index).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            entries.put("xl/worksheets/sheet" + index + ".xml", worksheet(sheet.getValue()));
        }
        entries.put("[Content_Types].xml", types.append("</Types>").toString());
        entries.put("xl/workbook.xml", workbook.append("</sheets></workbook>").toString());
        entries.put("xl/_rels/workbook.xml.rels", relations.append("</Relationships>").toString());
        entries.put("_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        try (var buffer = new ByteArrayOutputStream(); var zip = new ZipOutputStream(buffer)) {
            for (var entry : entries.entrySet()) {
                var member = new ZipEntry(entry.getKey()); member.setTime(0); zip.putNextEntry(member);
                zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + entry.getValue()).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
            zip.finish(); return buffer.toByteArray();
        } catch (IOException error) { throw new IllegalStateException("보고서를 메모리에서 구성하지 못했습니다."); }
    }

    /** 내부 보고 배열을 읽는다. Args: value 배열/null. Returns: Map 목록. Raises: 계약 위반 ClassCastException. Side Effects: 없음. */
    private static List<Map<String,Object>> maps(Object value) { return value == null ? List.of() : (List<Map<String,Object>>) value; }

    /** 헤더가 있는 표를 만든다. Args: headers 열 제목. Returns: 변경 가능한 행 목록. Raises: 없음. Side Effects: 없음. */
    private static List<List<Object>> rows(String... headers) { var rows = new ArrayList<List<Object>>(); rows.add(row((Object[])headers)); return rows; }

    /** nullable 값을 가진 한 행을 만든다. Args: cells 셀 값. Returns: 행. Raises: 없음. Side Effects: 없음. */
    private static List<Object> row(Object... cells) { return Arrays.asList(cells); }

    /** 확인된 금액 문자열만 정확한 십진수로 변환한다. Args: value 십진 문자열/null. Returns: Decimal/null. Raises: 잘못된 내부 금액. Side Effects: 없음. */
    private static BigDecimal money(Object value) { return value == null ? null : new BigDecimal(value.toString()); }

    /** 표를 원문 텍스트·정밀 금액 셀로 직렬화한다.
     * Args: rows 표. Returns: worksheet XML. Raises: 없음. Side Effects: 없음; 문자열을 Excel 수식으로 실행하지 않는다.
     */
    private static String worksheet(List<List<Object>> rows) {
        var xml = new StringBuilder("<worksheet xmlns=\"").append(MAIN).append("\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            xml.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < rows.get(r).size(); c++) {
                Object value = rows.get(r).get(c); String address = column(c + 1) + (r + 1);
                if (value instanceof BigDecimal amount && amount.stripTrailingZeros().precision() <= 15)
                    xml.append("<c r=\"").append(address).append("\"><v>").append(amount.toPlainString()).append("</v></c>");
                else xml.append("<c r=\"").append(address).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(escape(value == null ? "" : value.toString())).append("</t></is></c>");
            }
            xml.append("</row>");
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    /** 열 번호를 A1 주소의 문자 부분으로 만든다. Args: number 1 이상 열번호. Returns: 열문자. Raises: 없음. Side Effects: 없음. */
    private static String column(int number) { var text = new StringBuilder(); while (number > 0) { number--; text.insert(0, (char)('A' + number % 26)); number /= 26; } return text.toString(); }

    /** XML에 허용되지 않는 표시 문자를 대체하고 텍스트를 이스케이프한다.
     * Args: value 표시 문자열. Returns: 안전한 XML 텍스트. Raises: 없음. Side Effects: 원본을 변경하지 않으며 내보내기 표시만 정리한다.
     */
    private static String escape(String value) {
        var clean = new StringBuilder();
        value.codePoints().forEach(cp -> clean.appendCodePoint(cp == 9 || cp == 10 || cp == 13 || cp >= 32 && cp <= 0xD7FF || cp >= 0xE000 && cp <= 0xFFFD || cp >= 0x10000 && cp <= 0x10FFFF ? cp : 0xFFFD));
        return clean.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
