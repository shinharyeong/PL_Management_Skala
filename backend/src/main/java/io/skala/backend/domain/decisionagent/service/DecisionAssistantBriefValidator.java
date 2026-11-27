package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAssistantDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/** 설명의 근거 연결·요구 항목 상태를 검증한다. 원문 일치만으로 해석의 진실성을 보증하지 않는다. */
final class DecisionAssistantBriefValidator {
    // 일반 명사 뒤 조사 '만'을 금액으로 보지 않되 명백한 수사·통화 단위는 계속 검사한다.
    private static final Pattern NUMERIC = Pattern.compile("[\\p{N}%]|퍼센트|절반|두\\s*배|세\\s*배|(?:[수일이삼사오육칠팔구십백천]*[십백천][수일이삼사오육칠팔구십백천]*\\s*(?:억|조|만)(?:\\s*원)?|(?<![가-힣])[수일이삼사오육칠팔구]+\\s*(?:(?:억|조)(?:\\s*원)?|만\\s*원))(?=$|[^가-힣]|[은는이가을를의에와과도]|으로|보다|부터|까지|입니다|이며|이고|인데|증가|감소|절감|달성|확보|필요|부족|초과|미달|수준|규모|가량|정도)|\\b(?:million|billion|percent)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSERTED = Pattern.compile("보장(?:합니다|됩니다|했습니다)|(?:반드시|무조건)\\s*(?:달성|실현|성공|절감|확보)|(?:달성|확보|실현|절감)(?:됩니다|합니다|했습니다|하였습니다)|(?:추진\\s*승인|발송|저장|삭제)(?:을|를)?\\s*(?:완료|했습니다|하였습니다|처리했습니다)");
    private final DecisionAssistantJson json;

    /** 목적: 원문 경로 확인에 사용할 직렬화기를 받는다. Args: json 정밀 JSON 변환기. Returns: 검증기. Raises: 없음. Side Effects: 없음. */
    DecisionAssistantBriefValidator(DecisionAssistantJson json) { this.json = json; }

    /** 목적: 검증된 인용에 연결한 설명과 질문별 완료 상태를 검사한다. Args: node brief JSON, citations 원장 검증을 통과한 전체 인용, sources 성공 원장, message 현재 질문, answer 최상위 답변, status 실제 실행 상태. Returns: 타입이 있는 보고 내용. Raises: IllegalArgumentException 필드·근거·상태 불일치. Side Effects: 없음. */
    Brief validate(JsonNode node, List<Citation> citations, Map<String, Observation> sources,
                   String message, String answer, String status) {
        fields(node, Set.of("summary", "requirements", "findings"));
        String summary = text(node.get("summary"), 1000);
        if (!summary.equals(answer) || message == null) throw new IllegalArgumentException("현재 질문의 설명 계약을 확인할 수 없습니다.");
        Set<Citation> allowed = new HashSet<>(citations), used = new HashSet<>();
        List<Finding> findings = new ArrayList<>(); Set<String> findingIds = new HashSet<>();
        for (JsonNode item : array(node.get("findings"), 8, false)) {
            fields(item, Set.of("id", "kind", "text", "citations", "uncertainty"));
            String id = identifier(item.get("id")), kind = text(item.get("kind"), 30), description = text(item.get("text"), 700);
            if (!findingIds.add(id) || !Set.of("FACT", "INTERPRETATION", "HYPOTHESIS").contains(kind))
                throw new IllegalArgumentException("설명 식별자 또는 종류가 올바르지 않습니다.");
            List<Citation> refs = new ArrayList<>(); Set<Citation> unique = new HashSet<>();
            for (JsonNode ref : array(item.get("citations"), 4, true)) {
                fields(ref, Set.of("observationId", "path", "quote"));
                Citation citation = new Citation(text(ref.get("observationId"), 100), text(ref.get("path"), 1000), text(ref.get("quote"), 2000));
                if (!allowed.contains(citation) || !unique.add(citation)) throw new IllegalArgumentException("설명에 실제로 검증한 근거가 필요합니다.");
                refs.add(citation); used.add(citation);
            }
            if (refs.stream().noneMatch(citation -> businessCitation(citation, sources)))
                throw new IllegalArgumentException("설명에는 실제 업무 자료의 인용이 필요합니다.");
            String uncertainty = optionalText(item.get("uncertainty"), 400);
            if (!kind.equals("FACT") && uncertainty == null) throw new IllegalArgumentException("해석과 가설에는 확인 한계가 필요합니다.");
            prose(description, refs, sources); if (uncertainty != null) prose(uncertainty, refs, sources);
            findings.add(new Finding(id, kind, description, List.copyOf(refs), uncertainty));
        }
        if (!used.equals(allowed)) throw new IllegalArgumentException("전체 인용과 설명 인용의 범위가 다릅니다.");
        prose(summary, citations, sources);
        List<Requirement> requirements = new ArrayList<>(); Set<String> requirementIds = new HashSet<>(); boolean unresolved = false;
        for (JsonNode item : array(node.get("requirements"), 6, true)) {
            fields(item, Set.of("id", "requestQuote", "question", "status", "findingIds", "missingInformation", "followUpQuestion"));
            String id = identifier(item.get("id")), quote = text(item.get("requestQuote"), 500), question = text(item.get("question"), 400), state = text(item.get("status"), 30);
            if (!requirementIds.add(id) || !message.contains(quote) || !Set.of("ANSWERED", "NEEDS_INPUT", "UNSUPPORTED").contains(state))
                throw new IllegalArgumentException("요구 항목은 현재 질문과 연결되어야 합니다.");
            List<String> refs = new ArrayList<>();
            for (JsonNode ref : array(item.get("findingIds"), 8, false)) {
                String findingId = identifier(ref);
                if (!findingIds.contains(findingId) || refs.contains(findingId)) throw new IllegalArgumentException("요구 항목의 설명 참조가 올바르지 않습니다.");
                refs.add(findingId);
            }
            String missing = optionalText(item.get("missingInformation"), 400), followUp = optionalText(item.get("followUpQuestion"), 400);
            if (state.equals("ANSWERED")) {
                if (refs.isEmpty() || missing != null || followUp != null) throw new IllegalArgumentException("해결된 요구 항목의 근거 또는 상태가 올바르지 않습니다.");
            } else {
                unresolved = true;
                if (missing == null || followUp == null) throw new IllegalArgumentException("미해결 항목에는 부족한 정보와 확인 질문이 필요합니다.");
                prose(missing, citations, sources); prose(followUp, citations, sources);
            }
            requirements.add(new Requirement(id, quote, question, state, List.copyOf(refs), missing, followUp));
        }
        if (status.equals("COMPLETED") && unresolved || status.equals("NEEDS_INPUT") && !unresolved)
            throw new IllegalArgumentException("전체 완료 상태와 요구 항목의 해결 여부가 다릅니다.");
        return new Brief(summary, List.copyOf(requirements), List.copyOf(findings));
    }

    /** 목적: 상세 조회의 위치·개수 같은 운반 메타데이터를 업무 근거로 인정하지 않는다. Args: citation 원장 검증 인용, sources 성공 관찰. Returns: 실제 업무 값의 인용 여부. Raises: 없음. Side Effects: 없음. */
    private boolean businessCitation(Citation citation, Map<String, Observation> sources) {
        Observation source = sources.get(citation.observationId());
        if (source == null) return false;
        if (source.tool().equals("observation_detail"))
            return citation.path().equals("/data/value") || citation.path().startsWith("/data/value/");
        return citation.path().startsWith("/data/");
    }

    /** 목적: 직접 만든 금액·보장·업무 실행 주장을 차단하되 실제 이름의 숫자는 보존한다. Args: value 설명, citations 이 설명의 검증 인용, sources 원장. Returns: 없음. Raises: IllegalArgumentException 근거 밖 수치·실행 주장. Side Effects: 없음; 해석의 의미 진실성까지 보증하지 않는다. */
    private void prose(String value, List<Citation> citations, Map<String, Observation> sources) {
        String inspected = value;
        List<String> names = new ArrayList<>();
        for (Citation citation : citations) {
            if (!(citation.path().endsWith("/name") || citation.path().endsWith("/organizationName"))) continue;
            JsonNode scalar = json.tree(sources.get(citation.observationId())).at(citation.path());
            if (scalar.isTextual() && scalar.textValue().equals(citation.quote())) names.add(citation.quote());
        }
        names.sort(Comparator.comparingInt(String::length).reversed());
        for (String name : names) inspected = inspected.replace(name, "해당 이름");
        if (NUMERIC.matcher(inspected).find() || ASSERTED.matcher(inspected).find())
            throw new IllegalArgumentException("설명에 근거 밖 숫자 또는 결과 보장·실행 주장이 있습니다.");
    }

    /** 목적: 필수 문자열의 길이·공백을 검사한다. Args: node 값, max 길이. Returns: 원문. Raises: IllegalArgumentException 계약 오류. Side Effects: 없음. */
    private String text(JsonNode node, int max) {
        if (node == null || !node.isTextual() || node.textValue().isBlank() || node.textValue().length() > max)
            throw new IllegalArgumentException("분석 설명 문자열이 올바르지 않습니다.");
        return node.textValue();
    }

    /** 목적: 명시적 null과 유효한 선택 문자열만 허용한다. Args: node 값, max 길이. Returns: 문자열 또는 null. Raises: IllegalArgumentException 계약 오류. Side Effects: 없음. */
    private String optionalText(JsonNode node, int max) { return node != null && node.isNull() ? null : text(node, max); }

    /** 목적: AI의 공통 Identifier와 같은 길이·공백 계약을 검사한다. Args: node 식별자. Returns: 검증 식별자. Raises: IllegalArgumentException 형식 오류. Side Effects: 없음. */
    private String identifier(JsonNode node) { return text(node, 100); }

    /** 목적: 배열의 최대 개수와 필수 원소 여부를 검사한다. Args: node 배열, max 상한, required 최소 원소 필요 여부. Returns: 원본 배열. Raises: IllegalArgumentException 계약 오류. Side Effects: 없음. */
    private JsonNode array(JsonNode node, int max, boolean required) {
        if (node == null || !node.isArray() || node.size() > max || required && node.isEmpty())
            throw new IllegalArgumentException("분석 설명 목록이 올바르지 않습니다.");
        return node;
    }

    /** 목적: 새 설명 객체의 누락·추가 필드를 차단한다. Args: node 객체, expected 허용 필드. Returns: 없음. Raises: IllegalArgumentException 계약 오류. Side Effects: 없음. */
    private void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("분석 설명 객체가 필요합니다.");
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException("분석 설명 필드가 다릅니다.");
    }
}
