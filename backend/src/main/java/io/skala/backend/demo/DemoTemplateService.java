package io.skala.backend.demo;

import io.skala.backend.common.ApiException;
import io.skala.backend.security.Actor;
import io.skala.backend.submission.SubmissionService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 합성 연간 매출·비용 양식을 메모리 OOXML로 생성하며 원본 Data에는 접근하지 않는다. */
@Service
@Profile("local & demo")
public class DemoTemplateService {
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private final SubmissionService submissions;

    /** 요청 권한 조회 서비스를 받는다.
     * Args: submissions 업무 서비스. Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public DemoTemplateService(SubmissionService submissions) { this.submissions = submissions; }

    /** 본인 조직의 연간 요청에 맞는 12개월 합성 양식을 반환한다.
     * Args: actor 조직 담당자, requestId 접근할 연간 요청.
     * Returns: 요청 파일명과 XLSX 바이트. Raises: 권한/404/미지원 요청/ZIP 구성 오류.
     * Side Effects: 요청 DB 조회, 메모리 ZIP 생성; 디스크·Data·DB 쓰기 없음.
     */
    public SubmissionService.OriginalFile generate(Actor actor, String requestId) {
        actor.requireRole("ORG_MANAGER");
        var request = submissions.request(actor, requestId);
        if (!"ANNUAL_PLAN".equals(request.get("type"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEMO_TEMPLATE_UNSUPPORTED_REQUEST",
                    "체험 양식은 연간 계획 요청을 지원합니다.");
        }
        int year = ((Number) request.get("year")).intValue();
        return new SubmissionService.OriginalFile(actor.organization().code() + "_" + year + "-PLAN.xlsx",
                workbook(actor, year));
    }

    /** 재무 상세·코드표·집계 근거를 한 메모리 ZIP으로 구성한다.
     * Args: actor 조직 정보, year 요청 연도. Returns: OOXML 바이트.
     * Raises: IllegalStateException 메모리 ZIP 실패. Side Effects: 메모리만 사용; 파일 생성 없음.
     */
    private byte[] workbook(Actor actor, int year) {
        String[] names = {"매출", "비용", "조직코드", "예산코드", "집계근거"};
        var entries = new LinkedHashMap<String, String>();
        entries.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + worksheetTypes(names.length) + "</Types>");
        entries.put("_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"book\" Type=\"" + REL + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        var book = new StringBuilder("<workbook xmlns=\"").append(NS).append("\" xmlns:r=\"").append(REL).append("\"><sheets>");
        var relations = new StringBuilder("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= names.length; i++) {
            book.append("<sheet name=\"").append(names[i - 1]).append("\" sheetId=\"").append(i).append("\" r:id=\"r").append(i).append("\"/>");
            relations.append("<Relationship Id=\"r").append(i).append("\" Type=\"").append(REL)
                    .append("/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        entries.put("xl/workbook.xml", book.append("</sheets><calcPr calcMode=\"auto\"/></workbook>").toString());
        entries.put("xl/_rels/workbook.xml.rels", relations.append("</Relationships>").toString());
        entries.put("xl/worksheets/sheet1.xml", financialSheet(actor.organization().name(), year, true));
        entries.put("xl/worksheets/sheet2.xml", financialSheet(actor.organization().name(), year, false));
        entries.put("xl/worksheets/sheet3.xml", worksheet("<row r=\"1\">" + text("A1", "사업부코드") + text("B1", "사업부명")
                + "</row><row r=\"2\">" + text("A2", actor.organization().code()) + text("B2", actor.organization().name()) + "</row>"));
        entries.put("xl/worksheets/sheet4.xml", worksheet("<row r=\"1\">" + text("A1", "예산코드") + text("B1", "예산명칭")
                + text("C1", "실적집계기준") + "</row><row r=\"2\">" + text("A2", "77001") + text("B2", "사용료") + text("C2", "사용료") + "</row>"));
        String formula = "SUMIFS('매출'!F:F,'매출'!$B:$B,\"서비스매출\",'매출'!$A:$A,\""
                + actor.organization().name().replace("\"", "\"\"") + "\")";
        entries.put("xl/worksheets/sheet5.xml", worksheet("<row r=\"1\">" + text("A1", "합성 집계 근거 · 승인 재무값 아님")
                + "</row><row r=\"2\"><c r=\"A2\"><f>" + xml(formula) + "</f><v>1000000.50</v></c></row>"));
        try (var buffer = new ByteArrayOutputStream(); var zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                ZipEntry member = new ZipEntry(entry.getKey());
                member.setTime(0);
                zip.putNextEntry(member);
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return buffer.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("체험 양식을 메모리에서 구성하지 못했습니다.");
        }
    }

    /** 표준 worksheet ContentType 선언을 만든다.
     * Args: count 시트 수. Returns: XML 조각. Raises: 없음. Side Effects: 없음.
     */
    private String worksheetTypes(int count) {
        var result = new StringBuilder();
        for (int i = 1; i <= count; i++) result.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        return result.toString();
    }

    /** 파서가 지원하는 다단 헤더와 12개월 PLAN 상세 한 행을 만든다.
     * Args: organization 조직명, year 연도, revenue 매출이면 true.
     * Returns: worksheet XML. Raises: 없음. Side Effects: 합성 숫자를 메모리에서 구성.
     */
    private String financialSheet(String organization, int year, boolean revenue) {
        String[] headers = revenue ? new String[]{"조직명", "관리분류", "중분류", "소분류", "거래내역 및 산출근거"}
                : new String[]{"대분류", "중분류", "소분류", "조직명", "예산Code", "예산명칭", "거래내역 및 산출근거", "실적집계기준"};
        String[] values = revenue ? new String[]{organization, "서비스매출", "3100 서비스", "기본 이용료", "체험용 합성 근거 [ID:demo-revenue-001; 수정:1]"}
                : new String[]{"운영비", "시설", "임차", organization, "77001", "사용료", "체험용 합성 근거 [ID:demo-expense-001; 수정:1]", "사용료"};
        var rows = new StringBuilder("<row r=\"1\">").append(text("A1", "단위: 원")).append("</row><row r=\"2\">")
                .append(text("A2", "체험용 합성 값 · 실제 계획 아님")).append("</row><row r=\"3\">");
        if (revenue) {
            for (int i = 0; i < headers.length; i++) rows.append(text(column(i + 1) + "3", headers[i]));
        }
        for (int month = 1; month <= 12; month++) rows.append(text(column(headers.length + month) + "3", year + "년 계획"));
        rows.append("</row><row r=\"4\">");
        if (!revenue) {
            for (int i = 0; i < headers.length; i++) rows.append(text(column(i + 1) + "4", headers[i]));
        }
        for (int month = 1; month <= 12; month++) rows.append(text(column(headers.length + month) + "4", month + "월"));
        rows.append("</row><row r=\"5\">");
        for (int i = 0; i < values.length; i++) rows.append(text(column(i + 1) + "5", values[i]));
        for (int month = 1; month <= 12; month++) {
            String amount = month == 2 ? "0" : revenue ? "1000000.50" : "300000.25";
            rows.append("<c r=\"").append(column(headers.length + month)).append("5\"><v>").append(amount).append("</v></c>");
        }
        return worksheet(rows.append("</row>").toString());
    }

    /** 행 XML을 worksheet로 감싼다.
     * Args: rows 행 XML. Returns: worksheet. Raises: 없음. Side Effects: 없음.
     */
    private String worksheet(String rows) { return "<worksheet xmlns=\"" + NS + "\"><sheetData>" + rows + "</sheetData></worksheet>"; }

    /** 문자열 셀을 XML 이스케이프하여 만든다.
     * Args: address A1 위치, value 문자열. Returns: inlineStr 셀 XML. Raises: 없음. Side Effects: 없음.
     */
    private String text(String address, String value) { return "<c r=\"" + address + "\" t=\"inlineStr\"><is><t>" + xml(value) + "</t></is></c>"; }

    /** 고정 양식의 1~26열을 A1 열 문자로 바꾼다.
     * Args: index 양식 내 열 번호. Returns: 열 문자. Raises: IllegalArgumentException 범위 밖. Side Effects: 없음.
     */
    private String column(int index) {
        if (index < 1 || index > 26) throw new IllegalArgumentException("체험 양식 열 범위를 벗어났습니다.");
        return Character.toString((char) ('A' + index - 1));
    }

    /** 사용자 조직 문자열과 수식의 XML 특수 문자를 이스케이프한다.
     * Args: value 원문. Returns: XML 텍스트. Raises: 없음. Side Effects: 없음.
     */
    private String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
}
