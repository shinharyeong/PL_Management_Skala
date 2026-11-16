package io.skala.backend.security;

/**
 * 인증 계정에 연결된 활성 조직의 공개 식별 정보를 보관한다.
 *
 * <p>Args: id는 십진 문자열 조직 ID, code는 조직 코드, name은 표시 이름이다.
 * Returns: record가 생성하는 접근자는 해당 식별 정보만 반환한다.
 * Raises: 생성 시 추가 검증은 없으며 ActorResolver가 DB 상태를 검증한다.
 * Side Effects: 없음. 원본 계정·토큰·DB 연결을 보관하지 않는다.
 */
public record Organization(String id, String code, String name) {
}
