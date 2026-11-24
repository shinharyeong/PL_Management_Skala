package io.skala.backend.domain.request.entity;

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
@Table(name = "request_target")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestTarget {

    @EmbeddedId
    private RequestTargetId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_plan_submission_id")
    private Submission confirmedPlanSubmission;

    /** 필수 대상에서 뺀 조직(master 생성 조건에서도 빠짐). selected와 달리 '대상 아님'을 뜻한다. */
    @Column(name = "excluded", nullable = false)
    private Boolean excluded = false;

    /**
     * 요청을 실제로 보낸 조직이면 true. false는 '아직 안 보냄'이라 업로드·요청함 대상이 아니지만, master는 전 조직 기준이라 행은 남긴다.
     * 제출을 받을 수 있는 대상은 selected AND NOT excluded.
     */
    @Column(name = "selected", nullable = false)
    private Boolean selected = true;

    public static RequestTarget of(Long requestId, Long organizationId, boolean selected) {
        RequestTarget target = new RequestTarget();
        target.id = new RequestTargetId(requestId, organizationId);
        target.selected = selected;
        return target;
    }
}
