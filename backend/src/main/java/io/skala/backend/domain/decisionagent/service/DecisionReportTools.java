package io.skala.backend.domain.decisionagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.skala.backend.domain.decisionagent.dto.DecisionAssistantDtos.Observation;
import io.skala.backend.domain.report.dto.ExportRequest;
import io.skala.backend.domain.report.dto.ReportAiRequests;
import io.skala.backend.domain.report.dto.TemplateResponse;
import io.skala.backend.domain.report.service.ReportAiRelayService;
import io.skala.backend.domain.report.service.ReportExportService;
import io.skala.backend.domain.report.service.ReportTemplateService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 기존 보고서 API의 서비스와 동일한 권한·틀·값 채우기·내보내기를 Agent에 연결한다. */
@Service
@RequiredArgsConstructor
public class DecisionReportTools {
    private static final List<String> WARNINGS = List.of(
            "보고서는 선택한 마스터와 같은 해당 월 최신 원천을 조회합니다. 구마스터나 조회 중 변경은 채택하지 않습니다.",
            "계획·전월·전년 비교값과 소명은 조회 시점 자료이며 고정 마스터 발행 시점으로 동결된 값은 아닙니다.",
            "저장된 틀의 문장은 사용자가 작성한 내용일 수 있습니다. 수치 칸의 조회 근거와 별도로 확인해야 합니다.");
    private final ReportTemplateService templates;
    private final ReportAiRelayService relay;
    private final ReportExportService exports;
    private final DecisionReportBasisGuard guard;
    private final DecisionReportArtifactStore artifacts;
    private final DecisionAssistantJson json;
    private final Clock clock;

    /** 목적: 기존 자료 틀 목록에서 모델이 선택할 최소 metadata만 제공한다.
     * Args: run 권한 실행, args limit·선택 cursor. Returns: 버전·블록 metadata와 다음 cursor.
     * Raises: 권한/조회 오류 또는 앞선 목록에 없는 cursor. Side Effects: 기존 서비스 DB 읽기만. */
    public DecisionAssistantTools.ToolValue templates(DecisionAssistantGateway.Run run, JsonNode args) {
        String cursor=args.has("cursor") ? args.path("cursor").asText() : null;
        if (cursor != null && run.observations.stream().noneMatch(value -> value.status().equals("SUCCESS")
                && value.tool().equals("report_templates") && cursor.equals(value.data().path("nextCursor").asText())))
            throw failure("앞서 조회한 자료 틀 목록의 nextCursor만 사용할 수 있습니다.");
        var page=templates.list(run.actor,cursor,args.path("limit").asInt(10));
        List<Object> items=new ArrayList<>();
        for (TemplateResponse summary:page.items()) {
            var template=templates.get(run.actor,Long.parseLong(summary.id()),summary.latestVersion());
            List<Object> blocks=new ArrayList<>();
            for (JsonNode block:template.definition().path("blocks")) {
                if (blocks.size()==20) break;
                blocks.add(Map.of("blockId",block.path("id").asText(),"kind",block.path("kind").asText(),
                        "title",title(block)));
            }
            items.add(Map.of("templateId",template.id(),"name",template.name(),"version",template.version(),
                    "blockCount",template.definition().path("blocks").size(),"blocks",blocks,
                    "blocksTruncated",template.definition().path("blocks").size()>blocks.size()));
        }
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("items",items); data.put("nextCursor",page.nextCursor()); data.put("limit",args.path("limit").asInt(10));
        return value(data,"공유 자료 틀의 저장 버전 목록",List.of("자료 틀은 업무 수치를 조회한 결과가 아닙니다. 블록 목록은 틀마다 앞의 20개만 표시합니다."));
    }

