package io.skala.backend.workspace;

import io.skala.backend.report.ReportService;
import io.skala.backend.report.ReportWorkbook;
import io.skala.backend.review.ReviewService;
import io.skala.backend.security.ActorResolver;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 공식 마스터와 명시한 두 버전 비교를 사용자 요청 시 메모리 Excel로 내려준다. */
@RestController
@RequestMapping("/api/v1")
public class WorkspaceExportController {
    private final ReportService reports;
    private final ReviewService reviews;
    private final ActorResolver actors;
    /** 경계를 연결한다. Args: reports 고정마스터, reviews 비교, actors 인증. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public WorkspaceExportController(ReportService reports,ReviewService reviews,ActorResolver actors) { this.reports=reports;this.reviews=reviews;this.actors=actors; }

    /** 한 월 마스터의 금액·소명·조직 출처를 내려준다. Args: masterId 고정버전. Returns: Excel첨부. Raises: 권한·버전오류. Side Effects: DB 읽기·메모리 ZIP. */
    @GetMapping("/masters/{masterId}/file") public ResponseEntity<byte[]> master(@PathVariable String masterId) {
        return file(ReportWorkbook.write(reports.preview(actors.current(),new ReportService.Input(List.of(masterId),true))),"master-"+masterId+".xlsx");
    }

    /** 같은 조직·연도의 두 검증 버전을 그대로 비교해 내보낸다.
     * Args: submissionId 현재본,query 비교 기준·월·시나리오. Returns: 금액 비교와 기준표시 Excel.
     * Raises: 권한·기준 누락·검증 오류. Side Effects: DB 읽기·메모리 ZIP; 원본 파일은 변경하지 않는다.
     */
    @GetMapping("/submissions/{submissionId}/comparison/file") public ResponseEntity<byte[]> comparison(@PathVariable String submissionId,@RequestParam Map<String,String> query) {
        var data=reviews.compareSubmissions(actors.current(),submissionId,query);
        var rows=new ArrayList<List<Object>>();rows.add(Arrays.asList("항목 코드","항목","연도","월","비교 시나리오","현재 시나리오","비교 금액(원)","현재 금액(원)","차액(원)","차이율(%)","값 상태"));
        for (var item:(List<Map<String,Object>>)data.get("items")) {
            var report=(Map<String,Object>)item.get("reportItem");
            rows.add(Arrays.asList(report.get("code"),report.get("name"),item.get("year"),item.get("month"),item.get("baselineScenario"),item.get("scenario"),money(item.get("baselineAmount")),money(item.get("currentAmount")),money(item.get("gapAmount")),money(item.get("gapRate")),item.get("valueStatus")));
        }
        var meta=new ArrayList<List<Object>>();meta.add(Arrays.asList("출처","값"));
        for (String key:List.of("submissionId","baselineSubmissionId","organizationId","year","month","scenario","baselineScenario")) meta.add(Arrays.asList(key,data.get(key)));
        var sheets=new LinkedHashMap<String,List<List<Object>>>();sheets.put("버전 비교",rows);sheets.put("비교 출처",meta);
        return file(ReportWorkbook.tables(sheets),"comparison-"+submissionId+".xlsx");
    }
    /** 선택 금액을 정확한 십진수로 만든다. Args: value 금액/null. Returns: BigDecimal/null. Raises: 내부 형식 오류. Side Effects: 없음. */
    private BigDecimal money(Object value) { return value==null?null:new BigDecimal(value.toString()); }
    /** XLSX 첨부 응답을 만든다. Args: bytes 메모리파일,filename 내부 이름. Returns: 응답. Raises: 없음. Side Effects: 없음. */
    private ResponseEntity<byte[]> file(byte[] bytes,String filename) { return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+filename).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(bytes); }
}
