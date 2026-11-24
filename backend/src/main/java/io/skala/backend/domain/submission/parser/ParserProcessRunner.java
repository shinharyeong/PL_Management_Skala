package io.skala.backend.domain.submission.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 파서 브리지를 자식 프로세스로 실행한다(infrastructure.md "현재 파서 실행 방식").
 * stdin으로 엑셀(xlsx·xlsb) 바이트를 넣고 stdout JSON 한 건을 받는다. 제한 시간을 넘기면 프로세스를 끝내고 실패로 본다(NFR-REL-001).
 * TODO(infra): parser-worker·RabbitMQ(parse.jobs)로 옮기면 이 클래스를 큐 발행으로 바꾼다.
 */
@Slf4j
@Component
public class ParserProcessRunner {

    static final long MAX_OUTPUT_BYTES = 256L * 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final String python;
    private final Path sourceDirectory;
    private final Duration timeout;

    public ParserProcessRunner(ObjectMapper objectMapper,
                               @Value("${app.parser.python:${PARSER_PYTHON:python3}}") String python,
                               @Value("${app.parser.source-directory:${PARSER_SOURCE_DIRECTORY:../parser/src}}") String sourceDirectory,
                               @Value("${app.parser.timeout:PT5M}") Duration timeout) {
        this.objectMapper = objectMapper;
        this.python = python;
        this.sourceDirectory = Path.of(sourceDirectory).toAbsolutePath().normalize();
        this.timeout = timeout;
    }

    /** 실패해도 예외 대신 FAILED 결과를 돌려준다. errorCode: PARSER_TIMEOUT, PARSER_UNAVAILABLE, PARSER_OUTPUT_INVALID 등 */
    public ParserRun run(String filename, InputStream content) {
        ProcessBuilder builder = new ProcessBuilder(List.of(python, "-B", "-m", "excel_parser.service_bridge",
                "--filename", filename));
        builder.environment().put("PYTHONPATH", sourceDirectory.toString());
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            log.error("파서 실행 실패: {}", e.getMessage());
            return failed("PARSER_UNAVAILABLE");
        }
        try {
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readLimited(process.getInputStream()));
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readLimited(process.getErrorStream()));
            try (OutputStream stdin = process.getOutputStream()) {
                content.transferTo(stdin);
            } catch (IOException e) {
                // 파서가 입력 한도를 넘겨 먼저 닫은 경우. 결과 JSON으로 판단한다.
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return failed("PARSER_TIMEOUT");
            }
            byte[] out = stdout.get(10, TimeUnit.SECONDS);
            if (process.exitValue() != 0 && out.length == 0) {
                log.warn("파서 비정상 종료 exit={} stderr={}", process.exitValue(),
                        new String(stderr.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8).strip());
                return failed("PARSER_INTERNAL_ERROR");
            }
            return new ParserRun(objectMapper.readValue(out, ParserBridgeResponse.class), out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return failed("PARSER_INTERRUPTED");
        } catch (Exception e) {
            process.destroyForcibly();
            log.warn("파서 결과를 읽을 수 없음: {}", e.toString());
            return failed("PARSER_OUTPUT_INVALID");
        }
    }

    private static byte[] readLimited(InputStream in) {
        try (in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(chunk)) > 0) {
                total += n;
                if (total > MAX_OUTPUT_BYTES) {
                    throw new IOException("parser output too large");
                }
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ParserRun failed(String code) {
        return new ParserRun(new ParserBridgeResponse(null, null, null, "FAILED", code, List.of(), null), null);
    }

    /** 해석한 응답과 원본 JSON 바이트(결과 파일 보관용). 실행 실패면 raw는 null. */
    public record ParserRun(ParserBridgeResponse response, byte[] raw) {
    }
}
