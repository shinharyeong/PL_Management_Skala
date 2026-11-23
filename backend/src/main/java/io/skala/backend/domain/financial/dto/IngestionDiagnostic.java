package io.skala.backend.domain.financial.dto;

/**
 * 적재 중 백엔드가 찾은 검증 오류. 파서 진단과 같은 모양으로 UP-05 수정 필요 표에 그대로 쓴다.
 *
 * @param sourceCell 시트!셀 (예: 손익제출!N40). 위치를 모르면 null
 * @param item       항목 이름(계정명·보고 항목명). 모르면 null
 * @param rule       규칙 코드(대문자·밑줄)
 * @param guide      조직 담당자에게 보여 줄 조치 안내
 */
public record IngestionDiagnostic(String sourceCell, String item, String rule, String guide) {
}
