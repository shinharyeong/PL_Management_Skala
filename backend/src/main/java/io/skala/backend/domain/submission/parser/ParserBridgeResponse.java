package io.skala.backend.domain.submission.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.skala.backend.domain.financial.dto.parser.ParserSubmissionData;
import java.util.List;

/**
 * parser/src/excel_parser/service_bridge.py 응답(protocolVersion 1.0).
 * status: SUCCEEDED / NEEDS_REVIEW(읽었지만 확인 필요) / FAILED(읽지 못함).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParserBridgeResponse(
        String protocolVersion,
        String parserVersion,
        String contractVersion,
        String status,
        String errorCode,
        List<ParserSubmissionData.Diagnostic> diagnostics,
        ParserSubmissionData submissionData
) {

    public ParserBridgeResponse {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
