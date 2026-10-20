package com.homes.zipsai.building.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.building.domain.RuleDocument;
import com.homes.zipsai.building.domain.RuleDocumentIndexingOperation;
import com.homes.zipsai.building.domain.RuleDocumentIndexingOutbox;
import com.homes.zipsai.building.domain.RuleDocumentIndexingOutboxStatus;
import com.homes.zipsai.building.domain.RuleDocumentIndexingStatus;
import com.homes.zipsai.building.repository.RuleDocumentIndexingOutboxRepository;
import com.homes.zipsai.building.repository.RuleDocumentRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.conversation.ai.AiIndexingClient;
import com.homes.zipsai.conversation.ai.AiIndexingRequest;
import com.homes.zipsai.conversation.ai.AiIndexingResponse;
import com.homes.zipsai.conversation.ai.AiIndexingResponse.Result;
import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.global.util.TimeUtils;

@Service
public class RuleDocumentIndexingWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuleDocumentIndexingWorker.class);

    static final int BATCH_SIZE = 10;
    static final int MAX_RETRY_COUNT = 5;
    static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(10);
    static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(10);
    static final String AI_UNAVAILABLE = "AI_UNAVAILABLE";
    static final String AI_REJECTED = "AI_REJECTED";
    static final String MISSING_RESULT = "MISSING_RESULT";
    static final String UNKNOWN_STATUS = "UNKNOWN_STATUS";

    private final RuleDocumentIndexingOutboxRepository outboxRepository;
    private final RuleDocumentRepository documentRepository;
    private final StorageProperties storageProperties;
    private final ObjectProvider<AiIndexingClient> aiIndexingClient;
    private final TransactionTemplate tx;

    public RuleDocumentIndexingWorker(RuleDocumentIndexingOutboxRepository outboxRepository,
                                      RuleDocumentRepository documentRepository,
                                      StorageProperties storageProperties,
                                      ObjectProvider<AiIndexingClient> aiIndexingClient,
                                      TransactionTemplate tx) {
        this.outboxRepository = outboxRepository;
        this.documentRepository = documentRepository;
        this.storageProperties = storageProperties;
        this.aiIndexingClient = aiIndexingClient;
        this.tx = tx;
    }

    public int processDue() {
        AiIndexingClient client = aiIndexingClient.getIfAvailable();
        if (client == null) {
            LOGGER.error("AI 색인 클라이언트가 설정되지 않아 운영규칙 문서 색인을 보내지 못했습니다.");
            return 0;
        }
        List<Long> ids = outboxRepository.findDueIds(TimeUtils.now(), PageRequest.of(0, BATCH_SIZE));
        for (Long id : ids) {
            process(id, client);
        }
        return ids.size();
    }

    private void process(Long outboxId, AiIndexingClient client) {
        Task task = tx.execute(status -> prepare(outboxId));
        if (task == null) {
            return;
        }
        String previousTraceId = MDC.get("traceId");
        if (task.traceId() != null) {
            MDC.put("traceId", task.traceId());
        }
        try {
            if (task.skipReason() != null) {
                complete(outboxId, outbox -> outbox.markDone(TimeUtils.now()));
                LOGGER.info("운영규칙 문서 색인을 보내지 않고 완료했습니다. outboxId={}, documentId={}, reason={}",
                        outboxId, task.documentId(), task.skipReason());
                return;
            }
            AiIndexingResponse response;
            try {
                response = client.index(task.request());
            } catch (AiUnavailableException exception) {
                retry(task);
                return;
            } catch (RuntimeException exception) {
                fail(task, AI_REJECTED);
                LOGGER.error("AI가 운영규칙 문서 색인 요청을 거절했습니다. outboxId={}, operation={}, documentId={}",
                        outboxId, task.operation(), task.documentId(), exception);
                return;
            }
            apply(task, response);
        } finally {
            if (previousTraceId == null) {
                MDC.remove("traceId");
            } else {
                MDC.put("traceId", previousTraceId);
            }
        }
    }

    private Task prepare(Long outboxId) {
        RuleDocumentIndexingOutbox outbox = outboxRepository.findById(outboxId).orElse(null);
        if (outbox == null || outbox.getStatus() != RuleDocumentIndexingOutboxStatus.PENDING) {
            return null;
        }
        List<String> validDocumentIds = documentRepository.findValidIdsByBuildingId(outbox.getBuildingId())
                .stream().map(String::valueOf).toList();
        if (outbox.getOperation() == RuleDocumentIndexingOperation.CLEANUP) {
            return Task.send(outbox, new AiIndexingRequest(outbox.getBuildingId(), outbox.getTraceId(), null, null,
                    null, validDocumentIds));
        }
        Optional<RuleDocument> found = documentRepository.findWithAttachmentById(outbox.getDocumentId());
        if (found.isEmpty() || !found.get().isValid() || found.get().getDeletedAt() != null) {
            return Task.skip(outbox, "DOCUMENT_REMOVED");
        }
        RuleDocument document = found.get();
        if (outbox.getDocumentVersion() == null || document.getVersion() != outbox.getDocumentVersion()) {
            return Task.skip(outbox, "STALE_VERSION");
        }
        String fileKey = "s3://" + storageProperties.uploadBucket() + "/" + document.getAttachment().getFileKey();
        return Task.send(outbox, new AiIndexingRequest(outbox.getBuildingId(), outbox.getTraceId(),
                String.valueOf(document.getId()), document.getTitle(), fileKey, validDocumentIds));
    }

    private void apply(Task task, AiIndexingResponse response) {
        if (response.results().isEmpty()) {
            complete(task.outboxId(), outbox -> outbox.markDone(TimeUtils.now()));
            LOGGER.info("AI가 처리 결과 없이 운영규칙 문서 색인을 접수했습니다. outboxId={}, operation={}, documentId={}",
                    task.outboxId(), task.operation(), task.documentId());
            return;
        }
        logCleanupResult(task, response.cleanupResult());
        if (task.operation() == RuleDocumentIndexingOperation.CLEANUP) {
            Optional<Result> cleanup = response.cleanupResult();
            if (cleanup.isPresent() && AiIndexingResponse.STATUS_FAILED.equals(cleanup.get().status())) {
                complete(task.outboxId(), outbox -> outbox.markFailed(cleanup.get().reasonCode(), TimeUtils.now()));
            } else {
                complete(task.outboxId(), outbox -> outbox.markDone(TimeUtils.now()));
            }
            return;
        }
        Optional<Result> found = response.indexResult(String.valueOf(task.documentId()));
        if (found.isEmpty()) {
            fail(task, MISSING_RESULT);
            LOGGER.warn("AI 응답에 운영규칙 문서 색인 결과가 없습니다. outboxId={}, documentId={}",
                    task.outboxId(), task.documentId());
            return;
        }
        Result result = found.get();
        switch (result.status()) {
            case AiIndexingResponse.STATUS_SUCCEEDED -> succeed(task, RuleDocumentIndexingStatus.INDEXED, null);
            case AiIndexingResponse.STATUS_SKIPPED ->
                    succeed(task, RuleDocumentIndexingStatus.SKIPPED, result.reasonCode());
            case AiIndexingResponse.STATUS_FAILED -> fail(task, result.reasonCode());
            case null, default -> fail(task, UNKNOWN_STATUS);
        }
        LOGGER.info("운영규칙 문서 색인 결과를 저장했습니다. outboxId={}, documentId={}, status={}, reasonCode={}",
                task.outboxId(), task.documentId(), result.status(), result.reasonCode());
    }

    private void succeed(Task task, RuleDocumentIndexingStatus status, String reasonCode) {
        tx.executeWithoutResult(transaction -> {
            outboxRepository.findById(task.outboxId()).ifPresent(outbox -> outbox.markDone(TimeUtils.now()));
            documentRepository.updateIndexingResult(task.documentId(), task.documentVersion(), status, reasonCode);
        });
    }

    private void fail(Task task, String reason) {
        tx.executeWithoutResult(transaction -> {
            outboxRepository.findById(task.outboxId())
                    .ifPresent(outbox -> outbox.markFailed(reason, TimeUtils.now()));
            markDocumentFailed(task, reason);
        });
    }

    private void retry(Task task) {
        Boolean retrying = tx.execute(transaction -> {
            RuleDocumentIndexingOutbox outbox = outboxRepository.findById(task.outboxId()).orElse(null);
            if (outbox == null) {
                return false;
            }
            boolean scheduled = outbox.scheduleRetry(AI_UNAVAILABLE, MAX_RETRY_COUNT, BASE_RETRY_DELAY,
                    MAX_RETRY_DELAY, TimeUtils.now());
            if (!scheduled) {
                markDocumentFailed(task, AI_UNAVAILABLE);
            }
            return scheduled;
        });
        if (Boolean.TRUE.equals(retrying)) {
            LOGGER.warn("AI에 연결하지 못해 운영규칙 문서 색인을 다시 시도합니다. outboxId={}, operation={}, documentId={}",
                    task.outboxId(), task.operation(), task.documentId());
        } else {
            LOGGER.error("운영규칙 문서 색인 재시도 횟수를 넘겨 실패로 남깁니다. outboxId={}, operation={}, documentId={}",
                    task.outboxId(), task.operation(), task.documentId());
        }
    }

    private void markDocumentFailed(Task task, String reason) {
        if (task.operation() == RuleDocumentIndexingOperation.INDEX) {
            documentRepository.updateIndexingResult(task.documentId(), task.documentVersion(),
                    RuleDocumentIndexingStatus.FAILED, reason);
        }
    }

    private void complete(Long outboxId, Consumer<RuleDocumentIndexingOutbox> change) {
        tx.executeWithoutResult(transaction -> outboxRepository.findById(outboxId).ifPresent(change));
    }

    private static void logCleanupResult(Task task, Optional<Result> cleanup) {
        cleanup.filter(result -> !AiIndexingResponse.STATUS_SUCCEEDED.equals(result.status()))
                .ifPresent(result -> LOGGER.warn(
                        "AI 문서 정리가 완료되지 않았습니다. outboxId={}, buildingId={}, status={}, reasonCode={}",
                        task.outboxId(), task.buildingId(), result.status(), result.reasonCode()));
    }

    private record Task(Long outboxId, Long buildingId, Long documentId, Integer documentVersion,
                        RuleDocumentIndexingOperation operation, String traceId, AiIndexingRequest request,
                        String skipReason) {

        static Task send(RuleDocumentIndexingOutbox outbox, AiIndexingRequest request) {
            return new Task(outbox.getId(), outbox.getBuildingId(), outbox.getDocumentId(),
                    outbox.getDocumentVersion(), outbox.getOperation(), outbox.getTraceId(), request, null);
        }

        static Task skip(RuleDocumentIndexingOutbox outbox, String reason) {
            return new Task(outbox.getId(), outbox.getBuildingId(), outbox.getDocumentId(),
                    outbox.getDocumentVersion(), outbox.getOperation(), outbox.getTraceId(), null, reason);
        }
    }
}
