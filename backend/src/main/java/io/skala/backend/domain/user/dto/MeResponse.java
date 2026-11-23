package io.skala.backend.domain.user.dto;

import io.skala.backend.domain.user.value.UserRole;
import java.util.List;

/** AUTH-04. 조직 담당자만 organization이 있고 경영기획팀은 null. */
public record MeResponse(User user, UserRole role, OrganizationRef organization, String homePath, List<Menu> menus) {

    public record User(String id, String name, String email) {
    }

    public record OrganizationRef(String id, String code, String name) {
    }

    public record Menu(String key, String label, String path) {
    }
}
