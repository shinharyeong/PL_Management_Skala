package io.skala.backend.domain.master.entity;

import io.skala.backend.domain.submission.entity.Submission;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "master_source")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MasterSource {

    @EmbeddedId
    private MasterSourceId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;
}
