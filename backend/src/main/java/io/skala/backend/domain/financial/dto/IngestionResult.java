package io.skala.backend.domain.financial.dto;

import java.util.List;

/** 파서 결과 적재 결과. diagnostics가 비어 있어야 소명·제출로 넘어갈 수 있다. */
public record IngestionResult(int lineCount, int valueCount, List<IngestionDiagnostic> diagnostics) {

    public IngestionResult {
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean passed() {
        return diagnostics.isEmpty();
    }
}