    /** 목적: 실제 목록에서 선택한 틀을 기존 데이터 채우기 API로 조회한다.
     * Args: run 고정 기준·관찰, args templateId·version·선택 blockId.
     * Returns: 숫자 생성 없는 채운 블록·출처·변경 감지 값.
     * Raises: 미조회 틀·지원하지 않는 SQL/기간·출처 불일치·DB/AI 조회 오류.
     * Side Effects: DB 읽기·기존 /v1/data 호출과 메모리 결과만, 틀·업무 저장 없음. */
    public DecisionAssistantTools.ToolValue preview(DecisionAssistantGateway.Run run, JsonNode args) {
        String templateId=args.path("templateId").asText(); int version=args.path("version").asInt();
        requireKnown(run,templateId,version);
        TemplateResponse template=templates.get(run.actor,Long.parseLong(templateId),version);
        String blockId=args.has("blockId") ? args.path("blockId").asText() : null;
        List<JsonNode> blocks=blocks(template,blockId,run);
        var before=guard.check(run.snapshot);
        JsonNode filled=relay.data(run.actor,new ReportAiRequests.Data(
                new ReportAiRequests.Base(run.snapshot.year(),run.snapshot.baseMonth()),blocks));
        guard.requireSame(before,guard.check(run.snapshot));
        checkFilled(filled,blocks,before.version(),run);
        ObjectNode data=(ObjectNode) filled.deepCopy();
        data.put("templateId",templateId); data.put("templateVersion",version); data.put("templateName",template.name());
        if (blockId==null) data.putNull("blockId"); else data.put("blockId",blockId);
        data.put("masterId",run.snapshot.masterId()); data.put("year",run.snapshot.year()); data.put("month",run.snapshot.baseMonth());
        data.put("sourceVersion",before.version()); data.put("sourceFingerprint",before.fingerprint());
        data.put("templateFingerprint",DecisionReportBasisGuard.digest(json.canonical(template.definition()).toString()));
        data.put("capturedAt",clock.instant().toString()); data.put("saved",false);
        return value(data,"선택한 최신 마스터와 일치하는 저장 틀의 보고서 조회",WARNINGS);
    }

    /** 목적: 동일 실행의 성공 미리보기 값으로 다운로드 가능한 XLSX를 준비한다.
     * Args: run 권한·원장, args previewObservationId.
     * Returns: 메모리 파일 영수증. Raises: 미존재/실패 관찰·기준/틀 변경·용량/권한 오류.
     * Side Effects: 기존 XLSX 렌더링과 10분 임시 메모리 보관. DB쓰기·발송·파일 저장 없음. */
    public DecisionAssistantTools.ToolValue export(DecisionAssistantGateway.Run run, JsonNode args) {
        String observationId=args.path("previewObservationId").asText();
        Observation preview=run.observations.stream().filter(item -> item.id().equals(observationId)
                && item.tool().equals("report_preview") && item.status().equals("SUCCESS")).findFirst()
                .orElseThrow(() -> failure("같은 대화에서 성공한 보고서 미리보기를 먼저 선택해 주세요."));
        JsonNode data=preview.data();
        if (!run.snapshot.masterId().equals(data.path("masterId").asText())) throw failure("보고서의 기준 마스터가 다릅니다.");
        var before=guard.check(run.snapshot);
        if (!before.version().equals(data.path("sourceVersion").asText())
                || !before.fingerprint().equals(data.path("sourceFingerprint").asText()))
            throw failure("미리보기 후 원천·소명 또는 비교 기준이 변경됐습니다. 보고서를 다시 조회해 주세요.");
        String templateId=data.path("templateId").asText(); int version=data.path("templateVersion").asInt();
        TemplateResponse template=templates.get(run.actor,Long.parseLong(templateId),version);
        String fingerprint=DecisionReportBasisGuard.digest(json.canonical(template.definition()).toString());
        if (!fingerprint.equals(data.path("templateFingerprint").asText()) || !template.name().equals(data.path("templateName").asText()))
            throw failure("자료 틀이 변경됐습니다. 같은 버전의 보고서를 다시 조회해 주세요.");
        String blockId=data.hasNonNull("blockId") ? data.path("blockId").asText() : null;
        blocks(template,blockId,run);
        var request=new ExportRequest(templateId,version,new ReportAiRequests.Base(run.snapshot.year(),run.snapshot.baseMonth()),blockId,"XLSX");
        var file=exports.exportFilled(run.actor,request,data);
        guard.requireSame(before,guard.check(run.snapshot));
        var receipt=artifacts.put(run.actor,file.filename(),file.content());
        Map<String,Object> output=new LinkedHashMap<>();
        output.put("artifactId",receipt.artifactId()); output.put("filename",receipt.filename());
        output.put("contentType",receipt.contentType()); output.put("byteSize",receipt.byteSize());
        output.put("expiresAt",receipt.expiresAt()); output.put("ready",true); output.put("format","XLSX");
        output.put("masterId",run.snapshot.masterId()); output.put("templateId",templateId);
        output.put("templateVersion",version); output.put("previewObservationId",observationId); output.put("saved",false);
        return value(output,"성공한 미리보기와 같은 값의 임시 XLSX",List.of(
                "다운로드 파일은 현재 사용자에게만 10분 동안 제공되며 서버 재시작 시 사라집니다.",
                "XLSX는 기존 보고서 내보내기 형식을 사용합니다. 업무 데이터 저장·승인·외부 발송을 수행하지 않았습니다."));
    }

