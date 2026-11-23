package io.skala.backend.domain.master.dto;

import io.skala.backend.domain.request.value.RequestType;

/**
 * 새로 만든 master. REQ-06 응답의 masterPublished{id, label}에 쓴다.
 * type이 ANNUAL_PLAN이면 annual_master, MONTHLY_ACTUAL이면 master_version의 id다.
 */
public record PublishedMaster(Long id, RequestType type, String label) {
}
