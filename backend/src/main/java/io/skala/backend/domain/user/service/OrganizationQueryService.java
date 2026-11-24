package io.skala.backend.domain.user.service;

import io.skala.backend.domain.user.dto.OrganizationResponse;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.entity.Organization;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.repository.OrganizationRepository;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.response.PageResponse;
import io.skala.backend.global.util.OffsetCursor;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 조직 목록과 수신 담당자(REQ-01·ARCH-01). */
@Service
@RequiredArgsConstructor
public class OrganizationQueryService {

    private final OrganizationRepository organizationRepository;
    private final AppUserRepository userRepository;

    @Transactional(readOnly = true)
    public PageResponse<OrganizationResponse> list(String q, String cursor, Integer limit) {
        String keyword = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String scope = "org|" + keyword;
        int size = OffsetCursor.limit(limit);
        int offset = OffsetCursor.decode(cursor, scope);
        List<Organization> matched = organizationRepository.findAllByActiveTrueOrderByCodeAsc().stream()
                .filter(org -> keyword.isEmpty() || org.getCode().toLowerCase(Locale.ROOT).contains(keyword)
                        || org.getName().toLowerCase(Locale.ROOT).contains(keyword))
                .toList();
        List<Organization> page = matched.stream().skip(offset).limit(size).toList();
        Map<Long, List<OrganizationResponse.Manager>> managers = managersOf(page.stream().map(Organization::getId).toList());
        return PageResponse.of(page.stream().map(org -> toResponse(org, managers.getOrDefault(org.getId(), List.of()))).toList(),
                OffsetCursor.next(matched.size() > offset + size, offset, size, scope));
    }

    /** 조직별 사용 중인 조직 담당자 */
    @Transactional(readOnly = true)
    public Map<Long, List<OrganizationResponse.Manager>> managersOf(Collection<Long> organizationIds) {
        return userRepository.findAllByRoleAndActiveTrue(UserRole.ORG_MANAGER).stream()
                .filter(user -> user.getOrganization() != null && organizationIds.contains(user.getOrganization().getId()))
                .collect(Collectors.groupingBy(user -> user.getOrganization().getId(),
                        Collectors.mapping(OrganizationQueryService::toManager, Collectors.toList())));
    }

    public static OrganizationResponse toResponse(Organization org, List<OrganizationResponse.Manager> managers) {
        return new OrganizationResponse(String.valueOf(org.getId()), org.getCode(), org.getName(), managers);
    }

    private static OrganizationResponse.Manager toManager(AppUser user) {
        return new OrganizationResponse.Manager(String.valueOf(user.getId()), user.getName(), user.getEmail());
    }
}