    /** 목적: 모델이 앞서 실제 조회한 틀 버전만 선택하게 한다. Args: run 원장, id 틀, version 버전.
     * Returns: 없음. Raises: ToolFailure 미조회 선택. Side Effects: 없음. */
    private void requireKnown(DecisionAssistantGateway.Run run,String id,int version) {
        for (Observation observation:run.observations) if (observation.tool().equals("report_templates") && observation.status().equals("SUCCESS"))
            for (JsonNode item:observation.data().path("items"))
                if (id.equals(item.path("templateId").asText()) && version==item.path("version").asInt()) return;
        throw failure("자료 틀 목록에서 확인한 templateId와 version을 선택해 주세요.");
    }

    /** 목적: 저장 틀에서 고정 기준으로 지원할 수 있는 블록만 선택한다.
     * Args: template 저장 틀, blockId 선택 ID, run 고정 조직·월.
     * Returns: 실제 블록 사본. Raises: ToolFailure 미지원 SQL·기간·조직·블록.
     * Side Effects: 없음. 전체 보고서 중 지원하지 않는 부분을 몰래 생략하지 않는다. */
    private List<JsonNode> blocks(TemplateResponse template,String blockId,DecisionAssistantGateway.Run run) {
        List<JsonNode> selected=new ArrayList<>();
        Set<String> codes=new HashSet<>(), names=new HashSet<>();
        run.snapshot.organizations().forEach(org -> { codes.add(org.code()); names.add(org.name()); });
        for (JsonNode block:template.definition().path("blocks")) {
            if (blockId!=null && !blockId.equals(block.path("id").asText())) continue;
            String kind=block.path("kind").asText();
            if (!Set.of("title","paragraph","footnote","table","spec_table","chart","numbercard","explist","breakdown","metric_table").contains(kind))
                throw failure("해당 종류의 보고서 블록은 Agent의 고정 기준 조회에서 지원하지 않습니다. 기본 표·지표 블록을 선택해 주세요.");
            if (block.hasNonNull("sql") || block.hasNonNull("metricId"))
                throw failure("SQL 지표를 가진 블록은 현재 Agent의 고정 기준 보고서에서 지원하지 않습니다. 기본 지표 블록을 선택해 주세요.");
            if (kind.equals("chart") && (block.path("chart_type").asText().equals("line")
                    || block.hasNonNull("months") || block.hasNonNull("months_n")
                    || containsMonthLabels(block.path("categories"))))
                throw failure("월별 추이 차트는 고정 기준 보고서에서 지원하지 않습니다. 기준월 조직 비교 차트를 선택해 주세요.");
            if (block.hasNonNull("period")) {
                JsonNode period=block.path("period"); String rule=period.path("rule").asText("base");
                boolean supported=rule.equals("base") || rule.equals("recent_n") && period.path("n").asInt()==1
                        || rule.equals("fixed") && period.path("year").asInt()==run.snapshot.year()
                        && period.path("month").asInt()==run.snapshot.baseMonth();
                if (!supported) throw failure("다른 월·다기간 블록은 현재 고정 기준 보고서에서 지원하지 않습니다. 기준월 블록을 선택해 주세요.");
            }
            if (block.hasNonNull("scope_org") && !codes.contains(block.path("scope_org").asText()))
                throw failure("보고서 블록의 조직이 선택한 마스터 범위 밖입니다.");
            for (JsonNode code:block.path("scope_orgs")) if (!codes.contains(code.asText()))
                throw failure("보고서 블록의 조직이 선택한 마스터 범위 밖입니다.");
            for (JsonNode scope:block.path("scope")) if (!codes.contains(scope.asText()) && !names.contains(scope.asText()))
                throw failure("보고서 차트의 조직이 선택한 마스터 범위 밖입니다.");
            for (JsonNode row:block.path("spec").path("rows"))
                if (row.path("t").asText().equals("org") && !codes.contains(row.path("code").asText()))
                    throw failure("보고서 표의 조직이 선택한 마스터 범위 밖입니다.");
            selected.add(block.deepCopy());
        }
        if (selected.isEmpty()) throw failure("선택한 자료 틀에 조회할 블록이 없습니다.");
        return List.copyOf(selected);
    }

