package io.skala.backend.settings;

import com.fasterxml.jackson.databind.JsonNode;
import io.skala.backend.common.ApiException;
import io.skala.backend.foundation.CommandExecutor;
import io.skala.backend.security.ActorResolver;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 기획 설정과 본인 소속 확인의 인증된 HTTP 경계다. */
@RestController
@RequestMapping("/api/v1")
public class SettingsController {
    private final SettingsService service;
    private final ActorResolver actors;

    /** 설정·인증 서비스를 연결한다. Args: service 설정, actors 사용자. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public SettingsController(SettingsService service,ActorResolver actors){this.service=service;this.actors=actors;}
    /** 설정 조직을 읽는다. Args: query 빈 쿼리. Returns: 조직 목록. Raises: 권한·입력 오류. Side Effects: DB 읽기. */
    @GetMapping("/settings/organizations")
    public Map<String,Object> organizations(@RequestParam Map<String,String> query){empty(query);return service.organizations(actors.current());}
    /** 조직을 등록한다. Args: key 멱등키, input 조직, query 빈 쿼리. Returns: 201 조직. Raises: 입력·권한 오류. Side Effects: DB 저장. */
    @PostMapping("/settings/organizations")
    public ResponseEntity<String> createOrganization(@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode input,@RequestParam Map<String,String> query){empty(query);return response(service.createOrganization(actors.current(),key,input));}
    /** 조직을 변경한다. Args: organizationId 조직, key 멱등키, input 변경, query 빈 쿼리. Returns: 변경 조직. Raises: 입력·권한·충돌. Side Effects: DB 저장. */
    @PatchMapping("/settings/organizations/{organizationId}")
    public ResponseEntity<String> updateOrganization(@PathVariable String organizationId,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode input,@RequestParam Map<String,String> query){empty(query);return response(service.updateOrganization(actors.current(),organizationId,key,input));}
    /** 사용자를 조회한다. Args: query 조직·역할·활성 필터. Returns: 계정 목록. Raises: 입력·권한 오류. Side Effects: DB 읽기. */
    @GetMapping("/settings/users")
    public Map<String,Object> users(@RequestParam Map<String,String> query){return service.users(actors.current(),query);}
    /** 사용자를 등록한다. Args: key 멱등키, input 계정, query 빈 쿼리. Returns: 201 계정. Raises: 입력·권한 오류. Side Effects: DB 저장. */
    @PostMapping("/settings/users")
    public ResponseEntity<String> createUser(@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode input,@RequestParam Map<String,String> query){empty(query);return response(service.createUser(actors.current(),key,input));}
    /** 계정 권한을 변경한다. Args: userId 계정, key 멱등키, input 변경, query 빈 쿼리. Returns: 변경 계정. Raises: 입력·권한·충돌. Side Effects: DB 저장. */
    @PatchMapping("/settings/users/{userId}")
    public ResponseEntity<String> updateUser(@PathVariable String userId,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode input,@RequestParam Map<String,String> query){empty(query);return response(service.updateUser(actors.current(),userId,key,input));}
    /** 문구를 읽는다. Args: query 빈 쿼리. Returns: 문구 목록. Raises: 권한 오류. Side Effects: DB 읽기. */
    @GetMapping("/settings/message-templates")
    public Map<String,Object> templates(@RequestParam Map<String,String> query){empty(query);return service.templates(actors.current());}
    /** 문구를 등록한다. Args: key 멱등키, input 문구, query 빈 쿼리. Returns: 201 문구. Raises: 입력·권한 오류. Side Effects: DB 저장. */
    @PostMapping("/settings/message-templates")
    public ResponseEntity<String> createTemplate(@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode input,@RequestParam Map<String,String> query){empty(query);return response(service.createTemplate(actors.current(),key,input));}
    /** 문구를 변경한다. Args: templateId 문구, key 멱등키, input 변경, query 빈 쿼리. Returns: 변경 문구. Raises: 입력·권한·충돌. Side Effects: DB 저장. */
    @PatchMapping("/settings/message-templates/{templateId}")
    public ResponseEntity<String> updateTemplate(@PathVariable String templateId,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode input,@RequestParam Map<String,String> query){empty(query);return response(service.updateTemplate(actors.current(),templateId,key,input,false));}
    /** 문구를 논리 삭제한다. Args: templateId 문구, key 멱등키, input 토큰, query 빈 쿼리. Returns: 비활성 문구. Raises: 입력·권한·충돌. Side Effects: DB 저장. */
    @DeleteMapping("/settings/message-templates/{templateId}")
    public ResponseEntity<String> deleteTemplate(@PathVariable String templateId,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode input,@RequestParam Map<String,String> query){empty(query);return response(service.updateTemplate(actors.current(),templateId,key,input,true));}
    /** 본인 소속 확인 상태를 읽는다. Args: query 빈 쿼리. Returns: 상태·서버 역할. Raises: 인증 오류. Side Effects: DB 읽기. */
    @GetMapping("/auth/profile-confirmation")
    public Map<String,Object> confirmation(@RequestParam Map<String,String> query){empty(query);return service.confirmation(actors.current());}
    /** 본인 소속을 확인한다. Args: key 멱등키, input 토큰, query 빈 쿼리. Returns: 확인 상태. Raises: 입력·인증·충돌. Side Effects: DB 저장. */
    @PostMapping("/auth/profile-confirmation")
    public ResponseEntity<String> confirm(@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody JsonNode input,@RequestParam Map<String,String> query){empty(query);return response(service.confirm(actors.current(),key,input));}
    /** 예상하지 않은 쿼리를 거부한다. Args: query 쿼리. Returns: 없음. Raises: 400 오류. Side Effects: 없음. */
    private void empty(Map<String,String> query){if(!query.isEmpty())throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","지원하지 않는 쿼리입니다.");}
    /** 멱등 결과를 HTTP로 변환한다. Args: result 명령 결과. Returns: JSON 응답. Raises: 없음. Side Effects: 없음. */
    private ResponseEntity<String> response(CommandExecutor.Result result){var response=ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON);if(!result.location().isBlank())response.header(HttpHeaders.LOCATION,result.location());if(result.replayed())response.header("Idempotency-Replayed","true");return response.body(result.body());}
}
