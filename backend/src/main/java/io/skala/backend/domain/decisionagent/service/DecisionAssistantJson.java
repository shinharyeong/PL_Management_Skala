package io.skala.backend.domain.decisionagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 정밀 문자열, 결정적 인자 키, 파일·인증 정보 제거를 한 경계에서 처리한다. */
@Component
public class DecisionAssistantJson {
    private static final Set<String> HIDDEN = Set.of("file", "url", "downloadUrl", "stateToken", "toolAccessToken", "parsingJob");
    private static final Pattern LINKS = Pattern.compile("(?i)(?:https?://|file://|s3://|gs://|www\\.)[^\\s<>]+|(?:/Users/|/private/|/tmp/|[A-Z]:\\\\)[^\\s<>]+");
    private final ObjectMapper mapper;

    /** 목적: 공용 ObjectMapper를 변경하지 않는 정밀 직렬화기를 만든다. Args: mapper 애플리케이션 직렬화기. Returns: 인스턴스. Raises: 없음. Side Effects: 메모리 구성만. */
    public DecisionAssistantJson(ObjectMapper mapper) {
        this.mapper = mapper.copy().registerModule(new SimpleModule().addSerializer(BigDecimal.class, ToStringSerializer.instance));
    }

    /** 목적: 응답을 정밀 JSON으로 변환하고 파일·URL·변경 토큰을 제거한다. Args: value 조회 값. Returns: 안전한 JSON. Raises: IllegalArgumentException 직렬화 실패. Side Effects: 없음. */
    public JsonNode safe(Object value) { return clean(mapper.valueToTree(value)); }

    /** 목적: 원문 검증에 사용할 변환을 수행한다. Args: value 계약 객체. Returns: JSON. Raises: IllegalArgumentException 변환 실패. Side Effects: 없음. */
    public JsonNode tree(Object value) { return mapper.valueToTree(value); }

    /** 목적: 최대 응답 예산을 UTF-8 바이트로 센다. Args: value 직렬화 값. Returns: 바이트 수. Raises: IllegalArgumentException 변환 실패. Side Effects: 없음. */
    public int size(Object value) {
        try { return mapper.writeValueAsBytes(value).length; }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("조회 응답을 변환할 수 없습니다."); }
    }

    /** 목적: 필드 순서와 무관한 도구 캐시 키를 만든다. Args: node 인자 JSON. Returns: 정렬된 JSON. Raises: 없음. Side Effects: 없음. */
    public JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            TreeSet<String> fields = new TreeSet<>(); node.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field -> result.set(field, canonical(node.get(field)))); return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode(); node.forEach(item -> result.add(canonical(item))); return result;
        }
        return node.deepCopy();
    }

    /** 목적: 필요한 공개 필드만 정밀 JSON으로 투영한다. Args: value 원본, fields 허용 필드. Returns: 투영 JSON. Raises: 변환 실패. Side Effects: 없음. */
    public ObjectNode project(Object value, String... fields) {
        JsonNode input = safe(value); ObjectNode result = mapper.createObjectNode();
        for (String field : fields) if (input.has(field)) result.set(field, input.get(field));
        return result;
    }

    /** 목적: 중첩 응답에 파일 위치·비밀 값·소수 JSON number가 남지 않게 한다. Args: node JSON. Returns: 정제 사본. Raises: 없음. Side Effects: 없음. */
    private JsonNode clean(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            node.fields().forEachRemaining(entry -> { if (!HIDDEN.contains(entry.getKey())) result.set(entry.getKey(), clean(entry.getValue())); });
            return result;
        }
        if (node.isArray()) { var result = mapper.createArrayNode(); node.forEach(item -> result.add(clean(item))); return result; }
        if (node.isFloatingPointNumber()) return mapper.getNodeFactory().textNode(node.decimalValue().toPlainString());
        if (node.isTextual()) return mapper.getNodeFactory().textNode(LINKS.matcher(node.textValue()).replaceAll("[링크 제외]"));
        return node.deepCopy();
    }
}
