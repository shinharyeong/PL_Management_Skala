package io.skala.backend.domain.report;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.report.dto.ExportRequest;
import io.skala.backend.domain.report.dto.ReportAiRequests;
import io.skala.backend.domain.report.dto.TemplateResponse;
import io.skala.backend.domain.report.service.ReportAiClient;
import io.skala.backend.domain.report.service.ReportExportService;
import io.skala.backend.domain.report.service.ReportMetricService;
import io.skala.backend.domain.report.service.ReportTemplateService;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.security.AuthenticatedUser;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** 서버 성공 관찰의 XLSX 렌더링을 디스크·DB·외부 API 없이 확인한다. */
class ReportFilledExportTest {
    /** 목적: 미리보기의 실제 값을 재조회 없이 쓰고 출처를 정보 시트에 남긴다.
     * Args: 없음. Returns: 없음. Raises: AssertionError/IOException.
     * Side Effects: 메모리 XLSX 생성·읽기만. */
    @Test void rendersObservedWonAndSourceWithoutCallingAiAgain() throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        ReportTemplateService templates=mock(ReportTemplateService.class);
        ReportMetricService metrics=mock(ReportMetricService.class);
        ReportAiClient ai=mock(ReportAiClient.class);
        Clock clock=Clock.fixed(Instant.parse("2026-11-27T00:00:00Z"),ZoneOffset.UTC);
        AuthenticatedUser user=new AuthenticatedUser(7L,UserRole.PLANNER,null);
        JsonNode definition=mapper.valueToTree(Map.of("blocks",List.of(Map.of("id","b1","kind","metric_table","title","성과"))));
        when(templates.get(user,5L,2)).thenReturn(new TemplateResponse("5","보고서",2,2,definition,
                OffsetDateTime.now(clock),OffsetDateTime.now(clock)));
        JsonNode filled=mapper.valueToTree(Map.of("dataStatus","CONFIRMED","masterId","3","sourceVersion","master-2026-11",
                "capturedAt","2026-11-27T00:00:00Z","blocks",List.of(Map.of("id","b1","rows",List.of(
                        List.of(Map.of("text","영업이익"),Map.of("text","0.0","won","123.125")))))));
        var service=new ReportExportService(templates,metrics,ai,clock);
        var request=new ExportRequest("5",2,new ReportAiRequests.Base(2026,11),null,"XLSX");
        var file=service.exportFilled(user,request,filled);
        try (var wb=new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            boolean amountFound=false,masterFound=false;
            for (var row:wb.getSheetAt(0)) for (var cell:row)
                if (cell.getCellType()==CellType.STRING && cell.getStringCellValue().equals("123.125")
                        && new DataFormatter().formatCellValue(cell).equals("123.125")) amountFound=true;
            for (var row:wb.getSheet("정보"))
                if (row.getCell(0).getStringCellValue().equals("기준 마스터") && row.getCell(1).getStringCellValue().equals("3")) masterFound=true;
            assertThat(amountFound).isTrue(); assertThat(masterFound).isTrue();
        }
        verifyNoInteractions(metrics,ai);
        assertThatThrownBy(() -> service.exportFilled(user,request,mapper.valueToTree(Map.of("blocks",List.of(Map.of("id","different"))))))
                .isInstanceOf(BusinessException.class);
    }

    /** 목적: 기존 내보내기와 관찰 재사용 모두 소수·큰 정수의 셀 종류와 표시 원문을 보존한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError/IOException.
     * Side Effects: mock 데이터 API와 메모리 workbook만 사용하고 DB·파일을 만들지 않는다. */
    @Test void preservesDecimalAndLargeValuesAcrossAllCellPaths() throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        ReportTemplateService templates=mock(ReportTemplateService.class);
        ReportMetricService metrics=mock(ReportMetricService.class);
        ReportAiClient ai=mock(ReportAiClient.class);
        Clock clock=Clock.fixed(Instant.parse("2026-11-27T00:00:00Z"),ZoneOffset.UTC);
        AuthenticatedUser user=new AuthenticatedUser(7L,UserRole.PLANNER,null);
        JsonNode definition=mapper.readTree("""
                {"blocks":[{"id":"rows","kind":"metric_table"},{"id":"chart","kind":"chart"},
                  {"id":"cards","kind":"numbercard"},{"id":"sql","kind":"table"}]}
                """);
        JsonNode filled=mapper.readTree("""
                {"dataStatus":"CONFIRMED","blocks":[
                  {"id":"rows","rows":[
                    [{"text":"decimal"},{"won":"123.125","text":"123"}],
                    [{"text":"negativeDecimal"},{"won":"-0.5000"}],
                    [{"text":"safeInteger"},{"won":"999999999999999"}],
                    [{"text":"largeInteger"},{"won":"1000000000000001"}],
                    [{"text":"preciseAmount"},{"won":"-12345678901234567890.123456789"}]]},
                  {"id":"chart","rows":[{"name":"chartDecimal","won":"17.2500"}]},
                  {"id":"cards","cards":[{"label":"cardLarge","won":"12345678901234567890"}]},
                  {"id":"sql","table":{"columns":["label","value"],"rows":[
                    ["sqlDecimal",123.125],["sqlLarge",12345678901234567890],
                    ["sqlSafe",-999999999999999],["sqlString","00123.1250"],["sqlFormulaText","=1+1"]]}}
                ]}
                """);
        when(templates.get(user,5L,2)).thenReturn(new TemplateResponse("5","보고서",2,2,definition,
                OffsetDateTime.now(clock),OffsetDateTime.now(clock)));
        when(metrics.resolveMetrics(any())).thenReturn(List.of(definition.path("blocks").get(0),definition.path("blocks").get(1),
                definition.path("blocks").get(2),definition.path("blocks").get(3)));
        when(ai.post(eq("/v1/data"),any(),eq(user.id()))).thenReturn(filled);
        var service=new ReportExportService(templates,metrics,ai,clock);
        var request=new ExportRequest("5",2,new ReportAiRequests.Base(2026,11),null,"XLSX");
        for (var file:List.of(service.exportFilled(user,request,filled),service.export(user,request))) {
            try (var wb=new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
                assertRenderedValue(wb,"decimal",CellType.STRING,"123.125");
                assertRenderedValue(wb,"negativeDecimal",CellType.STRING,"-0.5000");
                assertRenderedValue(wb,"safeInteger",CellType.NUMERIC,"999,999,999,999,999");
                assertRenderedValue(wb,"largeInteger",CellType.STRING,"1000000000000001");
                assertRenderedValue(wb,"preciseAmount",CellType.STRING,"-12345678901234567890.123456789");
                assertRenderedValue(wb,"chartDecimal",CellType.STRING,"17.2500");
                assertRenderedValue(wb,"cardLarge",CellType.STRING,"12345678901234567890");
                assertRenderedValue(wb,"sqlDecimal",CellType.STRING,"123.125");
                assertRenderedValue(wb,"sqlLarge",CellType.STRING,"12345678901234567890");
                assertRenderedValue(wb,"sqlSafe",CellType.NUMERIC,"-999,999,999,999,999");
                assertRenderedValue(wb,"sqlString",CellType.STRING,"00123.1250");
                assertRenderedValue(wb,"sqlFormulaText",CellType.STRING,"=1+1");
            }
        }
        verify(ai,times(1)).post(eq("/v1/data"),any(),eq(user.id()));
    }

    /** 목적: XLSX를 다시 연 실제 셀 종류와 Excel 표시값이 원문 정책과 일치하는지 확인한다.
     * Args: wb 메모리 workbook, label 행 이름, type 기대 셀 종류, expected 표시 원문.
     * Returns: 없음. Raises: AssertionError 누락 또는 값 불일치.
     * Side Effects: workbook 읽기만 수행한다. */
    private void assertRenderedValue(XSSFWorkbook wb,String label,CellType type,String expected) {
        for (var row:wb.getSheet("보고서")) {
            var first=row.getCell(0);
            if (first!=null && first.getCellType()==CellType.STRING && label.equals(first.getStringCellValue())) {
                var cell=row.getCell(1);
                assertThat(cell.getCellType()).as(label).isEqualTo(type);
                assertThat(new DataFormatter(java.util.Locale.US).formatCellValue(cell)).as(label).isEqualTo(expected);
                if (type==CellType.STRING) assertThat(cell.getStringCellValue()).as(label).isEqualTo(expected);
                return;
            }
        }
        fail("검증 행이 없습니다: "+label);
    }
}
