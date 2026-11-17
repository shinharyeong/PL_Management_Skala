package io.skala.backend.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 실제 HTTP 변환 경계에서 오류 안전성·추적·검증 계약을 확인한다.
 * fixture는 메모리에만 두고 테스트 서버·파일·로그 출력물을 생성하는 코드를 두지 않는다.
 */
class ApiExceptionHandlerTest {

    private MockMvc mvc;

    /**
     * 업무 컨트롤러와 독립된 메모리 MVC fixture를 준비한다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: 런타임 예외 — MVC fixture 구성이 잘못되면 테스트를 실패시킨다.
     * Side Effects: 메모리 MVC 객체만 생성한다. Spring 진단은 콘솔 설정을 따른다.
     */
    @BeforeEach
    void setUp() {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        mapper.addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new ApiRequestFilter())
                .build();
    }

    /**
     * 업무·라우팅·크기·내부 오류가 같은 안전한 오류 계약을 따르는지 확인한다.
     * Args: kind는 fixture 오류 종류, expectedStatus와 expectedCode는 기대 HTTP 결과다.
     * Returns: 없음.
     * Raises: Exception — 메모리 요청 처리 실패, AssertionError — 계약 불일치.
     * Side Effects: 메모리 HTTP 요청만 처리하며 원본·임시 파일을 사용하지 않는다.
     */
    @ParameterizedTest
    @CsvSource({
            "business, 422, INVALID_AMOUNT",
            "conflict, 409, RESOURCE_CHANGED",
            "busy, 503, PROCESSING_UNAVAILABLE",
            "authentication, 401, UNAUTHENTICATED",
            "forbidden, 403, FORBIDDEN",
            "missing, 404, RESOURCE_NOT_FOUND",
            "limit, 413, FILE_TOO_LARGE",
            "unexpected, 500, INTERNAL_ERROR"
    })
    void returnsSafeProblem(String kind, int expectedStatus, String expectedCode) throws Exception {
        mvc.perform(get("/api/v1/test/error/{kind}", kind)
                        .header(ApiRequestFilter.REQUEST_ID_HEADER, "test-request_01"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(ApiRequestFilter.REQUEST_ID_HEADER, "test-request_01"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.requestId").value("test-request_01"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(content().string(not(containsString("INTERNAL_ONLY_MARKER"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    /**
     * 허용된 업무 필드 오류만 JSON 확장 필드로 전달되는지 확인한다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: Exception — 요청 처리 실패, AssertionError — 필드 오류 불일치.
     * Side Effects: 메모리 요청·응답만 생성한다.
     */
    @Test
    void preservesPublicBusinessFieldErrors() throws Exception {
        mvc.perform(get("/api/v1/test/error/business"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_AMOUNT"));
    }

    /**
     * 동시에 처리 중인 명령의 안전한 재시도 안내를 확인한다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: Exception — 요청 처리 실패, AssertionError — 재시도 헤더 불일치.
     * Side Effects: 메모리 요청만 생성한다.
     */
    @Test
    void providesRetryAfterForInProgressCommand() throws Exception {
        mvc.perform(get("/api/v1/test/error/in-progress"))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
    }

    /**
     * 잘못된 JSON과 미정의 필드가 원문 노출 없이 400인지 확인한다.
     * Args: body는 메모리의 잘못된 JSON fixture다.
     * Returns: 없음.
     * Raises: Exception — 요청 처리 실패, AssertionError — 안전 응답 불일치.
     * Side Effects: 메모리 요청만 생성하며 파일을 만들지 않는다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"name\":\"ok\",\"INTERNAL_ONLY_MARKER\":true}"})
    void rejectsMalformedAndUnknownJson(String body) throws Exception {
        mvc.perform(post("/api/v1/test/echo").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().string(not(containsString("INTERNAL_ONLY_MARKER"))));
    }

    /**
     * Bean Validation 실패에서 거절된 값 대신 안전한 필드 식별자만 반환하는지 확인한다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: Exception — 요청 처리 실패, AssertionError — 검증 응답 불일치.
     * Side Effects: 메모리 요청만 생성한다.
     */
    @Test
    void returnsValidationFieldWithoutRejectedValue() throws Exception {
        mvc.perform(post("/api/v1/test/echo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_VALUE"))
                .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
    }

    /**
     * 요청 본문 형식과 메서드 오류의 HTTP 의미가 유지되는지 확인한다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: Exception — 요청 처리 실패, AssertionError — 상태·프로토콜 헤더 불일치.
     * Side Effects: 두 메모리 요청만 처리한다.
     */
    @Test
    void preservesMediaAndMethodErrors() throws Exception {
        mvc.perform(post("/api/v1/test/echo").contentType(MediaType.TEXT_PLAIN).content("text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(put("/api/v1/test/echo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ok\"}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("POST")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    /**
     * 잘못되거나 너무 긴 클라이언트 추적 ID를 UUID로 대체하는지 확인한다.
     * Args: supplied는 헤더에 사용할 유효하지 않은 식별자다.
     * Returns: 없음.
     * Raises: Exception — 요청 처리 실패, AssertionError — 추적 ID 계약 불일치.
     * Side Effects: 메모리 요청 속성과 응답 헤더만 생성한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"../private", "has space", "", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void replacesInvalidRequestIds(String supplied) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/test/error/unexpected")
                        .header(ApiRequestFilter.REQUEST_ID_HEADER, supplied))
                .andExpect(status().isInternalServerError()).andReturn();
        String actual = result.getResponse().getHeader(ApiRequestFilter.REQUEST_ID_HEADER);
        assertThat(actual).isNotEqualTo(supplied);
        assertThat(UUID.fromString(actual).toString()).isEqualTo(actual);
        assertThat(result.getResponse().getContentAsString()).contains(actual);
    }

    /**
     * 중복 추적 헤더를 반사하지 않고 성공 응답에도 캐시 제한을 적용하는지 확인한다.
     * Args: 없음.
     * Returns: 없음.
     * Raises: Exception — 요청 처리 실패, AssertionError — 헤더 계약 불일치.
     * Side Effects: 메모리 요청·응답만 생성한다.
     */
    @Test
    void replacesDuplicateIdsAndProtectsSuccessfulResponses() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/test/echo")
                        .header(ApiRequestFilter.REQUEST_ID_HEADER, "first-id", "second-id")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andReturn();
        String actual = result.getResponse().getHeader(ApiRequestFilter.REQUEST_ID_HEADER);
        assertThat(UUID.fromString(actual).toString()).isEqualTo(actual);
    }

    /** 실제 업무 처리 없이 공통 오류를 검증하는 HTTP fixture다. */
    @RestController
    static class TestController {

        /**
         * 지정한 안전성 검증용 예외를 발생시킨다.
         * Args: kind는 원본 데이터가 아닌 고정 fixture 이름이다.
         * Returns: 없음. 항상 예외를 던진다.
         * Raises: ApiException·NoResourceFoundException 등 선택한 fixture 예외.
         * Side Effects: 없음. 파일을 읽거나 쓰지 않는다.
         */
        @GetMapping("/api/v1/test/error/{kind}")
        public void failure(@PathVariable String kind) throws NoResourceFoundException {
            switch (kind) {
                case "business" -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "INVALID_AMOUNT", "금액을 확인해 주세요.",
                        List.of(new ApiException.FieldError("amount", "INVALID_AMOUNT")));
                case "conflict" -> throw new ApiException(HttpStatus.CONFLICT,
                        "RESOURCE_CHANGED", "최신 상태를 확인해 주세요.");
                case "busy" -> throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "PROCESSING_UNAVAILABLE", "잠시 후 다시 시도해 주세요.");
                case "authentication" -> throw new BadCredentialsException("INTERNAL_ONLY_MARKER");
                case "forbidden" -> throw new AccessDeniedException("INTERNAL_ONLY_MARKER");
                case "in-progress" -> throw new ApiException(HttpStatus.CONFLICT,
                        "REQUEST_IN_PROGRESS", "처리 중인 요청이 있습니다.");
                case "missing" -> throw new NoResourceFoundException(HttpMethod.GET,
                        "/INTERNAL_ONLY_MARKER/not-public.xlsx");
                case "limit" -> throw new MaxUploadSizeExceededException(1);
                default -> throw new IllegalStateException("INTERNAL_ONLY_MARKER/not-public.xlsx");
            }
        }

        /**
         * 유효한 요청이 공통 필터를 거쳐 성공하는 경로를 제공한다.
         * Args: body는 이름이 비어 있지 않은 JSON fixture다.
         * Returns: 입력 이름만 담은 메모리 응답.
         * Raises: 없음. 입력 변환·검증 예외는 MVC가 발생시킨다.
         * Side Effects: 없음.
         */
        @PostMapping(value = "/api/v1/test/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
        public Map<String, String> echo(@Valid @RequestBody TestRequest body) {
            return Map.of("name", body.name());
        }
    }

    /** 자동 생성 생성자·접근자로 이름을 보존하며 MVC가 NotBlank 검증을 수행하는 fixture다. */
    record TestRequest(@NotBlank String name) {
    }
}
