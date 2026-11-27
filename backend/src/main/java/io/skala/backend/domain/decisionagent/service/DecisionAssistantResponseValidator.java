package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAssistantDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 모델이 제출한 추적·관찰·인용을 실제 capability 원장과 다시 대조한다. */
@Component
@RequiredArgsConstructor
public class DecisionAssistantResponseValidator {
    private static final Set<String> FIELDS = Set.of("mode", "status", "answer", "context", "toolCalls", "observations", "citations", "nextActions", "limitations");
    private static final Set<String> ACTIONS = Set.of("OPEN_DISCOVERY", "OPEN_SCENARIOS", "OPEN_WORKFLOW");
    // 일반 명사 뒤 조사 '만'을 금액으로 보지 않되 명백한 수사·통화 단위는 계속 검사한다.
    private static final Pattern NUMERIC = Pattern.compile("[\\p{N}%]|퍼센트|절반|두\\s*배|세\\s*배|(?:[수일이삼사오육칠팔구십백천]*[십백천][수일이삼사오육칠팔구십백천]*\\s*(?:억|조|만)(?:\\s*원)?|(?<![가-힣])[수일이삼사오육칠팔구]+\\s*(?:(?:억|조)(?:\\s*원)?|만\\s*원))(?=$|[^가-힣]|[은는이가을를의에와과도]|으로|보다|부터|까지|입니다|이며|이고|인데|증가|감소|절감|달성|확보|필요|부족|초과|미달|수준|규모|가량|정도)|\\b(?:million|billion|percent)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSERTED = Pattern.compile("확정|보장|반드시|(?:승인|달성|실현|검증|확보)\\s*완료|(?:달성|확보|실현|절감)(?:됩니다|합니다)");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private static final List<String> LIMITATIONS = List.of("이 대화는 조회·보고서 준비·사용자 확인용 초안을 제공하며 업무 데이터 저장·발송·승인을 실행하지 않습니다.",
            "금액은 실제 관찰 인용에서 확인하고 비교 기준과 조건부 효과를 구분해 주세요.");
    private final DecisionAssistantJson json;

