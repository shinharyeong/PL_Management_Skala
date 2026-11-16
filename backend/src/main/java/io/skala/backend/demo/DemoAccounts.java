package io.skala.backend.demo;

import io.skala.backend.common.ApiException;
import io.skala.backend.security.Actor;
import io.skala.backend.security.Organization;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

/** local+demo에서만 환경 자격과 합성 DB 사용자를 연결한다. 토큰은 DB·로그에 저장하지 않는다. */
@Component
@Profile("local & demo")
public class DemoAccounts {
    public static final String DEFAULT_ORGANIZATION_CODE = "F10";
    public static final List<String> ORGANIZATION_CODES = List.of(
            "F10", "F12", "F20", "F40", "F39", "F50", "F16", "F13", "F25",
            "F43", "F42", "F44", "F73", "F38", "F66", "F36", "F37");
    private final JdbcClient jdbc;
    private final String plannerEmail;
    private final String plannerToken;
    private final String organizationToken;
    private final Map<String, String> organizationTokens = new LinkedHashMap<>();
    private final Map<String, byte[]> organizationDigests = new LinkedHashMap<>();

    /** 환경 자격을 검증하고 F10에 환경 토큰, 나머지 사업부에 임시 토큰을 고정한다.
     * Args: jdbc DB, environment 서버 환경. Returns: 없음.
     * Raises: IllegalStateException 누락·충돌 자격·필수 암호화 실패.
     * Side Effects: 환경 읽기·메모리 난수/다이제스트 생성; 토큰 출력·DB 저장 없음. 재시작하면 추가 자격은 만료된다.
     */
    public DemoAccounts(JdbcClient jdbc, Environment environment) {
        this.jdbc = jdbc;
        plannerEmail = required(environment, "app.local-auth.planner-email");
        String configuredOrganizationEmail = required(environment, "app.local-auth.organization-email");
        plannerToken = required(environment, "app.local-auth.planner-token");
        organizationToken = required(environment, "app.local-auth.organization-token");
        if (plannerEmail.equalsIgnoreCase(configuredOrganizationEmail) || plannerEmail.length() > 320
                || configuredOrganizationEmail.length() > 320 || plannerToken.equals(organizationToken)
                || plannerToken.length() < 32 || organizationToken.length() < 32
                || plannerToken.chars().anyMatch(Character::isWhitespace)
                || organizationToken.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("데모 이메일·토큰 설정을 확인하세요.");
        }
        SecureRandom random = new SecureRandom();
        for (String code : ORGANIZATION_CODES) {
            if (plannerEmail.equalsIgnoreCase(organizationEmail(code))) {
                throw new IllegalStateException("설정 이메일과 사업부 체험 이메일이 충돌합니다.");
            }
            String value = code.equals(DEFAULT_ORGANIZATION_CODE) ? organizationToken : randomToken(random);
            organizationTokens.put(code, value);
            organizationDigests.put(code, digest(value));
        }
    }

    /** 기획자는 설정 이메일, 기본 담당자는 F10 전용 이메일을 반환한다.
     * Args: role PLANNER/ORG_MANAGER. Returns: 이메일.
     * Raises: IllegalArgumentException 미지원 역할. Side Effects: 없음.
     */
    public String email(String role) {
        return switch (role) {
            case "PLANNER" -> plannerEmail;
            case "ORG_MANAGER" -> organizationEmail(DEFAULT_ORGANIZATION_CODE);
            default -> throw new IllegalArgumentException("지원하지 않는 체험 역할입니다.");
        };
    }

    /** 허용된 사업부 전용 합성 이메일을 구성한다.
     * Args: code 17개 사업부 허용 코드. Returns: example.test의 합성 이메일.
     * Raises: IllegalArgumentException 미지원 코드. Side Effects: 없음.
     */
    public String organizationEmail(String code) {
        if (code == null || !ORGANIZATION_CODES.contains(code)) throw new IllegalArgumentException("지원하지 않는 체험 조직입니다.");
        return "demo-" + code.toLowerCase(Locale.ROOT) + "@example.test";
    }

