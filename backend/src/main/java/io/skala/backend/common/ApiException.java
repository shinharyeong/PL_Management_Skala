package io.skala.backend.common;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * 공개 가능한 업무 오류와 불변 필드 오류를 전달한다.
 * 원인 예외·파일 경로를 메시지에 넣지 않으며 생성 시 상태와 코드 형식을 검증한다.
 */
public final class ApiException extends RuntimeException {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_.\\[\\]-]{0,127}");

    private final HttpStatus status;
    private final String code;
    private final List<FieldError> errors;

    /**
     * 필드 오류가 없는 공개 업무 오류를 만든다.
     * Args: status는 4xx/5xx, code는 대문자 오류 코드, publicMessage는 공개용 설명이다.
     * Returns: 없음. 생성된 예외에 전달값을 보존한다.
     * Raises: IllegalArgumentException — 상태·코드·공개 설명이 유효하지 않을 때.
     * Side Effects: 없음. 파일·로그·DB를 쓰지 않는다.
     */
    public ApiException(HttpStatus status, String code, String publicMessage) {
        this(status, code, publicMessage, List.of());
    }

    /**
     * 필드별 오류를 함께 보존하는 공개 업무 오류를 만든다.
     * Args: status는 4xx/5xx, code는 오류 코드, publicMessage는 공개 설명,
     * errors는 null 원소가 없는 필드 오류 목록이다.
     * Returns: 없음. 목록은 불변 복사본으로 보존한다.
     * Raises: IllegalArgumentException — 상태·코드·설명·목록이 유효하지 않을 때.
     * Side Effects: 없음. 원인 예외나 내부 경로를 자동 수집하지 않는다.
     */
    public ApiException(HttpStatus status, String code, String publicMessage,
                        List<FieldError> errors) {
        super(requireMessage(publicMessage));
        if (status == null || !status.isError()) {
            throw new IllegalArgumentException("오류 HTTP 상태만 사용할 수 있습니다.");
        }
        if (errors == null || errors.stream().anyMatch(error -> error == null)) {
            throw new IllegalArgumentException("필드 오류 목록에 null을 사용할 수 없습니다.");
        }
        this.status = status;
        this.code = requireCode(code);
        this.errors = List.copyOf(errors);
    }

    /**
     * 오류의 HTTP 상태를 반환한다.
     * Args: 없음.
     * Returns: 생성 시 검증한 4xx/5xx 상태.
     * Raises: 없음.
     * Side Effects: 없음.
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * 기계 판별용 오류 코드를 반환한다.
     * Args: 없음.
     * Returns: 검증된 대문자 오류 코드.
     * Raises: 없음.
     * Side Effects: 없음.
     */
    public String code() {
        return code;
    }

    /**
     * 응답에 사용할 불변 필드 오류 목록을 반환한다.
     * Args: 없음.
     * Returns: 오류가 없으면 빈 목록.
     * Raises: 없음.
     * Side Effects: 없음.
     */
    public List<FieldError> errors() {
        return errors;
    }

    /**
     * 외부에 제공할 오류 코드의 형식을 확인한다.
     * Args: value는 검증할 오류 코드다.
     * Returns: 검증된 입력 코드.
     * Raises: IllegalArgumentException — 대문자·숫자·밑줄 형식이나 64자 제한 위반.
     * Side Effects: 없음.
     */
    private static String requireCode(String value) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("공개 오류 코드 형식이 올바르지 않습니다.");
        }
        return value;
    }

    /**
     * 호출자가 마련한 공개 설명의 빈 값과 길이를 확인한다.
     * Args: value는 원본 예외 메시지를 포함하지 않는 공개 설명이다.
     * Returns: 검증된 설명. 업무 내용을 추론하거나 수정하지 않는다.
     * Raises: IllegalArgumentException — null·공백이거나 512자를 초과할 때.
     * Side Effects: 없음. 내용의 공개 적합성은 호출자가 보장해야 한다.
     */
    private static String requireMessage(String value) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException("공개 오류 설명이 올바르지 않습니다.");
        }
        return value;
    }

    /**
     * 입력값 자체를 담지 않는 필드명·오류 코드다.
     * 자동 생성 접근자는 불변 값을 반환하며, 위험한 필드명은 request로 일반화한다.
     */
    public record FieldError(String field, String code) {

        /**
         * 공개할 필드 오류를 검증하고 내부 이름 노출을 제한한다.
         * Args: field는 DTO 필드 경로, code는 대문자 오류 코드다.
         * Returns: 없음. 자동 접근자는 정리된 두 값을 반환한다.
         * Raises: IllegalArgumentException — 오류 코드 형식이 유효하지 않을 때.
         * Side Effects: 없음. 허용 형식 밖 필드명은 request로 바꾼다.
         */
        public FieldError {
            field = field != null && FIELD.matcher(field).matches() ? field : "request";
            code = requireCode(code);
        }
    }
}
