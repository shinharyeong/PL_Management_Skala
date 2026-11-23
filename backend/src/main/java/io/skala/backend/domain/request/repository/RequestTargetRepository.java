package io.skala.backend.domain.request.repository;

import io.skala.backend.domain.request.entity.RequestTarget;
import io.skala.backend.domain.request.entity.RequestTargetId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestTargetRepository extends JpaRepository<RequestTarget, RequestTargetId> {
}