    /** 검증된 개발 세션 응답에만 사용할 설정 토큰을 반환한다.
     * Args: role PLANNER/ORG_MANAGER. Returns: 서버 환경의 개발 Bearer.
     * Raises: IllegalArgumentException 미지원 역할. Side Effects: 없음; 로깅 금지.
     */
    public String token(String role) {
        return switch (role) {
            case "PLANNER" -> plannerToken;
            case "ORG_MANAGER" -> organizationToken;
            default -> throw new IllegalArgumentException("지원하지 않는 체험 역할입니다.");
        };
    }

    /** 선택한 허용 조직에 고정된 개발 자격만 반환한다.
     * Args: role 체험 역할, organizationCode 생략 가능한 조직 코드.
     * Returns: 기획자·F10의 환경 토큰 또는 시작 시 난수로 발급한 나머지 사업부 토큰.
     * Raises: ApiException 잘못된 역할·조직 조합. Side Effects: 없음; 토큰 로깅 금지.
     */
    public String token(String role, String organizationCode) {
        String code = selectedOrganization(role, organizationCode);
        return code == null ? token(role) : organizationTokens.get(code);
    }

    /** F10 환경 토큰과 나머지 사업부 토큰을 고정 역할·조직의 현재 DB 계정에 대조한다.
     * Args: candidate Bearer 후보 문자열. Returns: 일치하는 허용 Actor 또는 null.
     * Raises: BadCredentialsException 계정·역할·조직·활성 불일치, IllegalStateException 필수 해시 실패, DB 오류.
     * Side Effects: 메모리 비교·DB 읽기; 이전 토큰으로 변경된 소속이나 기획 권한을 얻을 수 없다.
     */
    public Actor actorForOrganizationToken(String candidate) {
        byte[] candidateDigest = digest(candidate);
        String code = null;
        for (var entry : organizationDigests.entrySet()) {
            if (MessageDigest.isEqual(entry.getValue(), candidateDigest)) code = entry.getKey();
        }
        if (code == null) return null;
        try {
            return actor("ORG_MANAGER", code);
        } catch (IllegalStateException exception) {
            throw new BadCredentialsException("체험 자격의 역할과 조직을 확인할 수 없습니다.");
        }
    }

    /** 기본 체험 계정의 활성 DB 역할·조직이 기획자 또는 F10 계약과 같은지 다시 확인한다.
     * Args: role 체험 역할. Returns: 토큰이 없는 Actor.
     * Raises: IllegalStateException 사용자·조직 충돌, DB 오류. Side Effects: DB 읽기.
     */
    public Actor actor(String role) {
        return actor(role, null);
    }

    /** 선택 조직의 허용 계정과 DB 역할·활성 상태·소속을 재확인한다.
     * Args: role 체험 역할, organizationCode 생략 가능한 조직 코드.
     * Returns: 선택한 조직의 토큰 없는 Actor.
     * Raises: ApiException 잘못된 선택, IllegalStateException 사용자·조직 충돌, DB 오류.
     * Side Effects: DB 읽기; 기존 사용자 소속을 바꾸지 않는다.
     */
    public Actor actor(String role, String organizationCode) {
        String code = selectedOrganization(role, organizationCode);
        String selectedEmail = code == null ? email(role) : organizationEmail(code);
        var rows = jdbc.sql("""
                SELECT u.id,u.email,u.name,u.role,u.organization_id,u.active,
                       o.code AS organization_code,o.name AS organization_name,o.active AS organization_active
                FROM app_user u LEFT JOIN organization o ON o.id=u.organization_id WHERE u.email=?
                """).param(selectedEmail).query().listOfRows();
        if (rows.size() != 1) throw new IllegalStateException("데모 사용자가 준비되지 않았습니다.");
        var row = rows.getFirst();
        boolean planner = role.equals("PLANNER") && row.get("organization_id") == null;
        boolean manager = role.equals("ORG_MANAGER") && code.equals(row.get("organization_code"))
                && Boolean.TRUE.equals(row.get("organization_active"));
        if (!role.equals(row.get("role")) || !Boolean.TRUE.equals(row.get("active")) || (!planner && !manager)) {
            throw new IllegalStateException("기존 사용자와 데모 역할·조직이 충돌합니다.");
        }
        Organization organization = planner ? null : new Organization(row.get("organization_id").toString(),
                row.get("organization_code").toString(), row.get("organization_name").toString());
        return new Actor(row.get("id").toString(), row.get("email").toString(), row.get("name").toString(), role, organization);
    }