    /** 목적: 서버 원장과 다른 모델 결과나 근거 없는 숫자 주장을 거부한다. Args: proposed AI 응답, run 실제 실행. Returns: 검증한 공개 응답. Raises: IllegalArgumentException 변조/계약 오류. Side Effects: 없음. */
    Response validate(JsonNode proposed, DecisionAssistantGateway.Run run) {
        Set<String> fields = new HashSet<>(FIELDS);
        if (proposed != null && proposed.has("brief")) fields.add("brief");
        exactFields(proposed, fields);
        if (json.size(proposed) > 256 * 1024 || !json.tree(run.context).equals(proposed.get("context"))
                || !json.tree(run.observations).equals(proposed.get("observations")) || !json.tree(run.calls()).equals(proposed.get("toolCalls")))
            throw new IllegalArgumentException("실제 조회 기록과 응답이 다릅니다.");
        String mode = text(proposed.get("mode"), 20); String status = text(proposed.get("status"), 30);
        if (!Set.of("MODEL", "UNAVAILABLE").contains(mode) || !Set.of("COMPLETED", "NEEDS_INPUT", "LIMIT_REACHED", "FAILED").contains(status)
                || (mode.equals("UNAVAILABLE") && status.equals("COMPLETED"))) throw new IllegalArgumentException("실행 상태가 잘못되었습니다.");
        String answer = text(proposed.get("answer"), 2000);
        boolean hasBrief = proposed.hasNonNull("brief");
        if (!hasBrief && (NUMERIC.matcher(answer).find() || ASSERTED.matcher(answer).find())) throw new IllegalArgumentException("근거 밖의 숫자·결과 주장을 허용하지 않습니다.");
        JsonNode rawCitations = array(proposed.get("citations"), hasBrief ? 32 : 12);
        Map<String, Observation> sources = new HashMap<>();
        run.observations.stream().filter(item -> item.status().equals("SUCCESS")).forEach(item -> sources.put(item.id(), item));
        List<Citation> citations = new ArrayList<>(); Set<Citation> seen = new HashSet<>();
        for (JsonNode item : rawCitations) {
            exactFields(item, Set.of("observationId", "path", "quote"));
            Citation citation = new Citation(text(item.get("observationId"), 100), text(item.get("path"), 1000), text(item.get("quote"), 2000));
            Observation source = sources.get(citation.observationId());
            if (source == null || !citation.path().startsWith("/") || Pattern.compile("~(?![01])").matcher(citation.path()).find() || !seen.add(citation)) throw new IllegalArgumentException("실제로 읽은 근거만 인용할 수 있습니다.");
            JsonNode scalar;
            try { scalar = json.tree(source).at(citation.path()); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("인용 경로가 잘못되었습니다."); }
            if (scalar.isMissingNode() || scalar.isContainerNode()) throw new IllegalArgumentException("인용 경로가 scalar가 아닙니다.");
            String original = scalar.asText();
            boolean exact = !scalar.isTextual() || DECIMAL.matcher(original).matches();
            if (exact ? !original.equals(citation.quote()) : !original.contains(citation.quote())) throw new IllegalArgumentException("인용이 실제 원문과 다릅니다.");
            citations.add(citation);
        }
        if (mode.equals("MODEL") && status.equals("COMPLETED") && (sources.isEmpty() || citations.isEmpty()))
            throw new IllegalArgumentException("완료 답변에는 성공 관찰과 인용이 필요합니다.");
        Brief brief = hasBrief ? new DecisionAssistantBriefValidator(json).validate(proposed.get("brief"), citations, sources, run.message, answer, status) : null;
        List<String> actions = new ArrayList<>();
        for (JsonNode item : array(proposed.get("nextActions"), 3)) {
            String action = text(item, 30);
            if (!ACTIONS.contains(action) || actions.contains(action)) throw new IllegalArgumentException("허용되지 않은 후속 동작입니다.");
            actions.add(action);
        }
        for (JsonNode item : array(proposed.get("limitations"), 30)) text(item, 2000);
        List<String> limitations = new ArrayList<>(LIMITATIONS);
        if (status.equals("LIMIT_REACHED")) limitations.add("이번 대화의 실행 한도에 도달했습니다. 조회한 근거를 확인하고 질문 범위를 좁혀 주세요.");
        if (status.equals("FAILED") || mode.equals("UNAVAILABLE")) limitations.add("모델 답변을 완료하지 못했습니다. 실제로 읽은 자료만 남겼습니다.");
        Response response = new Response(mode, status, answer, run.context, run.calls(), List.copyOf(run.observations), List.copyOf(citations), List.copyOf(actions), List.copyOf(limitations), brief);
        if (json.size(response) > 256 * 1024) throw new IllegalArgumentException("공개 응답 크기 한도를 초과했습니다.");
        return response;
    }

    /** 목적: AI 실패 시 실제 관찰만 보존하고 추측 답변을 만들지 않는다. Args: run 실행. Returns: 실패 응답. Raises: 없음. Side Effects: 없음. */
    Response unavailable(DecisionAssistantGateway.Run run) {
        return new Response("UNAVAILABLE", "FAILED", "대화 답변을 안전하게 완료하지 못했습니다. 조회된 자료를 확인하거나 질문을 다시 입력해 주세요.",
                run.context, run.calls(), List.copyOf(run.observations), List.of(), List.of(), LIMITATIONS);
    }

    /** 목적: JSON 객체의 추가·누락 필드를 거부한다. Args: node 객체, expected 필드 집합. Returns: 없음. Raises: IllegalArgumentException 불일치. Side Effects: 없음. */
    private void exactFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("응답 객체가 필요합니다.");
        Set<String> fields = new HashSet<>(); node.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(expected)) throw new IllegalArgumentException("응답 필드가 다릅니다.");
    }

    /** 목적: 문자열 계약을 검사한다. Args: node 문자열, max 최대 길이. Returns: 문자열. Raises: IllegalArgumentException 오류. Side Effects: 없음. */
    private String text(JsonNode node, int max) {
        if (node == null || !node.isTextual() || node.textValue().isBlank() || node.textValue().length() > max) throw new IllegalArgumentException("응답 문자열이 잘못되었습니다.");
        return node.textValue();
    }

    /** 목적: 응답 배열의 항목 수를 제한한다. Args: node 배열, max 상한. Returns: 배열. Raises: IllegalArgumentException 오류. Side Effects: 없음. */
    private JsonNode array(JsonNode node, int max) {
        if (node == null || !node.isArray() || node.size() > max) throw new IllegalArgumentException("응답 배열이 잘못되었습니다.");
        return node;
    }
}
