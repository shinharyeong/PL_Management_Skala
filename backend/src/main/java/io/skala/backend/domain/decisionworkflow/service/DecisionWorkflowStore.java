package io.skala.backend.domain.decisionworkflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.Baseline;
import io.skala.backend.domain.decisionworkflow.dto.DecisionWorkflowDtos.WorkItem;
import io.skala.backend.domain.master.service.MasterLabels;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 소유 기획자 또는 발행된 자기 조직 요청만 읽고 원본·이력 불변성을 보존한다. */
@Repository
@RequiredArgsConstructor
public class DecisionWorkflowStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 목적: 역할에 맞는 요청 목록을 조회한다. Args: actor 사용자, analysisId 선택 필터. Returns: 권한 범위의 요청 목록. Raises: 권한/DB 오류. Side Effects: DB 읽기. */
    public List<WorkItem> list(AuthenticatedUser actor, Long analysisId) {
        List<Object> args = new ArrayList<>();
        String where = scope(actor, args);
        if (analysisId != null) {
            where += " AND analysis_id=?";
            args.add(analysisId);
        }
        return jdbc.query("SELECT body FROM decision_work_item WHERE " + where + " ORDER BY updated_at DESC,id DESC",
                (rs, row) -> decode(rs.getString("body")), args.toArray());
    }

    /** 목적: 단일 요청의 조직·소유권을 검사한다. Args: actor 사용자, id 요청, lock 행 잠금 여부. Returns: 요청. Raises: 404 미소유/초안/미존재. Side Effects: DB 읽기 및 선택적 잠금. */
    public WorkItem get(AuthenticatedUser actor, long id, boolean lock) {
        List<Object> args = new ArrayList<>();
        String where = scope(actor, args);
        args.add(id);
        return jdbc.query("SELECT body FROM decision_work_item WHERE " + where + " AND id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> decode(rs.getString("body")), args.toArray()).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "요청을 찾을 수 없습니다."));
    }

    /** 목적: 동일 후보·가설의 중복 진행 요청을 막으며 초안을 저장한다. Args: initial 초안. Returns: 식별자가 있는 초안. Raises: 409 중복 진행, DB/직렬화 오류. Side Effects: 요청 DB 생성. */
    public WorkItem create(WorkItem initial) {
        Long id = jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('decision_work_item','id'))", Long.class);
        WorkItem saved = initial.toBuilder().id(String.valueOf(id)).build();
        try {
            jdbc.update("""
                    INSERT INTO decision_work_item(id,analysis_id,owner_id,organization_id,candidate_id,hypothesis_id,
                        revision,state_code,body,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?)
                    """, id, Long.parseLong(saved.analysisId()), Long.parseLong(saved.ownerId()), Long.parseLong(saved.organizationId()),
                    saved.candidateId(), saved.hypothesisId(), saved.revision(), saved.state().name(), encode(saved), saved.createdAt(), saved.updatedAt());
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_CHANGED, "같은 후보 또는 가설에 진행 중인 확인 요청이 있습니다.");
        }
        return saved;
    }

    /** 목적: 요청 revision을 비교하고 다음 상태를 저장한다. Args: actor 권한 사용자, expectedRevision 예상 버전, changed 다음 요청. Returns: 저장 상태. Raises: 409 경쟁 수정. Side Effects: DB 갱신. */
    public WorkItem update(AuthenticatedUser actor, int expectedRevision, WorkItem changed) {
        List<Object> args = new ArrayList<>(List.of(changed.revision(), changed.state().name(), encode(changed), changed.updatedAt()));
        String where = scope(actor, args);
        args.add(Long.parseLong(changed.id()));
        args.add(expectedRevision);
        int rows = jdbc.update("UPDATE decision_work_item SET revision=?,state_code=?,body=?::jsonb,updated_at=? WHERE "
                + where + " AND id=? AND revision=?", args.toArray());
        if (rows != 1) throw new BusinessException(ErrorCode.RESOURCE_CHANGED);
        return changed;
    }

    /** 목적: 요청 조직이 포함된 같은 연도 후속 확정본을 제시한다. Args: item 권한 검증된 요청. Returns: 후속 월 기준 목록, sourceCount는 해당 조직의 원천 수 1. Raises: DB 오류. Side Effects: DB 읽기. */
    public List<Baseline> followupBaselines(WorkItem item) {
        return jdbc.query("""
                SELECT m.id,p.year,r.month,m.seq,m.created_at FROM master_version m
                JOIN submission_request r ON r.id=m.request_id JOIN annual_plan p ON p.id=r.annual_plan_id
                JOIN master_source ms ON ms.master_id=m.id AND ms.organization_id=?
                WHERE r.type='MONTHLY_ACTUAL' AND p.year=? AND r.month>? ORDER BY r.month DESC,m.seq DESC
                """, (rs, row) -> new Baseline(rs.getString("id"), MasterLabels.monthly(rs.getInt("month"), rs.getInt("seq")),
                rs.getInt("year"), rs.getInt("month"), rs.getObject("created_at", OffsetDateTime.class), 1),
                Long.parseLong(item.organizationId()), item.year(), item.baseMonth());
    }

    /** 목적: 목록/상세/수정에 동일한 행 권한 조건을 사용한다. Args: actor 사용자, args SQL 인수. Returns: SQL 조건. Raises: 403 지원하지 않는 역할. Side Effects: args에 권한 인수 추가. */
    private String scope(AuthenticatedUser actor, List<Object> args) {
        if (actor.isPlanner()) {
            args.add(actor.id());
            return "owner_id=?";
        }
        actor.requireRole(UserRole.ORG_MANAGER);
        if (actor.organizationId() == null) throw new BusinessException(ErrorCode.FORBIDDEN);
        args.add(actor.organizationId());
        return "organization_id=? AND state_code<>'DRAFT' AND body @> '{\"events\":[{\"action\":\"ISSUED\"}]}'::jsonb";
    }

    /** 목적: 요청 상태를 직렬화한다. Args: value 요청. Returns: JSON. Raises: IllegalStateException 변환 실패. Side Effects: 없음. */
    private String encode(WorkItem value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("개선 요청 상태를 직렬화할 수 없습니다.", exception);
        }
    }

    /** 목적: 저장 요청을 복원한다. Args: value JSON. Returns: 요청. Raises: IllegalStateException 손상된 상태. Side Effects: 없음. */
    private WorkItem decode(String value) {
        try {
            return mapper.readValue(value, WorkItem.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("개선 요청 상태를 복원할 수 없습니다.", exception);
        }
    }
}
