package io.skala.backend.domain.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/**
 * 자료 틀. 목록(RPT-03)에서는 version·definition이 null이고, 저장(RPT-04·05)·불러오기(RPT-06)에서는 그 버전의 정의를 준다.
 * definition 봉투 형식은 resources/report/template-definition.schema.json(AI 모델 StoredTemplate 기준), 블록 세부는 AI·화면 형식.
 */
public record TemplateResponse(String id, String name, int latestVersion, Integer version, JsonNode definition,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
