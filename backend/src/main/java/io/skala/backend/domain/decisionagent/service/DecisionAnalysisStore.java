package io.skala.backend.domain.decisionagent.service;

import static io.skala.backend.domain.decisionagent.dto.DecisionAgentDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 분석 소유권을 모든 조회·수정 조건에 포함하고 기준 JSON을 변경하지 않는 저장소. */
@Repository
@RequiredArgsConstructor
public class DecisionAnalysisStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 목적: 본인 분석 목록을 읽는다. Args: ownerId 소유자. Returns: 최신 수정 순 목록. Raises: DB 오류. Side Effects: DB 읽기. */
    public List<AnalysisSummary> list(long ownerId) {
        return jdbc.query("SELECT id,title,revision,created_at FROM decision_agent_analysis WHERE owner_id=? ORDER BY updated_at DESC,id DESC",
                (rs, i) -> new AnalysisSummary(rs.getString("id"), rs.getString("title"), rs.getInt("revision"),
                        rs.getObject("created_at", OffsetDateTime.class)), ownerId);
    }

    /** 목적: 본인 분석의 상태를 읽고 필요 시 행을 잠근다. Args: ownerId 소유자, id 분석, lock 수정 잠금. Returns: 저장된 분석. Raises: 404 타인/미존재, 상태 손상. Side Effects: DB 읽기 및 선택적 행 잠금. */
    public Analysis get(long ownerId, long id, boolean lock) {
        return jdbc.query("SELECT state FROM decision_agent_analysis WHERE owner_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, i) -> decode(rs.getString("state")), ownerId, id).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "분석을 찾을 수 없습니다."));
    }

    /** 목적: 기준과 초기 상태를 한 트랜잭션에서 저장한다. Args: ownerId 소유자, initial 초기 분석. Returns: 식별자를 부여한 분석. Raises: JSON/DB 오류. Side Effects: DB 생성. */
    public Analysis create(long ownerId, Analysis initial) {
        Long id = jdbc.queryForObject("""
                INSERT INTO decision_agent_analysis(owner_id,master_id,revision,title,snapshot,state,created_at,updated_at)
                VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?) RETURNING id
                """, Long.class, ownerId, Long.parseLong(initial.snapshot().masterId()), initial.revision(), initial.title(),
                encode(initial.snapshot()), encode(initial), initial.createdAt(), initial.updatedAt());
        Analysis saved = new Analysis(String.valueOf(id), initial.title(), initial.prompt(), initial.revision(), initial.status(),
                initial.createdAt(), initial.updatedAt(), initial.targetProfitWon(), initial.protectedCategories(), initial.snapshot(),
                initial.candidates(), initial.result(), initial.narrative());
        jdbc.update("UPDATE decision_agent_analysis SET state=?::jsonb WHERE id=? AND owner_id=?", encode(saved), id, ownerId);
        return saved;
    }

    /** 목적: 예상 revision과 소유권을 확인한 뒤 가변 상태만 저장한다. Args: ownerId 소유자, expectedRevision 예상 버전, analysis 다음 상태. Returns: 저장한 상태. Raises: 409 경쟁 수정, JSON/DB 오류. Side Effects: 상태·revision 갱신. */
    public Analysis update(long ownerId, int expectedRevision, Analysis analysis) {
        int changed = jdbc.update("""
                UPDATE decision_agent_analysis SET revision=?,state=?::jsonb,updated_at=?
                WHERE id=? AND owner_id=? AND revision=?
                """, analysis.revision(), encode(analysis), analysis.updatedAt(), Long.parseLong(analysis.id()), ownerId, expectedRevision);
        if (changed != 1) throw new BusinessException(ErrorCode.RESOURCE_CHANGED);
        return analysis;
    }

    /** 목적: 상태를 JSON으로 직렬화한다. Args: value 상태. Returns: JSON. Raises: IllegalStateException 변환 실패. Side Effects: 없음. */
    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("분석 상태를 직렬화할 수 없습니다.", exception);
        }
    }

    /** 목적: 저장된 분석을 복원한다. Args: value JSON. Returns: 분석. Raises: IllegalStateException 손상된 상태. Side Effects: 없음. */
    private Analysis decode(String value) {
        try {
            return mapper.readValue(value, Analysis.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("저장된 분석 상태를 읽을 수 없습니다.", exception);
        }
    }
}
