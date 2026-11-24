package io.skala.backend.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 조직과 수신 담당자(조직 담당자 계정). managers는 목록 화면에서만 채운다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrganizationResponse(String id, String code, String name, List<Manager> managers) {

    public record Manager(String id, String name, String email) {
    }
}
