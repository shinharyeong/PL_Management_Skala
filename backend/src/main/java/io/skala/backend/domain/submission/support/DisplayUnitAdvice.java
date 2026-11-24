package io.skala.backend.domain.submission.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * `unit` 쿼리(WON·THOUSAND·MILLION·HUNDRED_MILLION)를 주면 금액 필드 옆에 화면 단위 값을 더한다(openapi Unit).
 * 원 단위 값은 그대로 두고, `amount` → `displayAmount`, `plan` → `displayPlan`, `values` → `displayValues`처럼 붙인다.
 * 천원·백만원은 정수, 억원은 소수 첫째 자리(반올림). 값이 없으면 null(0으로 바꾸지 않음). 비율(rate)은 바꾸지 않는다.
 * 제출·검토(submission·review)와 홈 손익(dashboard) 컨트롤러의 JSON 응답에 적용한다.
 * 금액 이름의 필드가 객체면(예: 홈 사업부별 손익의 `total`) 그 안을 다시 살핀다.
 */
@RestControllerAdvice(basePackages = {
        "io.skala.backend.domain.submission.controller",
        "io.skala.backend.domain.review.controller",
        "io.skala.backend.domain.dashboard.controller"})
public class DisplayUnitAdvice implements ResponseBodyAdvice<Object> {

    static final Set<String> MONEY_FIELDS = Set.of("amount", "baseline", "previous", "current", "plan", "actual",
            "diff", "change", "values",
            // 홈 손익(HOME-03~06)
            "vsPlanAmount", "vsPrevEstimateAmount", "revenue", "op", "opDiff", "total");
    private static final Pattern NUMBER = Pattern.compile("^-?[0-9]+(\\.[0-9]+)?$");
    private static final Map<String, Unit> UNITS = Map.of(
            "WON", new Unit(BigDecimal.ONE, 0),
            "THOUSAND", new Unit(new BigDecimal("1000"), 0),
            "MILLION", new Unit(new BigDecimal("1000000"), 0),
            "HUNDRED_MILLION", new Unit(new BigDecimal("100000000"), 1));

    private final ObjectMapper objectMapper;

    public DisplayUnitAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    record Unit(BigDecimal divisor, int scale) {
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body == null || !(request instanceof ServletServerHttpRequest servlet)
                || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return body;
        }
        String unitName = servlet.getServletRequest().getParameter("unit");
        if (unitName == null || unitName.isBlank()) {
            return body;
        }
        Unit unit = UNITS.get(unitName);
        if (unit == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "unit은 WON·THOUSAND·MILLION·HUNDRED_MILLION 중 하나입니다.");
        }
        if (body instanceof String || body instanceof byte[]) {
            return body;
        }
        JsonNode tree = objectMapper.valueToTree(body);
        addDisplay(tree, unit);
        return tree;
    }

    static void addDisplay(JsonNode node, Unit unit) {
        if (node instanceof ArrayNode array) {
            array.forEach(child -> addDisplay(child, unit));
            return;
        }
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
            fields.add(it.next());
        }
        for (Map.Entry<String, JsonNode> field : fields) {
            String name = field.getKey();
            JsonNode value = field.getValue();
            if (MONEY_FIELDS.contains(name)) {
                String displayName = "display" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
                if (value instanceof ArrayNode values) {
                    ArrayNode converted = object.arrayNode();
                    values.forEach(v -> converted.add(convert(v, unit)));
                    object.set(displayName, converted);
                } else if (value.isNull() || value.isTextual()) {
                    object.set(displayName, convert(value, unit));
                } else {
                    addDisplay(value, unit);
                }
            } else {
                addDisplay(value, unit);
            }
        }
    }

    private static JsonNode convert(JsonNode value, Unit unit) {
        if (value == null || value.isNull() || !value.isTextual() || !NUMBER.matcher(value.asText()).matches()) {
            return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        }
        BigDecimal scaled = new BigDecimal(value.asText()).divide(unit.divisor(), unit.scale(), RoundingMode.HALF_UP);
        return com.fasterxml.jackson.databind.node.TextNode.valueOf(scaled.toPlainString());
    }
}
