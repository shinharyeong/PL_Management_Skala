package io.skala.backend.domain.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.skala.backend.domain.report.exception.ReportErrorCode;
import io.skala.backend.global.exception.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 자료 틀(definition) 저장 봉투 검사. 기준은 AI 모델(ai/app/report_template/model.py StoredTemplate)에서 뽑은 JSON 스키마
 * {@code resources/report/template-definition.schema.json} 하나다(AI 테스트가 모델과 같은지 확인).
 * 봉투(schema_version·name·blocks[].id·kind)와 크기만 보고, 블록 세부 형식은 AI·화면이 정한다(열려 있음).
 */
@Component
public class TemplateDefinitionValidator {

    static final String SCHEMA = "report/template-definition.schema.json";
    /** 저장 크기 한도(직렬화 기준) */
    static final int MAX_BYTES = 1_000_000;
    private static final int MAX_ERRORS = 20;

    private final JsonSchema schema;

    public TemplateDefinitionValidator() {
        try (InputStream in = new ClassPathResource(SCHEMA).getInputStream()) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("자료 틀 스키마를 읽을 수 없습니다: " + SCHEMA, e);
        }
    }

    /** 형식이 맞지 않으면 INVALID_TEMPLATE(422), 필드 오류에 JSON 위치와 규칙 이름 */
    public void validate(JsonNode definition) {
        if (definition == null || !definition.isObject()) {
            throw ReportErrorCode.INVALID_TEMPLATE.exception(List.of(new BusinessException.FieldError("definition", "type")));
        }
        if (definition.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ReportErrorCode.INVALID_TEMPLATE.exception(List.of(new BusinessException.FieldError("definition", "maxSize")));
        }
        Set<ValidationMessage> messages = schema.validate(definition);
        if (!messages.isEmpty()) {
            throw ReportErrorCode.INVALID_TEMPLATE.exception(messages.stream()
                    .sorted(Comparator.comparing(m -> m.getInstanceLocation().toString()))
                    .limit(MAX_ERRORS)
                    .map(m -> new BusinessException.FieldError(
                            "definition" + m.getInstanceLocation().toString().substring(1), m.getType()))
                    .toList());
        }
    }
}