    /** 조직 담당자에게만 활성 허용 계정의 조직 선택 목록을 반환한다.
     * Args: role 검증된 체험 역할. Returns: 사업부1~17 순서의 활성 조직 목록; 기획자는 빈 목록.
     * Raises: DB 오류. Side Effects: DB 읽기; 이메일·토큰은 반환하지 않는다.
     */
    public List<Organization> organizations(String role) {
        if (!"ORG_MANAGER".equals(role)) return List.of();
        var rows = jdbc.sql("""
                SELECT o.id,o.code,o.name,u.email FROM app_user u JOIN organization o ON o.id=u.organization_id
                WHERE u.role='ORG_MANAGER' AND u.active=TRUE AND o.active=TRUE
                """).query().listOfRows();
        var result = new ArrayList<Organization>();
        for (String code : ORGANIZATION_CODES) {
            String expectedEmail = organizationEmail(code);
            for (var row : rows) {
                if (code.equals(row.get("code")) && expectedEmail.equals(row.get("email"))) {
                    result.add(new Organization(row.get("id").toString(), code, row.get("name").toString()));
                }
            }
        }
        return List.copyOf(result);
    }

    /** 역할과 명시적 조직 선택을 허용 목록에 제한한다.
     * Args: role PLANNER/ORG_MANAGER, code 생략 가능한 조직 코드.
     * Returns: 기획자는 null, 기본 담당자는 F10, 명시한 담당자는 허용 사업부 코드.
     * Raises: ApiException 기획자의 조직 지정 또는 알 수 없는 역할·코드에 대한 400.
     * Side Effects: 없음.
     */
    private String selectedOrganization(String role, String code) {
        if ("PLANNER".equals(role) && code == null) return null;
        if ("ORG_MANAGER".equals(role)) {
            if (code == null) return DEFAULT_ORGANIZATION_CODE;
            if (ORGANIZATION_CODES.contains(code)) return code;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "체험 역할과 조직 선택을 확인하세요.");
    }

    /** 다른 역할의 공개된 개발 자격과 무관한 256비트 임시 Bearer를 발급한다.
     * Args: random JVM의 암호학적 난수 생성기. Returns: URL 안전 Base64 토큰.
     * Raises: 없음. Side Effects: 메모리 난수 생성; 토큰 출력·영속 저장 없음.
     */
    private String randomToken(SecureRandom random) {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    /** 개발 Bearer 비교에 사용할 고정 길이 다이제스트를 계산한다.
     * Args: value 개발 Bearer 후보. Returns: SHA-256 바이트 배열.
     * Raises: IllegalStateException 필수 해시 사용 실패. Side Effects: 메모리 계산만 수행.
     */
    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("체험 자격을 확인할 수 없습니다.");
        }
    }

    /** 필수 설정을 값 노출 없이 확인한다.
     * Args: environment 환경, key 설정 이름. Returns: 설정값.
     * Raises: IllegalStateException 누락. Side Effects: 환경 읽기.
     */
    private String required(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("데모 실행에 필요한 개발 인증 설정이 없습니다.");
        return value;
    }
}
