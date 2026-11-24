package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.file.entity.StoredObject;
import io.skala.backend.domain.file.service.FileStorage;
import io.skala.backend.domain.submission.entity.ParsingJob;
import io.skala.backend.domain.submission.parser.ParserProcessRunner;
import io.skala.backend.domain.submission.repository.ParsingJobRepository;
import io.skala.backend.domain.submission.value.ParsingJobStatus;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * parsing_job을 DB 작업 큐로 쓰는 실행기. 등록 트랜잭션이 커밋된 뒤 자체 스레드 풀에서 파서를 돌린다.
 * 서버가 재시작돼 남은 QUEUED 작업은 다시 실행하고, 제한 시간을 넘긴 RUNNING 작업은 실패로 정리한다(NFR-REL-001).
 */
@Slf4j
@Component
public class ParsingJobDispatcher {

    private final ParsingJobRepository parsingJobRepository;
    private final ParsingResultService parsingResultService;
    private final ParserProcessRunner parserProcessRunner;
    private final FileStorage fileStorage;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration timeout;
    private final boolean sweeperEnabled;
    private final ExecutorService executor;

    public ParsingJobDispatcher(ParsingJobRepository parsingJobRepository, ParsingResultService parsingResultService,
                                ParserProcessRunner parserProcessRunner, FileStorage fileStorage,
                                TransactionTemplate transaction, Clock clock,
                                @Value("${app.parser.timeout:PT5M}") Duration timeout,
                                @Value("${app.parser.max-concurrency:2}") int maxConcurrency,
                                @Value("${app.parser.sweeper.enabled:true}") boolean sweeperEnabled) {
        this.parsingJobRepository = parsingJobRepository;
        this.parsingResultService = parsingResultService;
        this.parserProcessRunner = parserProcessRunner;
        this.fileStorage = fileStorage;
        this.transaction = transaction;
        this.clock = clock;
        this.timeout = timeout;
        this.sweeperEnabled = sweeperEnabled;
        this.executor = Executors.newFixedThreadPool(Math.max(1, maxConcurrency), runnable -> {
            Thread thread = new Thread(runnable, "parsing-job");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 현재 트랜잭션이 커밋되면 실행한다. 트랜잭션 밖이면 바로 실행 요청. */
    public void dispatchAfterCommit(Long jobId, int attemptNo) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(jobId, attemptNo);
                }
            });
        } else {
            submit(jobId, attemptNo);
        }
    }

    private void submit(Long jobId, int attemptNo) {
        try {
            executor.execute(() -> run(jobId, attemptNo));
        } catch (RejectedExecutionException e) {
            log.warn("파싱 작업 {} 실행 예약 실패. 정리 작업이 다시 시도합니다.", jobId);
        }
    }

    /** 작업 하나를 실행한다. 다른 실행자가 먼저 가져갔으면 아무것도 하지 않는다. */
    void run(Long jobId, int attemptNo) {
        Boolean claimed = transaction.execute(status ->
                parsingJobRepository.claim(jobId, attemptNo, OffsetDateTime.now(clock)) == 1);
        if (!Boolean.TRUE.equals(claimed)) {
            return;
        }
        try {
            Source source = transaction.execute(status -> {
                ParsingJob job = parsingJobRepository.findById(jobId).orElseThrow();
                StoredObject object = job.getSubmission().getSourceObject();
                object.getObjectKey();
                return new Source(job.getSubmission().getOriginalFilename(), object);
            });
            ParserProcessRunner.ParserRun response;
            try (InputStream content = fileStorage.open(source.object())) {
                response = parserProcessRunner.run(source.filename(), content);
            }
            parsingResultService.apply(jobId, attemptNo, response.response(), response.raw());
        } catch (Exception e) {
            log.error("파싱 작업 {} 실패", jobId, e);
            parsingResultService.fail(jobId, attemptNo, "PARSER_INTERNAL_ERROR");
        }
    }

    /** 1분마다(global SchedulingConfig): 오래 대기 중인 작업은 다시 실행, 제한 시간을 넘긴 작업은 실패 처리 */
    @Scheduled(fixedDelayString = "${app.parser.sweep-interval:PT1M}", initialDelayString = "PT30S")
    public void sweep() {
        if (!sweeperEnabled) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        parsingJobRepository.findAllByStatusAndCreatedAtBefore(ParsingJobStatus.QUEUED, now.minusMinutes(1))
                .forEach(job -> submit(job.getId(), job.getAttemptNo()));
        parsingJobRepository.findAllByStatusAndStartedAtBefore(ParsingJobStatus.RUNNING,
                        now.minus(timeout).minusMinutes(1))
                .forEach(job -> parsingResultService.fail(job.getId(), job.getAttemptNo(), "PARSER_TIMEOUT"));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private record Source(String filename, StoredObject object) {
    }
}
