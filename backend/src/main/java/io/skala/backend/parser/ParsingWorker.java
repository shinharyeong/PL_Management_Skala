package io.skala.backend.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** DB 대기열에서 한 작업씩 실행한다. 접수 트랜잭션이 커밋된 작업만 보인다. */
@Component
@EnableScheduling
@ConditionalOnProperty(name="app.parser.worker-enabled", havingValue="true", matchIfMissing=true)
public class ParsingWorker {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final PythonParserClient parser;
    private final ObjectMapper mapper;
    private final int timeoutSeconds;

    /** 워커 의존성과 고아 작업 제한시간을 받는다.
     * Args: jdbc DB, transactions 트랜잭션, parser 실행기, mapper JSON, timeoutSeconds 실행 제한 초.
     * Returns: 없음. Raises: 없음. Side Effects: 없음.
     */
    public ParsingWorker(JdbcClient jdbc, TransactionTemplate transactions, PythonParserClient parser, ObjectMapper mapper,
            @Value("${app.parser.timeout-seconds:90}") int timeoutSeconds) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.parser = parser;
        this.mapper = mapper;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 고아 작업을 실패 처리하고 가장 오래된 대기 작업 하나를 실행한다.
     * Args: 없음. Returns: 없음. Raises: DB 접근 오류(다음 스케줄에서 재시도).
     * Side Effects: DB 작업 상태·결과 변경, Python 프로세스 실행; 로컬 파일 쓰기 없음.
     */
    @Scheduled(fixedDelayString="${app.parser.poll-delay-ms:1000}")
    public void runNext() {
        jdbc.sql("""
                UPDATE parsing_job SET status='FAILED',error_code='PARSER_INTERRUPTED',finished_at=?
                WHERE status='RUNNING' AND started_at<?
                """).params(now(), now().minusSeconds(timeoutSeconds + 30L)).update();
        var candidates = jdbc.sql("""
                SELECT j.id,j.submission_id,j.attempt_no,s.source_sha256,s.original_filename,s.source_object_key
                FROM parsing_job j JOIN submission s ON s.id=j.submission_id
                WHERE j.status='QUEUED' AND s.status='DRAFT' ORDER BY j.id LIMIT 1
                """).query().listOfRows();
        if (candidates.isEmpty()) return;
        var job = candidates.getFirst();
        int claimed = jdbc.sql("""
                UPDATE parsing_job SET status='RUNNING',started_at=?
                WHERE id=? AND attempt_no=? AND status='QUEUED'
                """).params(now(), job.get("id"), job.get("attempt_no")).update();
        if (claimed == 0) return;
        byte[] source = jdbc.sql("SELECT payload FROM stored_object WHERE object_key=?")
                .param(job.get("source_object_key")).query(byte[].class).single();
        JsonNode result = io.skala.backend.submission.ApiData.hash(source).equals(job.get("source_sha256"))
                ? parser.parse(job.get("original_filename").toString(), source)
                : parser.failure("SOURCE_INTEGRITY_MISMATCH");
        persist(job, result);
    }

    /** 활성 시도·원본·DRAFT가 일치할 때만 결과를 원자적으로 연결한다.
     * Args: job 실행 당시 식별자, result 파서 프로토콜.
     * Returns: 없음. Raises: IllegalStateException 결과 직렬화 실패/DB 오류.
     * Side Effects: 결과 객체 저장 및 해당 작업 완료; 지연된 구시도 결과는 버림.
     */
    private void persist(Map<String, Object> job, JsonNode result) {
        byte[] serialized;
        try {
            serialized = mapper.writeValueAsBytes(result);
        } catch (Exception exception) {
            throw new IllegalStateException("파서 결과 직렬화 실패");
        }
        transactions.executeWithoutResult(transaction -> {
            var active = jdbc.sql("""
                    SELECT j.id FROM parsing_job j JOIN submission s ON s.id=j.submission_id
                    WHERE j.id=? AND j.submission_id=? AND j.attempt_no=? AND j.status='RUNNING'
                      AND s.status='DRAFT' AND s.source_sha256=? FOR UPDATE
                    """).params(job.get("id"), job.get("submission_id"), job.get("attempt_no"), job.get("source_sha256"))
                    .query(Long.class).list();
            if (active.isEmpty()) return;
            String objectKey = "result/" + UUID.randomUUID();
            jdbc.sql("INSERT INTO stored_object(object_key,content_type,payload) VALUES (?,'application/json',?)")
                    .params(objectKey, serialized).update();
            jdbc.sql("""
                    UPDATE parsing_job SET status=?,result_object_key=?,error_code=?,finished_at=?,
                        parser_version=?,contract_version=?,mapping_version=? WHERE id=?
                    """).params(result.path("status").asText(), objectKey, nullable(result, "errorCode"), now(),
                    nullable(result, "parserVersion"), nullable(result, "contractVersion"), nullable(result, "mappingVersion"), job.get("id")).update();
        });
    }

    /** 존재하지 않는 엔진 버전을 추정하지 않는다.
     * Args: result 프로토콜, field 선택 필드명. Returns: 문자열 또는 null. Raises: 없음. Side Effects: 없음.
     */
    private String nullable(JsonNode result, String field) {
        return result.hasNonNull(field) ? result.get(field).asText() : null;
    }

    /** UTC 서버 시각을 반환한다. Args: 없음. Returns: 현재 시각. Raises: 없음. Side Effects: 시계 읽기. */
    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
}
