package io.skala.backend.parser;

import static io.skala.backend.submission.ApiData.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.skala.backend.common.ApiException;
import io.skala.backend.security.Actor;
import io.skala.backend.submission.SubmissionService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 승인 금융값이 아닌 현재 파서 후보의 제한된 읽기 전용 미리보기를 제공한다. */
@Service
public class ParsedRecordsService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final ObjectReader nestedReader;
    private final SubmissionService submissions;

    /** 저장 결과 조회와 기존 제출 권한 서비스를 받는다.
     * Args: jdbc DB, mapper JSON, submissions 권한 서비스.
     * Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public ParsedRecordsService(JdbcClient jdbc, ObjectMapper mapper, SubmissionService submissions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.nestedReader = mapper.readerFor(JsonNode.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.submissions = submissions;
    }

    /** 같은 조직 권한과 활성 시도에 묶인 표준 후보 페이지를 반환한다.
     * Args: actor 인증 사용자, submissionId 제출 ID, query limit/cursor.
     * Returns: summary·안전한 후보 items·nextCursor. Raises: 권한/404/파싱 중/커서/결과 오류.
     * Side Effects: DB 결과 바이트 읽기. 결과 전체 트리 대신 요청 페이지의 후보만 객체화한다.
     */
    public Map<String, Object> records(Actor actor, String submissionId, Map<String, String> query) {
        submissions.checkQuery(query, Set.of("limit", "cursor"));
        submissions.submission(actor, submissionId);
        int limit = limit(query.getOrDefault("limit", "50"));
        var job = jdbc.sql("SELECT status,attempt_no,result_object_key FROM parsing_job WHERE submission_id=?")
                .param(id(submissionId)).query().singleRow();
        if (Set.of("QUEUED", "RUNNING").contains(job.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "PARSING_NOT_READY", "현재 시도의 파싱이 완료되지 않았습니다.");
        }
        String scope = "parsed-records:" + actor.id() + ":" + actor.role() + ":" + submissionId
                + ":" + job.get("attempt_no") + ":" + job.get("result_object_key");
        int offset = offset(query.get("cursor"), scope);
        if (job.get("result_object_key") == null) {
            return object("summary", emptySummary(), "items", List.of(), "nextCursor", null);
        }
        byte[] bytes = jdbc.sql("SELECT payload FROM stored_object WHERE object_key=?")
                .param(job.get("result_object_key")).query(byte[].class).single();
        return page(bytes, offset, limit, scope);
    }

    /** 저장 JSON에서 요약과 지정 구간의 레코드만 DTO로 투영한다.
     * Args: bytes 저장 JSON, offset 시작, limit 페이지 크기, scope 커서 범위.
     * Returns: 미리보기 페이지; 구버전 결과의 미사용 양식 행 수는 0.
     * Raises: IllegalStateException 저장 JSON 손상.
     * Side Effects: 메모리 읽기만 수행하고 파싱·파일 저장·상태 변경 없음.
     */
    private Map<String, Object> page(byte[] bytes, int offset, int limit, String scope) {
        var items = new ArrayList<Map<String, Object>>();
        Map<String, Object> summary = emptySummary();
        int count = 0;
        try (JsonParser parser = mapper.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("잘못된 결과 구조");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("summary".equals(field)) {
                    JsonNode node = nestedReader.readValue(parser);
                    summary = object("sheetCount", node.path("sheetCount").asInt(), "recordCount", node.path("recordCount").asInt(),
                            "mappedCount", node.path("mappedCount").asInt(), "unresolvedCount", node.path("unresolvedCount").asInt(),
                            "unusedTemplateRowCount", node.path("unusedTemplateRowCount").asInt(0));
                } else if ("records".equals(field) && value == JsonToken.START_ARRAY) {
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parser.currentToken() == null) throw new IOException("잘못된 결과 배열");
                        if (count >= offset && items.size() < limit) items.add(project(nestedReader.readValue(parser)));
                        else parser.skipChildren();
                        count++;
                    }
                } else {
                    if (value == null) throw new IOException("잘못된 결과 필드");
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) throw new IOException("결과 뒤에 추가 JSON이 있습니다.");
        } catch (IOException exception) {
            throw new IllegalStateException("저장된 파싱 미리보기를 읽지 못했습니다.");
        }
        int next = offset + items.size();
        return object("summary", summary, "items", items, "nextCursor", next < count ? cursor(next, scope) : null);
    }

    /** 내부 경로·객체 키·원본 레코드 ID 없이 표시 필드만 선택한다.
     * Args: record 내부 표준 후보. Returns: 평탄 미리보기 DTO.
     * Raises: 없음. Side Effects: 없음; 숫자 금액은 문자열/null로 유지한다.
     */
    private Map<String, Object> project(JsonNode record) {
        JsonNode source = record.path("source"), organization = record.path("organization");
        JsonNode item = record.path("item"), period = record.path("period"), value = record.path("value");
        String scenario = text(period.get("scenario"));
        scenario = scenario == null ? null : scenario.toUpperCase(Locale.ROOT);
        if (scenario != null && !Set.of("PLAN", "ACTUAL", "ESTIMATE").contains(scenario)) scenario = null;
        String normalization = text(value.get("status"));
        normalization = normalization == null ? null : normalization.toUpperCase(Locale.ROOT);
        Object raw = record.path("raw_value").isBoolean() ? record.path("raw_value").booleanValue() : text(record.get("raw_value"));
        String amount = "amount".equals(value.path("value_kind").asText()) ? text(value.get("normalized_value")) : null;
        return object("sheet", text(source.get("sheet")), "cell", text(source.get("cell")),
                "organizationCode", text(organization.get("code")), "organizationName", text(organization.get("name")),
                "itemName", text(item.get("name")), "accountCode", text(item.get("code")),
                "year", integer(period.get("year")), "month", integer(period.get("month")),
                "scenario", scenario, "amount", amount, "amountUnit", text(value.get("source_unit")) == null ? null : "KRW", "normalizationStatus", normalization,
                "formula", text(record.path("formula").get("text")), "rawValue", raw);
    }

    /** 아직 결과가 없는 실패 작업의 빈 요약을 만든다.
     * Args: 없음. Returns: 0 카운트 사전. Raises: 없음. Side Effects: 없음.
     */
    private Map<String, Object> emptySummary() {
        return object("sheetCount", 0, "recordCount", 0, "mappedCount", 0, "unresolvedCount", 0, "unusedTemplateRowCount", 0);
    }

    /** nullable 원문·정밀 숫자를 문자열로 읽는다.
     * Args: value JSON scalar. Returns: 문자열 또는 null. Raises: 없음. Side Effects: 없음.
     */
    private String text(JsonNode value) { return value == null || value.isNull() || !value.isValueNode() ? null : value.asText(); }

    /** 확인된 연도·월만 정수로 읽는다.
     * Args: value JSON 정수. Returns: 정수 또는 null. Raises: 없음. Side Effects: 없음.
     */
    private Integer integer(JsonNode value) { return value != null && value.isIntegralNumber() && value.canConvertToInt() ? value.intValue() : null; }

    /** 페이지 크기를 서비스 공통 범위로 제한한다.
     * Args: value 정수 문자열. Returns: 1~100 정수.
     * Raises: ApiException 잘못된 크기. Side Effects: 없음.
     */
    private int limit(String value) {
        try {
            int limit = Integer.parseInt(value);
            if (limit < 1 || limit > 100) throw new NumberFormatException();
            return limit;
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "limit는 1~100 정수여야 합니다.");
        }
    }
}
