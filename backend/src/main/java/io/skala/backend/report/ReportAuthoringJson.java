package io.skala.backend.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.common.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;

/** 보고 작성의 JSON 경계와 공개 오류를 관리한다. */
final class ReportAuthoringJson {
    static final ObjectMapper JSON = new ObjectMapper();
    /** 유틸리티 생성을 막는다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    private ReportAuthoringJson() {}
    /** JSON을 문자열로 직렬화한다. Args: value JSON 자료. Returns: JSON. Raises: 직렬화 오류. Side Effects: 메모리만 사용. */
    static String write(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException("보고 자료 직렬화 실패", error); }
    }
    /** 저장 JSON을 읽는다. Args: value JSON 문자열. Returns: JSON 트리. Raises: 저장 무결성 오류. Side Effects: 없음. */
    static JsonNode read(Object value) {
        try { return JSON.readTree(value.toString()); } catch (Exception error) { throw new IllegalStateException("보고 자료 복원 실패", error); }
    }
    /** JSON을 nullable 맵으로 바꾼다. Args: value 객체. Returns: 독립 맵. Raises: 타입 오류. Side Effects: 없음. */
    static Map<String,Object> map(JsonNode value) { return JSON.convertValue(value, new TypeReference<LinkedHashMap<String,Object>>() {}); }
    /** 필수 문자열을 제한한다. Args: node 부모, key 필드, max 길이. Returns: 공백 제거 문자열. Raises: 400. Side Effects: 없음. */
    static String text(JsonNode node, String key, int max) {
        String value = node.path(key).asText("").strip();
        if (value.isEmpty() || value.length() > max) throw invalid(key + " 값을 확인해 주세요.");
        return value;
    }
    /** 정수 범위를 확인한다. Args: node 부모, key 필드, min/max 범위, fallback 기본값. Returns: 정수. Raises: 400. Side Effects: 없음. */
    static int number(JsonNode node, String key, int min, int max, int fallback) {
        JsonNode value = node.path(key); int result = value.isMissingNode() ? fallback : value.asInt(Integer.MIN_VALUE);
        if ((!value.isMissingNode() && !value.isIntegralNumber()) || result < min || result > max) throw invalid(key + " 범위를 확인해 주세요.");
        return result;
    }
    /** 입력 오류를 만든다. Args: message 안내. Returns: 400 예외. Raises: 없음. Side Effects: 없음. */
    static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
    /** 동시 수정 오류를 만든다. Args: 없음. Returns: 409 예외. Raises: 없음. Side Effects: 없음. */
    static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT, "REPORT_REVISION_CHANGED", "자료가 변경되었습니다. 최신 버전을 다시 불러와 주세요."); }
}
