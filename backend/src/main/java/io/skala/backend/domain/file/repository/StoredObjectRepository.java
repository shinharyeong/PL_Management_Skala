package io.skala.backend.domain.file.repository;

import io.skala.backend.domain.file.entity.StoredObject;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredObjectRepository extends JpaRepository<StoredObject, String> {
}
