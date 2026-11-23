package io.skala.backend.domain.master.service;

import io.skala.backend.domain.master.entity.MasterVersion;
import java.util.Optional;

/**
 * master 발행 포트. 제출본 승인 처리가 같은 트랜잭션 안에서 호출한다.
 * 대상 조직이 모두 승인됐으면 master 새 버전을 만들어 돌려주고, 아직이면 비어 있다.
 */
public interface MasterPublisher {

    Optional<MasterVersion> publishIfComplete(Long approvedSubmissionId);
}
