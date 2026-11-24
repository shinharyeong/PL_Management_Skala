package io.skala.backend.domain.master.service;

import io.skala.backend.domain.master.dto.PublishedMaster;
import java.util.Optional;

/**
 * master 발행 포트. 제출본 승인 처리(REQ-06)가 submission을 APPROVED로 바꾼 뒤 같은 트랜잭션 안에서 호출한다.
 * <ul>
 *   <li>요청을 보낸 대상 조직(selected, 제외 안 됨)이 모두 승인본을 가지면 master 새 버전을 만든다.</li>
 *   <li>각 조직은 가장 최근 승인본이 들어간다. 직전 master와 승인본 구성이 같으면 만들지 않는다.</li>
 *   <li>연간 계획은 첫 master가 생길 때 annual_plan을 CONFIRMED로 바꾼다(월간 요청을 보낼 수 있게 됨).</li>
 * </ul>
 * 아직 조건이 안 되면 비어 있다.
 */
public interface MasterPublisher {

    Optional<PublishedMaster> publishIfComplete(Long approvedSubmissionId);
}