    /** 목적: 저장 차트가 다른 월 원천을 읽는 축인지 확인한다. Args: categories 차트 축.
     * Returns: 월 표기 존재 여부. Raises: 없음. Side Effects: 없음. */
    private boolean containsMonthLabels(JsonNode categories) {
        for (JsonNode item:categories) if (item.asText().matches("\\s*\\d{1,2}\\s*월?\\s*")) return true;
        return false;
    }

    /** 목적: 데이터 API가 실제 기준과 선택한 블록을 반환했는지 검사한다.
     * Args: filled 응답, blocks 요청 블록, version 기대 원천, run 고정 연월.
     * Returns: 없음. Raises: ToolFailure 출처·상태·블록 불일치. Side Effects: 없음. */
    private void checkFilled(JsonNode filled,List<JsonNode> blocks,String version,DecisionAssistantGateway.Run run) {
        if (filled==null || !filled.isObject() || !filled.path("dataStatus").asText().equals("CONFIRMED")
                || !version.equals(filled.path("source").path("version").asText())
                || filled.path("source").path("year").asInt()!=run.snapshot.year()
                || filled.path("source").path("month").asInt()!=run.snapshot.baseMonth()
                || !filled.path("blocks").isArray() || filled.path("blocks").size()!=blocks.size())
            throw failure("보고서 데이터 API의 확정 상태·기준·블록을 확인하지 못했습니다. 연결 상태와 기준월을 확인해 주세요.");
        for (int i=0;i<blocks.size();i++) if (!blocks.get(i).path("id").equals(filled.path("blocks").get(i).path("id")))
            throw failure("보고서 데이터 API가 선택한 블록과 다른 결과를 반환했습니다.");
    }

    /** 목적: 수치 원문 없이 블록을 식별할 짧은 제목을 만든다. Args: block 저장 정의.
     * Returns: 최대 120자 표시명. Raises: 없음. Side Effects: 없음. */
    private String title(JsonNode block) {
        for (String key:List.of("title","label")) if (block.hasNonNull(key)) {
            String value=block.path(key).asText(); return value.substring(0,Math.min(120,value.length()));
        }
        return block.path("kind").asText();
    }

    /** 목적: 도구 봉투를 만든다. Args: data 자료, basis 비교 기준, warnings 한계.
     * Returns: 기존 ToolValue. Raises: 없음. Side Effects: 없음. */
    private DecisionAssistantTools.ToolValue value(Object data,String basis,List<String> warnings) {
        return new DecisionAssistantTools.ToolValue(data,basis,warnings);
    }

    /** 목적: 공개할 수 있는 업무 오류를 만든다. Args: message 안내. Returns: ToolFailure.
     * Raises: 없음. Side Effects: 없음. */
    private DecisionAssistantTools.ToolFailure failure(String message) { return new DecisionAssistantTools.ToolFailure(message); }
}
