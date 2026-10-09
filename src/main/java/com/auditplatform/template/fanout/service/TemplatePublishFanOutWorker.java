package com.auditplatform.template.fanout.service;

import com.auditplatform.template.fanout.concurrency.DownstreamCapacityThrottler;
import com.auditplatform.template.fanout.concurrency.FairTenantQueue;
import com.auditplatform.template.fanout.concurrency.RetryPolicy;
import com.auditplatform.template.fanout.model.*;
import com.auditplatform.template.fanout.port.*;
import com.auditplatform.template.fanout.port.DownstreamRehydrationClient.DownstreamPermanentException;
import com.auditplatform.template.fanout.port.DownstreamRehydrationClient.DownstreamTransientException;
import com.auditplatform.template.fanout.port.IdempotencyStore.ClaimStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Staff-level Template-Publish Fan-Out Worker.
 *
 * Coordinates the fan-out of product template updates across thousands of tenant engagements.
 * Strictly respects downstream capacity (~1 min rehydration calls owned by another team),
 * enforces multi-tenant scheduling fairness, guarantees idempotency against at-least-once message delivery,
 * handles transient errors with exponential backoff & jitter, and routes poison pills to a DLQ.
 */
public class TemplatePublishFanOutWorker implements AutoCloseable {

    private final EngagementCatalogPort catalogPort;
    private final DownstreamRehydrationClient downstreamClient;
    private final IdempotencyStore idempotencyStore;
    private final DeadLetterQueuePort dlqPort;
    private final MetricsPort metricsPort;

    private final DownstreamCapacityThrottler capacityThrottler;
    private final RetryPolicy retryPolicy;
    private final ExecutorService workerExecutor;
    private final Duration permitAcquisitionTimeout;
    private final Duration idempotencyTtl;
    private final int pageSize;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    public record FanOutSummary(
            String eventId,
            String productId,
            String targetVersion,
            long totalDiscovered,
            long successful,
            long skipped,
            long deadLettered,
            Duration totalDuration
    ) {}

    public TemplatePublishFanOutWorker(
            EngagementCatalogPort catalogPort,
            DownstreamRehydrationClient downstreamClient,
            IdempotencyStore idempotencyStore,
            DeadLetterQueuePort dlqPort,
            MetricsPort metricsPort,
            int maxDownstreamCapacity,
            RetryPolicy retryPolicy,
            ExecutorService workerExecutor,
            Duration permitAcquisitionTimeout,
            Duration idempotencyTtl,
            int pageSize
    ) {
        this.catalogPort = Objects.requireNonNull(catalogPort, "catalogPort must not be null");
        this.downstreamClient = Objects.requireNonNull(downstreamClient, "downstreamClient must not be null");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore must not be null");
        this.dlqPort = Objects.requireNonNull(dlqPort, "dlqPort must not be null");
        this.metricsPort = Objects.requireNonNull(metricsPort, "metricsPort must not be null");
        this.capacityThrottler = new DownstreamCapacityThrottler(maxDownstreamCapacity);
        this.retryPolicy = (retryPolicy != null) ? retryPolicy : RetryPolicy.standard();
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor must not be null");
        this.permitAcquisitionTimeout = Objects.requireNonNull(permitAcquisitionTimeout, "permitAcquisitionTimeout must not be null");
        this.idempotencyTtl = Objects.requireNonNull(idempotencyTtl, "idempotencyTtl must not be null");
        this.pageSize = Math.max(10, pageSize);
    }

