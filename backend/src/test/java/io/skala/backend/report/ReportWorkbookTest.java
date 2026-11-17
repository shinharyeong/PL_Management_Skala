package io.skala.backend.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** 실제 내보내기 형식·정밀도·텍스트 보존을 파일 생성 없이 확인한다. */
class ReportWorkbookTest {
    /** XLSX 관계와 각 XML·금액 셀을 확인한다.
     * Args: 없음. Returns: 없음. Raises: ZIP/XML/검증 오류. Side Effects: 메모리 ZIP만 생성한다.
     */
    @Test
    void workbookPreservesMoneyAndHasValidLinkedSheets() throws Exception {
        var values = List.of(Map.of("reportItemId", "10", "amount", "-123.450000"),
                Map.of("reportItemId", "11", "amount", "1234567890123456.123456"));
        var report = Map.<String,Object>of("periods", List.of(Map.of("masterId", "900", "year", 2026, "month", 1,
                "totalRows", values, "organizations", List.of(Map.of("organizationId", "1", "rows", values)))));
        var documents = documents(ReportWorkbook.write(report));
        assertThat(documents).hasSize(9).containsKeys("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels");
        assertThat(documents.get("xl/workbook.xml").getElementsByTagNameNS("*", "sheet").getLength()).isEqualTo(5);
        var relations = documents.get("xl/_rels/workbook.xml.rels").getElementsByTagNameNS("*", "Relationship");
        for (int i = 0; i < relations.getLength(); i++)
            assertThat(documents).containsKey("xl/" + ((Element)relations.item(i)).getAttribute("Target"));
        Document totals = documents.get("xl/worksheets/sheet1.xml");
        assertThat(cell(totals, "H2").getTextContent()).isEqualTo("-123.450000");
        assertThat(cell(totals, "H2").getAttribute("t")).isEmpty();
        assertThat(cell(totals, "H3").getTextContent()).isEqualTo("1234567890123456.123456");
        assertThat(cell(totals, "H3").getAttribute("t")).isEqualTo("inlineStr");
        assertThat(cell(documents.get("xl/worksheets/sheet2.xml"), "I3").getTextContent()).isEqualTo("1234567890123456.123456");
    }

    /** 소명 원문이 수식으로 실행되지 않으며 XML 제어문자만 표시 대체됨을 확인한다.
     * Args: 없음. Returns: 없음. Raises: ZIP/XML/검증 오류. Side Effects: 메모리만 사용한다.
     */
    @Test
    void explanationIsInertTextAndXmlIsEscaped() throws Exception {
        String explanation = "=HYPERLINK(\"https://example.test/?a=1&b=2\")\n<근거> 😀\u0001";
        var report = Map.<String,Object>of("explanations", List.of(Map.of("answer", explanation)));
        var documents = documents(ReportWorkbook.write(report));
        Element answer = cell(documents.get("xl/worksheets/sheet3.xml"), "I2");
        assertThat(answer.getAttribute("t")).isEqualTo("inlineStr");
        assertThat(answer.getTextContent()).isEqualTo(explanation.replace('\u0001', '\uFFFD'));
        for (Document document : documents.values())
            assertThat(document.getElementsByTagNameNS("*", "f").getLength()).isZero();
        assertThat(((Map<?,?>)((List<?>)report.get("explanations")).getFirst()).get("answer")).isEqualTo(explanation);
    }

    /** ZIP의 모든 XML 구성원을 안전한 메모리 DOM으로 읽는다.
     * Args: bytes XLSX 바이트. Returns: 구성원 경로별 DOM. Raises: ZIP/XML 오류. Side Effects: 외부 엔티티·파일 쓰기를 사용하지 않는다.
     */
    private Map<String,Document> documents(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var result = new LinkedHashMap<String,Document>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                result.put(entry.getName(), factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes())));
        }
        return result;
    }

    /** A1 주소의 셀을 찾아 셀 유형과 원문을 검사할 수 있게 한다.
     * Args: document 워크시트, address 주소. Returns: 셀. Raises: AssertionError 셀 없음. Side Effects: 없음.
     */
    private Element cell(Document document, String address) {
        var cells = document.getElementsByTagNameNS("*", "c");
        for (int i = 0; i < cells.getLength(); i++) if (address.equals(((Element)cells.item(i)).getAttribute("r"))) return (Element)cells.item(i);
        throw new AssertionError("없는 셀: " + address);
    }
}
