package io.skala.backend.parser;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 측정된 대용량 응답을 허용하되 파서 경계의 유한 상한을 유지한다. */
class PythonParserClientTests {
    /** 현재 808행 자료의 응답을 수용하는 상한과 초과 설정 거부를 확인한다.
     * Args: 없음. Returns: 없음. Raises: AssertionError 상한 계약 불일치.
     * Side Effects: 메모리 객체만 생성하며 프로세스·파일·DB에 접근하지 않는다.
     */
    @Test
    void outputLimitAcceptsMeasuredCapacityAndRejectsAnUnboundedConfiguration() {
        var mapper = new ObjectMapper();
        new PythonParserClient(mapper, "python3", "../parser/src", 90, 100663296);
        assertThatThrownBy(() -> new PythonParserClient(mapper, "python3", "../parser/src", 90, 100663297))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 명시한 09 대용량 808행 원본을 실제 Java·Python 경계로 읽고 전량 수신한다.
     * Args: 없음; EXCEL_PARSER_LARGE_TEST_FILE에 현재 09/F66 조직 원본 경로를 받는다.
     * Returns: 없음. Raises: 파일·해시 오류 또는 AssertionError 응답·원본 불일치.
     * Side Effects: 지정 원본 읽기와 -B Python 자식 프로세스 실행만 수행하며 파일·DB 쓰기는 없다.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "EXCEL_PARSER_LARGE_TEST_FILE", matches = ".+")
    void largeWorkbookCrossesTheRealJavaPythonBoundaryWithoutDataLoss() throws Exception {
        var path = Path.of(System.getenv("EXCEL_PARSER_LARGE_TEST_FILE"));
        byte[] source = Files.readAllBytes(path);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        var parser = new PythonParserClient(new ObjectMapper(), "python3", "../parser/src", 90, 100663296);
        var result = parser.parse(path.getFileName().toString(), source);
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("errorCode").isNull()).isTrue();
        assertThat(result.path("submissionData").path("sourceSha256").asText()).isEqualTo(hash);
        assertThat(result.path("submissionData").path("lines").size()).isEqualTo(808);
        assertThat(result.path("records").size()).isEqualTo(result.path("summary").path("recordCount").asInt());
        assertThat(Files.readAllBytes(path)).isEqualTo(source);
    }
}
