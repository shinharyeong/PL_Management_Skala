package io.skala.backend.domain.decisionworkflow.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;
import static io.skala.backend.domain.decisionworkflow.dto.DecisionWorkflowDtos.*;

import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 상태 전이와 입력 후보 일치를 부작용 없이 검증하는 업무 규칙. */
@Component
public class DecisionWorkflowPolicy {
    /** 목적: 요청/분석의 경쟁 변경을 거절한다. Args: actual 현재 revision, expected 예상 revision. Returns: 없음. Raises: 409 불일치. Side Effects: 없음. */
    public void revision(int actual, int expected) {
        if (actual != expected || expected < 1) throw new BusinessException(ErrorCode.RESOURCE_CHANGED);
    }

    /** 목적: 허용된 선행 상태만 전이시킨다. Args: item 요청, allowed 선행 상태. Returns: 없음. Raises: 409 잘못된 전이. Side Effects: 없음. */
    public void state(WorkItem item, State... allowed) {
        if (Arrays.stream(allowed).noneMatch(state -> state == item.state())) {
            throw new BusinessException(ErrorCode.RESOURCE_CHANGED, "현재 요청 상태에서는 이 작업을 처리할 수 없습니다.");
        }
    }

    /** 목적: 요청 당시 후보가 중간에 변경되거나 사라지지 않았는지 검사한다. Args: item 고정 요청, analysis 현재 분석. Returns: 없음. Raises: 409 기준 후보 변경. Side Effects: 없음. */
    public void unchangedSource(WorkItem item, Analysis analysis) {
        Candidate current = analysis.candidates().stream().filter(candidate -> candidate.id().equals(item.candidateId())).findFirst().orElse(null);
        if (!Objects.equals(current, item.sourceCandidate())) {
            throw new BusinessException(ErrorCode.RESOURCE_CHANGED, "요청 후 분석의 대상 후보가 변경되었습니다. 최신 후보로 새 확인 요청을 만들어 주세요.");
        }
    }

    /** 목적: 회신을 수락 가능한 검토 후보로 변환한다. Args: item 요청. Returns: REVIEWED 후보. Raises: INVALID_REQUEST 실현 불가/근거 누락. Side Effects: 없음. */
    public Candidate acceptedCandidate(WorkItem item) {
        Reply reply = item.reply();
        if (reply == null || reply.feasibility() != Feasibility.FEASIBLE || reply.candidate() == null
                || reply.note() == null || reply.note().isBlank() || reply.evidenceRef() == null || reply.evidenceRef().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "실현 가능 회신과 근거가 있어야 후보를 수락할 수 있습니다.");
        }
        Candidate candidate = reply.candidate();
        return new Candidate(candidate.id(), candidate.organizationId(), candidate.title(), candidate.type(), candidate.amountWon(),
                candidate.contributionMarginPercent(), candidate.implementationCostWon(), candidate.embeddedEffectWon(),
                candidate.recognitionMonth(), candidate.category(), candidate.duplicateGroup(), EvidenceStatus.REVIEWED,
                reply.note(), reply.evidenceRef());
    }

    /** 목적: 외부 추진 결정에 최신 계산에서 검토된 동일 후보만 연결한다. Args: item 요청, analysis 계산된 분석. Returns: 없음. Raises: 409 후보/계산 변경, INVALID_REQUEST 제외 효과. Side Effects: 없음. */
    public void approvable(WorkItem item, Analysis analysis) {
        Candidate accepted = acceptedCandidate(item);
        Candidate current = analysis.candidates().stream().filter(candidate -> candidate.id().equals(item.candidateId())).findFirst().orElse(null);
        if (!accepted.equals(current) || analysis.result() == null || !"COMPLETED".equals(analysis.status())) {
            throw new BusinessException(ErrorCode.RESOURCE_CHANGED, "수락된 후보로 최신 시나리오를 다시 계산한 뒤 추진 결정을 기록해 주세요.");
        }
        boolean included = analysis.result().effects().stream().anyMatch(effect -> effect.candidateId().equals(item.candidateId())
                && effect.included() && effect.reviewed());
        if (!included) throw new BusinessException(ErrorCode.INVALID_REQUEST, "제약·중복·기간 검사에서 제외된 후보는 추진 승인 대상으로 기록할 수 없습니다.");
    }

    /** 목적: 미래의 회신/실행 기한만 허용한다. Args: dueAt 기한, now 기준시각. Returns: 없음. Raises: INVALID_REQUEST 잘못된 기한. Side Effects: 없음. */
    public void futureDeadline(OffsetDateTime dueAt, OffsetDateTime now) {
        if (dueAt == null || !dueAt.isAfter(now)) throw new BusinessException(ErrorCode.INVALID_REQUEST, "기한은 현재 시각 이후여야 합니다.");
    }

    /** 목적: 후속 비교 범위를 같은 연도·후속 월·담당 조직으로 제한한다. Args: item 요청, followup 확정본. Returns: 담당 조직 수치. Raises: INVALID_REQUEST 범위 오류. Side Effects: 없음. */
    public Organization followupOrganization(WorkItem item, Snapshot followup) {
        if (followup.year() != item.year() || followup.baseMonth() <= item.baseMonth()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 연도의 기준월 이후 확정 master만 비교할 수 있습니다.");
        }
        return followup.organizations().stream().filter(org -> org.id().equals(item.organizationId())).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "후속 확정 master에 담당 조직이 없습니다."));
    }

    /** 목적: 비어 있거나 과도한 입력 문장을 거절한다. Args: value 문장, maxLength 제한, label 필드명. Returns: 정리된 문장. Raises: INVALID_REQUEST 입력 오류. Side Effects: 없음. */
    public String text(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.length() > maxLength) throw new BusinessException(ErrorCode.INVALID_REQUEST, label + "을 확인해 주세요.");
        return value.strip();
    }
}
