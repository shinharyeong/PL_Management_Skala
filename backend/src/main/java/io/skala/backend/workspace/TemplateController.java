package io.skala.backend.workspace;

import static io.skala.backend.submission.ApiData.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.ActorResolver;
import io.skala.backend.submission.SubmissionService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 요청별 실제 제출 양식을 원본 그대로 등록하고 내려준다. 테스트 양식을 자동 생성하지 않는다. */
@RestController
@RequestMapping("/api/v1/submission-requests/{requestId}/template")
public class TemplateController {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final JdbcClient jdbc;
    private final ActorResolver actors;
    private final SubmissionService requests;
    private final CommandExecutor commands;
    private final ObjectMapper mapper;

    /** 경계를 연결한다. Args: jdbc DB, actors 인증, requests 요청권한, commands 멱등명령, mapper JSON. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public TemplateController(JdbcClient jdbc, ActorResolver actors, SubmissionService requests, CommandExecutor commands, ObjectMapper mapper) { this.jdbc=jdbc;this.actors=actors;this.requests=requests;this.commands=commands;this.mapper=mapper; }

    /** 최신 양식 등록 상태를 읽는다. Args: requestId 요청. Returns: 양식 메타정보. Raises: 권한·404. Side Effects: DB 읽기. */
    @GetMapping public Map<String,Object> metadata(@PathVariable String requestId) {
        requests.request(actors.current(), requestId);
        var rows = latest(requestId);
        if (rows.isEmpty()) return object("available", false, "filename", null, "updatedAt", null);
        var row=rows.getFirst();
        return object("available",true,"filename",row.get("filename"),"updatedAt",instant(row.get("registered_at")),"sha256",row.get("source_sha256"),"templateId",row.get("id").toString());
    }

    /** 기획자가 제공한 XLSX를 불변 양식 이력으로 등록한다.
     * Args: requestId 요청, key 멱등키, file 실제 양식.
     * Returns: 메타정보. Raises: 권한·XLSX형식·크기·멱등 오류, IOException 입력 읽기 실패.
     * Side Effects: DB에 바이트·등록 이력 저장. 로컬 원본·파일 시스템을 변경하지 않는다.
     */
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> register(@PathVariable String requestId, @RequestHeader(value="Idempotency-Key",required=false) String key, @RequestPart("file") MultipartFile file) throws IOException {
        var actor=actors.current();
        if (!"PLANNER".equals(actor.role())) throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","경영기획팀만 양식을 등록할 수 있습니다.");
        requests.request(actor, requestId);
        String filename=file.getOriginalFilename(); byte[] bytes=file.getBytes();
        validate(filename, bytes);
        String sha=hash(bytes);
        var result=commands.execute(actor,"POST /api/v1/submission-requests/"+requestId+"/template",key,hash((filename+":"+sha).getBytes(StandardCharsets.UTF_8)),()->{
            requests.request(actor,requestId);
            jdbc.sql("SELECT id FROM submission_request WHERE id=? FOR UPDATE").param(Long.parseLong(requestId)).query(Long.class).single();
            String objectKey="template/"+UUID.randomUUID();
            jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES (?,?,?)").params(objectKey,XLSX,bytes).update();
            jdbc.sql("INSERT INTO request_template(request_id,object_key,filename,source_sha256,registered_by,registered_at) VALUES (?,?,?,?,?,?)")
                    .params(Long.parseLong(requestId),objectKey,filename,sha,Long.parseLong(actor.id()),OffsetDateTime.now(ZoneOffset.UTC)).update();
            try { return new CommandExecutor.Result(201,mapper.writeValueAsString(metadata(requestId)),"",false); }
            catch (IOException exception) { throw new IllegalStateException("양식 메타정보 직렬화 실패"); }
        });
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(result.body());
    }

    /** 등록된 실제 양식 바이트를 내려준다. Args: requestId 요청. Returns: 원본 attachment. Raises: 권한·미등록404. Side Effects: DB 읽기. */
    @GetMapping("/file") public ResponseEntity<byte[]> file(@PathVariable String requestId) {
        requests.request(actors.current(),requestId);
        var rows=latest(requestId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"TEMPLATE_NOT_REGISTERED","아직 제출 양식이 등록되지 않았습니다.");
        var row=rows.getFirst(); byte[] bytes=jdbc.sql("SELECT payload FROM stored_object WHERE object_key=?").param(row.get("object_key")).query(byte[].class).single();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(XLSX)).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(row.get("filename").toString(),StandardCharsets.UTF_8).build().toString()).body(bytes);
    }

    /** 최신 등록행을 조회한다. Args: requestId 검증된 요청. Returns: 0~1개 행. Raises: DB 오류. Side Effects: DB 읽기. */
    private List<Map<String,Object>> latest(String requestId) { return jdbc.sql("SELECT * FROM request_template WHERE request_id=? ORDER BY id DESC LIMIT 1").param(Long.parseLong(requestId)).query().listOfRows(); }

    /** 파일 이름과 ZIP 구조를 제한하여 매크로 없는 실제 XLSX만 받는다.
     * Args: filename 이름, bytes 원문. Returns: 없음. Raises: 400/413 유효하지 않은 파일.
     * Side Effects: 메모리에서 ZIP을 읽으며 수식·XML을 실행하지 않는다.
     */
    private void validate(String filename, byte[] bytes) {
        if (filename==null || filename.length()>255 || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx") || filename.contains("/") || filename.contains("\\") || filename.chars().anyMatch(c->c<32) || bytes.length==0) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TEMPLATE","XLSX 양식 파일을 선택해 주세요.");
        if (bytes.length>16*1024*1024) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"FILE_TOO_LARGE","양식은 16MB 이하여야 합니다.");
        boolean workbook=false, types=false; long total=0; int entries=0;
        try (var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry; byte[] buffer=new byte[8192];
            while ((entry=zip.getNextEntry())!=null) {
                if (++entries>10000 || entry.getName().contains("vbaProject") || entry.getName().contains("../")) throw new IOException();
                workbook |= "xl/workbook.xml".equals(entry.getName()); types |= "[Content_Types].xml".equals(entry.getName());
                int n; while ((n=zip.read(buffer))!=-1) { total+=n; if (total>128L*1024*1024) throw new IOException(); }
            }
        } catch (IOException error) { throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TEMPLATE","유효한 매크로 없는 XLSX 양식이 아닙니다."); }
        if (!workbook || !types) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TEMPLATE","유효한 XLSX 양식이 아닙니다.");
    }
}
