package io.skala.backend.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 임시 파일 없이 OOXML 바이트를 Python 표준 입력으로 전달하는 경계. */
@Component
public class PythonParserClient {
    private final ObjectMapper mapper;
    private final String python;
    private final Path sourceDirectory;
    private final int timeoutSeconds;
    private final int maxOutputBytes;

    /** 실행 구성을 고정한다.
     * Args: mapper JSON 변환기, python 실행 파일, sourceDirectory 모듈 경로, timeoutSeconds 제한 초, maxOutputBytes 출력 한도.
     * Returns: 없음. Raises: IllegalArgumentException 잘못된 한도. Side Effects: 없음.
     */
    public PythonParserClient(ObjectMapper mapper,
            @Value("${app.parser.python:python3}") String python,
            @Value("${app.parser.source-directory:../parser/src}") String sourceDirectory,
            @Value("${app.parser.timeout-seconds:90}") int timeoutSeconds,
            @Value("${app.parser.max-output-bytes:100663296}") int maxOutputBytes) {
        if (timeoutSeconds < 1 || timeoutSeconds > 600 || maxOutputBytes < 1024 || maxOutputBytes > 100663296) {
            throw new IllegalArgumentException("파서 실행 한도 설정을 확인하세요.");
        }
        this.mapper = mapper;
        this.python = python;
        this.sourceDirectory = Path.of(sourceDirectory).toAbsolutePath().normalize();
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
    }

    /** 원본을 해석하고 엔진 결과를 반환하며 업무 승인·조직 검증은 수행하지 않는다.
     * Args: filename 검증된 원본 이름, bytes 원본 OOXML.
     * Returns: 프로토콜 1.0 결과 또는 안전한 FAILED 결과.
     * Raises: 없음; 프로세스 오류는 FAILED로 변환. Side Effects: 제한시간 내 자식 프로세스 실행, 파일 쓰기 없음.
     */
    public JsonNode parse(String filename, byte[] bytes) {
        Process process = null;
        var io = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var builder = new ProcessBuilder(python, "-B", "-m", "excel_parser.service_bridge", "--filename", filename);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            String path = System.getenv("PATH");
            builder.environment().clear();
            if (path != null) builder.environment().put("PATH", path);
            builder.environment().put("PYTHONPATH", sourceDirectory.toString());
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            process = builder.start();
            final Process running = process;
            var output = io.submit(() -> readBounded(running));
            var input = io.submit(() -> {
                try (var stdin = running.getOutputStream()) {
                    stdin.write(bytes);
                }
                return null;
            });
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                return failure("PARSER_TIMEOUT");
            }
            byte[] resultBytes = output.get(2, TimeUnit.SECONDS);
            JsonNode result = mapper.readTree(resultBytes);
            if (!validResult(result)) return failure("PARSER_PROTOCOL_INVALID");
            boolean failed = result.path("status").asText().equals("FAILED");
            if ((process.exitValue() != 0) != failed) return failure("PARSER_PROTOCOL_INVALID");
            if (!failed) input.get(2, TimeUnit.SECONDS);
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failure("PARSER_INTERRUPTED");
        } catch (Exception exception) {
            return failure(process == null ? "PARSER_UNAVAILABLE" : "PARSER_PROTOCOL_INVALID");
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                try {
                    process.getOutputStream().close();
                    process.getInputStream().close();
                    process.getErrorStream().close();
                } catch (IOException ignored) {
                    // 종료한 프로세스 스트림의 닫기 오류는 원래 파싱 결과를 바꾸지 않는다.
                }
            }
            io.shutdownNow();
        }
    }

    /** 엔진의 성공 주장보다 필수 프로토콜 타입과 범위를 먼저 확인한다.
     * Args: result 엔진 JSON. Returns: 1.0 필수 필드와 선택 미사용 양식 행 수의 유효 여부.
     * Raises: 없음. Side Effects: 없음.
     */
    private boolean validResult(JsonNode result) {
        if (result == null || !result.path("protocolVersion").asText().equals("1.0")
                || !Set.of("SUCCEEDED", "NEEDS_REVIEW", "FAILED").contains(result.path("status").asText())
                || !result.path("diagnostics").isArray() || !result.path("records").isArray()
                || !result.path("summary").isObject()) return false;
        for (String field : Set.of("sheetCount", "recordCount", "mappedCount", "unresolvedCount")) {
            JsonNode count = result.path("summary").path(field);
            if (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() < 0) return false;
        }
        JsonNode unusedCount = result.path("summary").path("unusedTemplateRowCount");
        if (!unusedCount.isMissingNode()
                && (!unusedCount.isIntegralNumber() || !unusedCount.canConvertToInt() || unusedCount.intValue() < 0)) return false;
        if (result.path("summary").path("recordCount").intValue() != result.path("records").size()) return false;
        JsonNode error = result.path("errorCode");
        if (error.isMissingNode() || (!error.isNull() && (!error.isTextual() || !error.asText().matches("[A-Z0-9_]{1,100}")))) return false;
        if (result.path("status").asText().equals("FAILED") && error.isNull()) return false;
        for (JsonNode diagnostic : result.path("diagnostics")) {
            if (!diagnostic.isObject() || !diagnostic.path("code").isTextual()
                    || !diagnostic.path("message").isTextual()
                    || !Set.of("ERROR", "WARNING").contains(diagnostic.path("severity").asText())
                    || !Set.of("FILE", "PARSING", "MAPPING", "COMPLETENESS", "COMPARISON").contains(diagnostic.path("stage").asText())) return false;
            JsonNode source = diagnostic.path("source");
            if (!source.isNull() && (!source.isObject() || !source.path("sheet").isTextual()
                    || !(source.path("cell").isTextual() || source.path("cell").isNull()))) return false;
        }
        return true;
    }

    /** 출력 상한 초과 시 자식 프로세스를 즉시 종료한다.
     * Args: process 실행 중 프로세스. Returns: 제한 이내 stdout 바이트.
     * Raises: IOException 읽기/한도 오류. Side Effects: 프로세스 출력 읽기, 상한 초과 시 종료.
     */
    private byte[] readBounded(Process process) throws IOException {
        try (InputStream stream = process.getInputStream()) {
            byte[] bytes = stream.readNBytes(maxOutputBytes + 1);
            if (bytes.length > maxOutputBytes) {
                process.destroyForcibly();
                throw new IOException("파서 출력 한도 초과");
            }
            return bytes;
        }
    }

    /** 내부 예외를 노출하지 않는 실패 프로토콜을 만든다.
     * Args: code 고정 운영 오류 코드. Returns: FAILED JSON. Raises: 없음. Side Effects: 없음.
     */
    public JsonNode failure(String code) {
        var result = mapper.createObjectNode();
        result.put("protocolVersion", "1.0").put("status", "FAILED").put("errorCode", code);
        result.putObject("summary").put("sheetCount", 0).put("recordCount", 0)
                .put("mappedCount", 0).put("unresolvedCount", 0).put("unusedTemplateRowCount", 0);
        result.putArray("records");
        result.putArray("diagnostics").addObject().put("code", code).put("severity", "ERROR")
                .put("stage", "PARSING").put("message", "파서 실행을 완료하지 못했습니다.").putNull("source");
        return result;
    }
}