    public FanOutSummary processTemplatePublishedEvent(TemplatePublishedEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Instant startTime = Instant.now();

        long totalDiscovered = 0;
        long successfulCount = 0;
        long skippedCount = 0;
        long dlqCount = 0;

        FairTenantQueue tenantQueue = new FairTenantQueue(3);

        int pageOffset = 0;
        while (!isShutdown.get()) {
            List<EngagementReference> page = catalogPort.findActiveEngagementsByProduct(event.productId(), pageOffset, pageSize);
            if (page == null || page.isEmpty()) {
                break;
            }
            totalDiscovered += page.size();

            for (EngagementReference ref : page) {
                if (!ref.activeBranch().equalsIgnoreCase(event.branchId())) {
                    skippedCount++;
                    metricsPort.recordTaskSkipped("BRANCH_MISMATCH");
                    continue;
                }
                if (ref.currentVersion().equalsIgnoreCase(event.publishedVersion())) {
                    skippedCount++;
                    metricsPort.recordTaskSkipped("ALREADY_AT_TARGET_VERSION");
                    continue;
                }
                if (ref.declinedVersions().contains(event.publishedVersion())) {
                    skippedCount++;
                    metricsPort.recordTaskSkipped("PREVIOUSLY_DECLINED");
                    continue;
                }

                tenantQueue.enqueue(ref);
            }

            pageOffset += page.size();
        }

        List<CompletableFuture<EvaluationResult>> activeFutures = new CopyOnWriteArrayList<>();

        while (!tenantQueue.isEmpty() && !isShutdown.get()) {
            List<EngagementReference> batch = tenantQueue.pollFairBatch(capacityThrottler.getMaxCapacity() * 2);

            for (EngagementReference engagement : batch) {
                CompletableFuture<EvaluationResult> future = CompletableFuture.supplyAsync(
                        () -> evaluateSingleEngagementWithResilience(engagement, event),
                        workerExecutor
                );
                activeFutures.add(future);
            }

            drainCompletedFutures(activeFutures);
        }

        CompletableFuture.allOf(activeFutures.toArray(new CompletableFuture[0])).join();

        for (CompletableFuture<EvaluationResult> f : activeFutures) {
            try {
                EvaluationResult result = f.get();
                if (result.status() == TaskStatus.SUCCESS) {
                    successfulCount++;
                } else if (result.status() == TaskStatus.SKIPPED_IDEMPOTENT || result.status() == TaskStatus.SKIPPED_BRANCH_MISMATCH) {
                    skippedCount++;
                } else {
                    dlqCount++;
                }
            } catch (Exception e) {
                dlqCount++;
            }
        }

        Duration totalDuration = Duration.between(startTime, Instant.now());
        return new FanOutSummary(
                event.eventId(),
                event.productId(),
                event.publishedVersion(),
                totalDiscovered,
                successfulCount,
                skippedCount,
                dlqCount,
                totalDuration
        );
    }

