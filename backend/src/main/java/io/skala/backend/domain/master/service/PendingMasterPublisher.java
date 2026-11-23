package io.skala.backend.domain.master.service;

import io.skala.backend.domain.master.entity.MasterVersion;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 임시 구현. 승인 처리가 먼저 개발될 수 있도록 아무것도 발행하지 않는다.
 * TODO(master): 월간·연간 master 발행 로직으로 교체
 */
@Slf4j
@Service
public class PendingMasterPublisher implements MasterPublisher {

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<MasterVersion> publishIfComplete(Long approvedSubmissionId) {
        log.debug("master 발행 미구현: submissionId={}", approvedSubmissionId);
        return Optional.empty();
    }
}
