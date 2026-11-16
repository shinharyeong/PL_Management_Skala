package io.skala.backend.submission;

import io.skala.backend.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 초안 API의 nullable DTO와 조회 커서를 위한 작은 변환 모음. */
public final class ApiData {
    /** 인스턴스 생성을 막는다. Args: 없음. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    private ApiData() {}

    /** null을 유지하는 순서 있는 DTO를 만든다.
     * Args: entries 키/값 쌍. Returns: JSON용 맵. Raises: IllegalArgumentException 홀수 인자. Side Effects: 없음.
     */
    public static Map<String, Object> object(Object... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("키와 값 쌍이 필요합니다.");
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }

    /** 양수 bigint 문자열을 검증한다.
     * Args: value 외부 ID. Returns: bigint 값. Raises: ApiException 잘못된 ID. Side Effects: 없음.
     */
    public static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "ID 형식이 올바르지 않습니다.");
        }
    }

    /** DB 날짜를 UTC 문자열로 변환한다.
     * Args: value nullable JDBC 시각. Returns: UTC 시각 또는 null.
     * Raises: IllegalArgumentException 미지원 JDBC 타입. Side Effects: 없음.
     */
    public static String instant(Object value) {
        if (value == null) return null;
        if (value instanceof OffsetDateTime time) return time.toInstant().toString();
        if (value instanceof Timestamp time) return time.toInstant().toString();
        throw new IllegalArgumentException("지원하지 않는 JDBC 시각 타입");
    }

    /** SHA-256을 계산한다.
     * Args: bytes 원본. Returns: 소문자 hex. Raises: IllegalStateException SHA-256 부재. Side Effects: 없음.
     */
    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    /** 필터·권한 범위에 결합된 커서를 해석한다. 목록은 스냅샷을 보장하지 않는다.
     * Args: cursor nullable 커서, scope 정규화된 조회 범위. Returns: 행 오프셋.
     * Raises: ApiException 잘못된/다른 범위 커서. Side Effects: 없음.
     */
    public static int offset(String cursor, String scope) {
        if (cursor == null) return 0;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 2);
            int offset = Integer.parseInt(parts[0]);
            if (offset < 0 || offset > 1000000 || parts.length != 2
                    || !parts[1].equals(hash(scope.getBytes(StandardCharsets.UTF_8)))) throw new IllegalArgumentException();
            return offset;
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "커서의 형식이나 조회 범위가 다릅니다.");
        }
    }

    /** 다음 조회 커서를 만든다.
     * Args: offset 다음 오프셋, scope 조회 범위. Returns: URL 안전 커서. Raises: 없음. Side Effects: 없음.
     */
    public static String cursor(int offset, String scope) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (offset + ":" + hash(scope.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8));
    }
}