    public EvaluationResult evaluateSingleEngagementWithResilience(EngagementReference engagement, TemplatePublishedEvent event) {
        String idempotencyKey = buildIdempotencyKey(engagement, event);
        metricsPort.recordTaskStarted();

        ClaimStatus claim = idempotencyStore.tryAcquire(idempotencyKey, idempotencyTtl);
        if (claim == ClaimStatus.ALREADY_COMPLETED) {
            metricsPort.recordTaskSkipped("IDEMPOTENT_ALREADY_COMPLETED");
            return EvaluationResult.skipped(engagement.engagementId(), event.publishedVersion(), TaskStatus.SKIPPED_IDEMPOTENT);
        }
        if (claim == ClaimStatus.ALREADY_IN_PROGRESS) {
            metricsPort.recordTaskSkipped("IDEMPOTENT_IN_PROGRESS");
            return EvaluationResult.skipped(engagement.engagementId(), event.publishedVersion(), TaskStatus.SKIPPED_IDEMPOTENT);
        }

        int attempts = 0;
        Instant start = Instant.now();

        try {
            while (attempts < retryPolicy.getMaxAttempts() && !isShutdown.get()) {
                attempts++;
                boolean permitAcquired = false;

                try {
                    permitAcquired = capacityThrottler.acquirePermit(permitAcquisitionTimeout);
                    if (!permitAcquired) {
                        throw new DownstreamTransientException("Timed out waiting for downstream capacity permit after " + permitAcquisitionTimeout);
                    }

                    metricsPort.recordActiveDownstreamConcurrency(capacityThrottler.getActiveCount());

                    DownstreamRehydrationClient.RehydrationResponse response =
                            downstreamClient.rehydrateAndEvaluate(engagement, event.publishedVersion());

                    idempotencyStore.markCompleted(idempotencyKey);
                    Duration executionTime = Duration.between(start, Instant.now());
                    metricsPort.recordTaskSuccess(executionTime);

                    return EvaluationResult.success(engagement.engagementId(), event.publishedVersion(), executionTime, attempts);

                } catch (DownstreamTransientException transientEx) {
                    metricsPort.recordRetryAttempt(attempts);
                    if (!retryPolicy.canRetry(attempts, transientEx)) {
                        routeToDeadLetterQueue(engagement, event, TaskStatus.TRANSIENT_FAILURE, attempts, transientEx.getMessage());
                        return EvaluationResult.failure(engagement.engagementId(), event.publishedVersion(), TaskStatus.TRANSIENT_FAILURE, attempts, transientEx.getMessage());
                    }

                    Duration backoff = retryPolicy.computeBackoff(attempts);
                    safeSleep(backoff);

                } catch (DownstreamPermanentException permanentEx) {
                    routeToDeadLetterQueue(engagement, event, TaskStatus.PERMANENT_FAILURE, attempts, permanentEx.getMessage());
                    return EvaluationResult.failure(engagement.engagementId(), event.publishedVersion(), TaskStatus.PERMANENT_FAILURE, attempts, permanentEx.getMessage());

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    routeToDeadLetterQueue(engagement, event, TaskStatus.TRANSIENT_FAILURE, attempts, "Thread interrupted during execution");
                    return EvaluationResult.failure(engagement.engagementId(), event.publishedVersion(), TaskStatus.TRANSIENT_FAILURE, attempts, "Interrupted");

                } finally {
                    if (permitAcquired) {
                        capacityThrottler.releasePermit();
                        metricsPort.recordActiveDownstreamConcurrency(capacityThrottler.getActiveCount());
                    }
                }
            }

            routeToDeadLetterQueue(engagement, event, TaskStatus.TRANSIENT_FAILURE, attempts, "Max retries exceeded");
            return EvaluationResult.failure(engagement.engagementId(), event.publishedVersion(), TaskStatus.TRANSIENT_FAILURE, attempts, "Max retries exceeded");

        } finally {
            if (!idempotencyStore.isCompleted(idempotencyKey)) {
                idempotencyStore.release(idempotencyKey);
            }
        }
    }

    private void routeToDeadLetterQueue(
            EngagementReference engagement,
            TemplatePublishedEvent event,
            TaskStatus status,
            int attempts,
            String errorMessage
    ) {
        DeadLetterQueuePort.DeadLetterEnvelope dlqEnvelope = new DeadLetterQueuePort.DeadLetterEnvelope(
                engagement.engagementId(),
                engagement.firmId(),
                engagement.productId(),
                event.publishedVersion(),
                status,
                attempts,
                errorMessage,
                Instant.now()
        );
        dlqPort.sendToDeadLetterQueue(dlqEnvelope);
        metricsPort.recordDeadLetter(status.name());
    }

    private void drainCompletedFutures(List<CompletableFuture<EvaluationResult>> futures) {
        if (futures.size() > pageSize * 2) {
            futures.removeIf(CompletableFuture::isDone);
        }
    }

    private String buildIdempotencyKey(EngagementReference ref, TemplatePublishedEvent event) {
        return ref.engagementId() + ":" + event.publishedVersion() + ":" + event.eventId();
    }

    private void safeSleep(Duration duration) {
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public DownstreamCapacityThrottler getCapacityThrottler() {
        return capacityThrottler;
    }

    @Override
    public void close() {
        isShutdown.set(true);
        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
