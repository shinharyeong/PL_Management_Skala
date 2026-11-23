package io.skala.backend.report;

import static io.skala.backend.report.ReportAuthoringJson.*;
import static io.skala.backend.submission.ApiData.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 기획자 보고 작성·미리보기·명시 적용·내보내기와 읽기 전용 MCP 경계를 제공한다. */
@RestController
@RequestMapping("/api/v1/report-authoring")
public class ReportAuthoringController {
    private final ReportAuthoringService service;
    private final ReportAiClient ai;
    private final ActorResolver actors;
    private final CommandExecutor commands;
    /** 업무 경계를 연결한다. Args: service 작성, ai 초안, actors 인증, commands 멱등. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public ReportAuthoringController(ReportAuthoringService service,ReportAiClient ai,ActorResolver actors,CommandExecutor commands) { this.service=service; this.ai=ai; this.actors=actors; this.commands=commands; }
    /** 작성 카탈로그를 조회한다. Args: 없음. Returns: 지표·기간·글꼴. Raises: 권한. Side Effects: DB 읽기. */
    @GetMapping("/catalog") public Map<String,Object> catalog() { return service.catalog(actors.current()); }
    /** 자료틀 목록을 조회한다. Args: 없음. Returns: items. Raises: 권한. Side Effects: DB 읽기. */
    @GetMapping("/templates") public Map<String,Object> templates() { return service.list(actors.current(),true); }
    /** 보고자료 목록을 조회한다. Args: 없음. Returns: items. Raises: 권한. Side Effects: DB 읽기. */
    @GetMapping("/materials") public Map<String,Object> materials() { return service.list(actors.current(),false); }
    /** 자료틀을 읽는다. Args: value ID, version 선택회차. Returns: 틀. Raises: 권한·404. Side Effects: DB 읽기. */
    @GetMapping("/templates/{value}") public Map<String,Object> template(@PathVariable String value,@RequestParam(required=false) Integer version) { return service.get(actors.current(),value,version,true); }
    /** 현재 보고자료를 읽는다. Args: value ID. Returns: 저장정의·결과. Raises: 권한·404. Side Effects: DB 읽기. */
    @GetMapping("/materials/{value}") public Map<String,Object> material(@PathVariable String value) { return service.get(actors.current(),value,null,false); }
    /** 과거 불변버전을 읽는다. Args: value ID, revision 회차. Returns: 고정자료. Raises: 권한·404. Side Effects: DB 읽기. */
    @GetMapping("/materials/{value}/versions/{revision}") public Map<String,Object> version(@PathVariable String value,@PathVariable Integer revision) { return service.get(actors.current(),value,revision,false); }
    /** 확인한 새 틀을 저장한다. Args: input 정의, key 멱등키. Returns: 틀. Raises: 권한·검증. Side Effects: DB 새 버전·멱등응답 저장. */
    @PostMapping("/templates") public ResponseEntity<String> createTemplate(@RequestBody JsonNode input,@RequestHeader(value="Idempotency-Key",required=false) String key) { Actor actor=actors.current(); return command(actor,"POST /api/v1/report-authoring/templates",key,input,() -> service.create(actor,input,true)); }
    /** 새 자료의 구성을 저장한다. Args: input 정의, key 멱등키. Returns: 빈 계산결과의 첫버전. Raises: 권한·입력. Side Effects: DB 추가. */
    @PostMapping("/materials") public ResponseEntity<String> createMaterial(@RequestBody JsonNode input,@RequestHeader(value="Idempotency-Key",required=false) String key) { Actor actor=actors.current(); return command(actor,"POST /api/v1/report-authoring/materials",key,input,() -> service.create(actor,input,false)); }
    /** 틀의 새 버전을 저장한다. Args: value 틀ID, input 정의, key 멱등키. Returns: 틀버전. Raises: 동시수정. Side Effects: DB 추가. */
    @PostMapping("/templates/{value}/versions") public ResponseEntity<String> templateVersion(@PathVariable String value,@RequestBody JsonNode input,@RequestHeader(value="Idempotency-Key",required=false) String key) { Actor actor=actors.current(); return command(actor,"POST /api/v1/report-authoring/templates/"+id(value)+"/versions",key,input,() -> service.templateVersion(actor,value,input)); }
    /** 적용 전 숫자와 출처를 확인한다. Args: value 자료ID, input 편집, key 멱등키. Returns: 만료제안. Raises: 입력·동시수정. Side Effects: 제안·멱등응답만 저장. */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    @PostMapping("/materials/{value}/preview") public ResponseEntity<String> preview(@PathVariable String value,@RequestBody JsonNode input,@RequestHeader(value="Idempotency-Key",required=false) String key) { Actor actor=actors.current(); return command(actor,"POST /api/v1/report-authoring/materials/"+id(value)+"/preview",key,input,() -> service.preview(actor,value,input)); }
    /** 사용자가 확인한 제안을 적용한다. Args: value 자료ID, input 제안·회차, key 멱등키. Returns: 새버전. Raises: 만료·권한·동시수정. Side Effects: DB 원자적 추가. */
    @PostMapping("/materials/{value}/apply") public ResponseEntity<String> apply(@PathVariable String value,@RequestBody JsonNode input,@RequestHeader(value="Idempotency-Key",required=false) String key) { Actor actor=actors.current(); return command(actor,"POST /api/v1/report-authoring/materials/"+id(value)+"/apply",key,input,() -> service.apply(actor,value,input)); }
    /** 이전 자료를 새 버전으로 복원한다. Args: value 자료ID, input 기대회차, key 멱등키. Returns: 복원버전. Raises: 동시수정. Side Effects: DB 새 버전. */
    @PostMapping("/materials/{value}/undo") public ResponseEntity<String> undo(@PathVariable String value,@RequestBody JsonNode input,@RequestHeader(value="Idempotency-Key",required=false) String key) { Actor actor=actors.current(); return command(actor,"POST /api/v1/report-authoring/materials/"+id(value)+"/undo",key,input,() -> service.undo(actor,value,input)); }
    /** 사유계층을 읽는다. Args: version 선택버전. Returns: 계층. Raises: 권한·404. Side Effects: DB 읽기. */
    @GetMapping("/reason-types") public Map<String,Object> reasons(@RequestParam(required=false) Integer version) { return service.reasons(actors.current(),version); }
    /** 사유계층을 새 버전으로 저장한다. Args: input 계층, key 멱등키. Returns: 새 버전. Raises: 계층·동시수정. Side Effects: DB 추가. */
    @PostMapping("/reason-types") public ResponseEntity<String> saveReasons(@RequestBody JsonNode input,@RequestHeader(value="Idempotency-Key",required=false) String key) { Actor actor=actors.current(); return command(actor,"POST /api/v1/report-authoring/reason-types",key,input,() -> service.saveReasons(actor,input)); }
    /** 확인한 지표 SQL을 사전에 추가한다. Args: input 지표, key 멱등키. Returns: CUSTOM 지표. Raises: SQL 오류. Side Effects: DB 추가. */
    @PostMapping("/metrics") public ResponseEntity<String> metric(@RequestBody JsonNode input,@RequestHeader(value="Idempotency-Key",required=false) String key) { Actor actor=actors.current(); return command(actor,"POST /api/v1/report-authoring/metrics",key,input,() -> service.saveMetric(actor,input)); }
    /** 저장 없이 인증 보고뷰 SQL을 실행한다. Args: input 블록·기간. Returns: 결과. Raises: 입력·권한. Side Effects: DB 읽기만. */
    @PostMapping("/query") public Map<String,Object> query(@RequestBody JsonNode input) { return service.query(actors.current(),input); }
    /** 자료 하나의 고정버전을 HTML로 내보낸다. Args: value ID, version 선택회차. Returns: HTML 첨부. Raises: 권한·404. Side Effects: DB 읽기·메모리 바이트만. */
    @GetMapping("/materials/{value}/file") public ResponseEntity<byte[]> file(@PathVariable String value,@RequestParam(required=false) Integer version) {
        var material=service.get(actors.current(),value,version,false); return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=report-"+id(value)+"-v"+material.get("revision")+".html").header(HttpHeaders.CACHE_CONTROL,"no-store").header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; img-src data:").body(ReportMaterialExport.write(material));
    }
    /** 자연어 SQL 또는 편집 초안을 요청한다. Args: operation sql/edit, input 질문/블록. Returns: 검증된 미확정 초안. Raises: 권한·입력. Side Effects: AI 호출만. */
    @PostMapping("/ai/{operation}") public Map<String,Object> ai(@PathVariable String operation,@RequestBody JsonNode input) {
        if (!Set.of("sql","edit").contains(operation)) throw invalid("지원하지 않는 AI 작업입니다."); Actor actor=actors.current(); actor.requireRole("PLANNER");
        ObjectNode safe=JSON.createObjectNode(); for (String field:List.of("question","instruction","baseYear","baseMonth","block")) if (input.has(field)) safe.set(field,input.get(field));
        if ("edit".equals(operation)) safe.set("blocks",service.validateBlocks(input.path("blocks")));
        var result=ai.propose(actor,operation,safe);
        if (Boolean.TRUE.equals(result.get("configured"))) try {
            if ("sql".equals(operation)) { String sql=Objects.toString(result.get("sql"),""); ReportQueryEngine.execute(sql,List.of()); result.put("sql",sql); }
            else result.put("blocks",service.validateBlocks(JSON.valueToTree(result.get("blocks"))));
        } catch (RuntimeException error) { return object("configured",false,"warnings",List.of("AI 제안이 보고 계약에 맞지 않아 반영하지 않았습니다. 직접 작성할 수 있습니다.")); }
        return result;
    }
    /** PDF를 검사한 후 AI 구조 초안을 받는다. Args: file PDF, maskNumbers 숫자제거 여부. Returns: 확인 전 구성. Raises: 권한·파일입력. Side Effects: 메모리 읽기·명시 AI 호출, 서버파일 저장 없음. */
    @PostMapping(value="/ai/pdf",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Map<String,Object> pdf(@RequestPart MultipartFile file,@RequestParam(defaultValue="true") boolean maskNumbers) throws IOException {
        Actor actor=actors.current(); actor.requireRole("PLANNER"); if (file.isEmpty() || file.getSize()>16*1024*1024) throw invalid("16MiB 이하 PDF를 선택해 주세요."); byte[] bytes=file.getBytes();
        if (bytes.length<5 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) throw invalid("PDF 파일만 분석할 수 있습니다.");
        var result=ai.propose(actor,"pdf",JSON.valueToTree(object("filename","report.pdf","pdfBase64",Base64.getEncoder().encodeToString(bytes),"maskNumbers",maskNumbers)));
        if (Boolean.TRUE.equals(result.get("configured"))) try { result.put("blocks",service.validateBlocks(JSON.valueToTree(result.get("blocks")))); } catch (RuntimeException error) { return object("configured",false,"warnings",List.of("PDF 분석 구성이 보고 계약에 맞지 않습니다. 직접 작성할 수 있습니다.")); }
        return result;
    }
    /** HTTP JSON-RPC를 통해 읽기 전용 보고뷰 도구만 제공한다. Args: input MCP 요청. Returns: JSON-RPC 결과. Raises: 인증·입력. Side Effects: tools/call은 DB 읽기만, 쓰기·임의 SQL·파일 접근 없음. */
    @PostMapping("/mcp") public ResponseEntity<Map<String,Object>> mcp(@RequestBody JsonNode input) {
        Actor actor=actors.current(); actor.requireRole("PLANNER"); String method=input.path("method").asText(); Object identity=JSON.convertValue(input.get("id"),Object.class);
        if ("notifications/initialized".equals(method)) return ResponseEntity.accepted().build(); Object result;
        switch (method) {
            case "initialize" -> result=object("protocolVersion","2025-03-26","capabilities",object("tools",object("listChanged",false)),"serverInfo",object("name","approved-report-view","version","1.0"));
            case "tools/list" -> result=object("tools",List.of(object("name","query_report_view","description","기획자 범위에서 선택한 기간·조직의 보고뷰 SELECT를 읽기 전용으로 평가합니다.","annotations",object("readOnlyHint",true,"destructiveHint",false,"openWorldHint",false),"inputSchema",object("type","object","properties",object("baseYear",object("type","integer"),"baseMonth",object("type","integer"),"block",object("type","object")),"required",List.of("baseYear","baseMonth","block")))));
            case "tools/call" -> { if (!"query_report_view".equals(input.path("params").path("name").asText())) return ResponseEntity.ok(object("jsonrpc","2.0","id",identity,"error",object("code",-32601,"message","지원하지 않는 읽기 도구입니다."))); var data=service.query(actor,input.path("params").path("arguments")); result=object("content",List.of(object("type","text","text",write(data))),"structuredContent",data,"isError",false); }
            default -> { return ResponseEntity.ok(object("jsonrpc","2.0","id",identity,"error",object("code",-32601,"message","지원하지 않는 메서드입니다."))); }
        }
        return ResponseEntity.ok(object("jsonrpc","2.0","id",identity,"result",result));
    }
    /** DB 명령의 권한·멱등·원자 응답을 통일한다. Args: actor 사용자, route 경로, key 멱등키, input 본문, action 업무. Returns: JSON 응답. Raises: 권한·키충돌·업무오류. Side Effects: actor잠금·업무·멱등응답을 같은 DB 트랜잭션에 저장. */
    private ResponseEntity<String> command(Actor actor,String route,String key,JsonNode input,Supplier<Map<String,Object>> action) {
        actor.requireRole("PLANNER"); var result=commands.execute(actor,route,key,hash(write(input).getBytes(StandardCharsets.UTF_8)),() -> new CommandExecutor.Result(200,write(action.get()),"",false));
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL,"no-store").body(result.body());
    }
}
