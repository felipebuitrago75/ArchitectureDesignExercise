package com.auditplatform.template.fanout;

import com.auditplatform.template.fanout.concurrency.RetryPolicy;
import com.auditplatform.template.fanout.model.*;
import com.auditplatform.template.fanout.port.*;
import com.auditplatform.template.fanout.port.DownstreamRehydrationClient.DownstreamPermanentException;
import com.auditplatform.template.fanout.port.DownstreamRehydrationClient.DownstreamTransientException;
import com.auditplatform.template.fanout.port.IdempotencyStore.ClaimStatus;
import com.auditplatform.template.fanout.service.TemplatePublishFanOutWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TemplatePublishFanOutWorkerTest {

    private InMemoryCatalog catalog;
    private MockDownstreamClient downstreamClient;
    private InMemoryIdempotencyStore idempotencyStore;
    private InMemoryDeadLetterQueue dlq;
    private TestMetrics metrics;
    private ExecutorService workerExecutor;
    private TemplatePublishFanOutWorker worker;

    private static final String PRODUCT_ID = "audit-us-gaap";
    private static final String TEMPLATE_ID = "tmpl-100";
    private static final String TARGET_VERSION = "v2.0";
    private static final String BRANCH_ID = "US-GAAP";

    @BeforeEach
    void setUp() {
        catalog = new InMemoryCatalog();
        downstreamClient = new MockDownstreamClient();
        idempotencyStore = new InMemoryIdempotencyStore();
        dlq = new InMemoryDeadLetterQueue();
        metrics = new TestMetrics();
        workerExecutor = Executors.newFixedThreadPool(8);

        worker = new TemplatePublishFanOutWorker(
                catalog,
                downstreamClient,
                idempotencyStore,
                dlq,
                metrics,
                4,
                RetryPolicy.fastForTesting(),
                workerExecutor,
                Duration.ofSeconds(2),
                Duration.ofMinutes(5),
                50
        );
    }

    @AfterEach
    void tearDown() {
        if (worker != null) {
            worker.close();
        }
    }

    private TemplatePublishedEvent createEvent() {
        return new TemplatePublishedEvent(
                "evt-123",
                PRODUCT_ID,
                TEMPLATE_ID,
                TARGET_VERSION,
                BRANCH_ID,
                Instant.now(),
                "sha256-mock-digest"
        );
    }

    @Test
    @DisplayName("Should successfully process engagements across firms and respect downstream capacity")
    void shouldProcessEngagementsSuccessfully() {
        catalog.addEngagement(new EngagementReference("eng-1", "firm-A", PRODUCT_ID, TEMPLATE_ID, "v1.0", BRANCH_ID, Set.of()));
        catalog.addEngagement(new EngagementReference("eng-2", "firm-A", PRODUCT_ID, TEMPLATE_ID, "v1.0", BRANCH_ID, Set.of()));
        catalog.addEngagement(new EngagementReference("eng-3", "firm-B", PRODUCT_ID, TEMPLATE_ID, "v1.0", BRANCH_ID, Set.of()));
        catalog.addEngagement(new EngagementReference("eng-4", "firm-C", PRODUCT_ID, TEMPLATE_ID, "v1.0", BRANCH_ID, Set.of()));

        TemplatePublishedEvent event = createEvent();
        TemplatePublishFanOutWorker.FanOutSummary summary = worker.processTemplatePublishedEvent(event);

        assertThat(summary.totalDiscovered()).isEqualTo(4);
        assertThat(summary.successful()).isEqualTo(4);
        assertThat(summary.skipped()).isEqualTo(0);
        assertThat(summary.deadLettered()).isEqualTo(0);

        assertThat(downstreamClient.getObservedPeakConcurrency()).isLessThanOrEqualTo(4);
        assertThat(dlq.getEnvelopes()).isEmpty();
    }

    @Test
    @DisplayName("Should skip engagements already on target version, mismatched branch, or previously declined")
    void shouldFilterAndSkipIneligibleEngagements() {
        catalog.addEngagement(new EngagementReference("eng-skip-branch", "firm-A", PRODUCT_ID, TEMPLATE_ID, "v1.0", "UK-FRS102", Set.of()));
        catalog.addEngagement(new EngagementReference("eng-already-v2", "firm-A", PRODUCT_ID, TEMPLATE_ID, TARGET_VERSION, BRANCH_ID, Set.of()));
        catalog.addEngagement(new EngagementReference("eng-declined", "firm-B", PRODUCT_ID, TEMPLATE_ID, "v1.0", BRANCH_ID, Set.of(TARGET_VERSION)));
        catalog.addEngagement(new EngagementReference("eng-valid", "firm-C", PRODUCT_ID, TEMPLATE_ID, "v1.0", BRANCH_ID, Set.of()));

        TemplatePublishedEvent event = createEvent();
        TemplatePublishFanOutWorker.FanOutSummary summary = worker.processTemplatePublishedEvent(event);

        assertThat(summary.totalDiscovered()).isEqualTo(4);
        assertThat(summary.successful()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(3);
        assertThat(summary.deadLettered()).isEqualTo(0);

        assertThat(downstreamClient.getProcessedEngagementIds()).containsExactly("eng-valid");
    }

    @Test
    @DisplayName("Should enforce idempotency: duplicate event redelivery skips already completed tasks")
    void shouldEnforceIdempotencyOnRedelivery() {
        catalog.addEngagement(new EngagementReference("eng-idem-1", "firm-A", PRODUCT_ID, TEMPLATE_ID, "v1.0", BRANCH_ID, Set.of()));

        TemplatePublishedEvent event = createEvent();

        TemplatePublishFanOutWorker.FanOutSummary summary1 = worker.processTemplatePublishedEvent(event);
        assertThat(summary1.successful()).isEqualTo(1);
        assertThat(downstreamClient.getCallCount("eng-idem-1")).isEqualTo(1);

        TemplatePublishFanOutWorker.FanOutSummary summary2 = worker.processTemplatePublishedEvent(event);
        assertThat(summary2.successful()).isEqualTo(0);
        assertThat(summary2.skipped()).isEqualTo(1);

        assertThat(downstreamClient.getCallCount("eng-idem-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("Should recover from transient downstream errors using exponential backoff retry")
    void shouldRetryOnTransientDownstreamErrorAndSucceed() {
        catalog.addEngagement(new EngagementReference("eng-transient-1", "firm-A", PRODUCT_ID, TEMPLATE_ID, "v1.0", BRANCH_ID, Set.of()));

        downstreamClient.setFailuresBeforeSuccess("eng-transient-1", 2, true);

        TemplatePublishedEvent event = createEvent();
        TemplatePublishFanOutWorker.FanOutSummary summary = worker.processTemplatePublishedEvent(event);

        assertThat(summary.successful()).isEqualTo(1);
        assertThat(summary.deadLettered()).isEqualTo(0);
        assertThat(downstreamClient.getCallCount("eng-transient-1")).isEqualTo(3);
        assertThat(dlq.getEnvelopes()).isEmpty();
    }

    @Test
    @DisplayName("Should route to Dead Letter Queue when transient retries are exhausted")
    void shouldRouteToDLQWhenRetriesExhausted() {
        catalog.addEngagement(new EngagementReference("eng-exhaust-1", "firm-A", PRODUCT_ID, TEMPLATE_ID, "v1.0", BRANCH_ID, Set.of()));

        downstreamClient.setFailuresBeforeSuccess("eng-exhaust-1", 10, true);

        TemplatePublishedEvent event = createEvent();
        TemplatePublishFanOutWorker.FanOutSummary summary = worker.processTemplatePublishedEvent(event);

        assertThat(summary.successful()).isEqualTo(0);
        assertThat(summary.deadLettered()).isEqualTo(1);
        assertThat(downstreamClient.getCallCount("eng-exhaust-1")).isEqualTo(3);

        assertThat(dlq.getEnvelopes()).hasSize(1);
        DeadLetterQueuePort.DeadLetterEnvelope envelope = dlq.getEnvelopes().get(0);
        assertThat(envelope.engagementId()).isEqualTo("eng-exhaust-1");
        assertThat(envelope.failureReason()).isEqualTo(TaskStatus.TRANSIENT_FAILURE);
        assertThat(envelope.totalAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should route immediately to Dead Letter Queue on permanent downstream exception without retries")
    void shouldRouteToDLQImmediatelyOnPermanentError() {
        catalog.addEngagement(new EngagementReference("eng-permanent-1", "firm-A", PRODUCT_ID, TEMPLATE_ID, "v1.0", BRANCH_ID, Set.of()));

        downstreamClient.setFailuresBeforeSuccess("eng-permanent-1", 1, false);

        TemplatePublishedEvent event = createEvent();
        TemplatePublishFanOutWorker.FanOutSummary summary = worker.processTemplatePublishedEvent(event);

        assertThat(summary.successful()).isEqualTo(0);
        assertThat(summary.deadLettered()).isEqualTo(1);
        assertThat(downstreamClient.getCallCount("eng-permanent-1")).isEqualTo(1);

        assertThat(dlq.getEnvelopes()).hasSize(1);
        DeadLetterQueuePort.DeadLetterEnvelope envelope = dlq.getEnvelopes().get(0);
        assertThat(envelope.engagementId()).isEqualTo("eng-permanent-1");
        assertThat(envelope.failureReason()).isEqualTo(TaskStatus.PERMANENT_FAILURE);
        assertThat(envelope.totalAttempts()).isEqualTo(1);
    }

    private static class InMemoryCatalog implements EngagementCatalogPort {
        private final List<EngagementReference> records = new ArrayList<>();

        public void addEngagement(EngagementReference ref) {
            records.add(ref);
        }

        @Override
        public List<EngagementReference> findActiveEngagementsByProduct(String productId, int pageOffset, int pageSize) {
            if (pageOffset >= records.size()) {
                return Collections.emptyList();
            }
            int toIndex = Math.min(pageOffset + pageSize, records.size());
            return new ArrayList<>(records.subList(pageOffset, toIndex));
        }

        @Override
        public long countActiveEngagementsByProduct(String productId) {
            return records.size();
        }
    }

    private static class MockDownstreamClient implements DownstreamRehydrationClient {
        private final Map<String, Integer> callCounts = new ConcurrentHashMap<>();
        private final Map<String, Integer> remainingFailures = new ConcurrentHashMap<>();
        private final Map<String, Boolean> isTransientFailure = new ConcurrentHashMap<>();
        private final AtomicInteger activeConcurrency = new AtomicInteger(0);
        private final AtomicInteger peakConcurrency = new AtomicInteger(0);
        private final List<String> processedEngagements = new CopyOnWriteArrayList<>();

        public void setFailuresBeforeSuccess(String engagementId, int failures, boolean transientError) {
            remainingFailures.put(engagementId, failures);
            isTransientFailure.put(engagementId, transientError);
        }

        @Override
        public RehydrationResponse rehydrateAndEvaluate(EngagementReference engagement, String targetVersion)
                throws DownstreamTransientException, DownstreamPermanentException {
            int current = activeConcurrency.incrementAndGet();
            peakConcurrency.updateAndGet(max -> Math.max(max, current));
            callCounts.merge(engagement.engagementId(), 1, Integer::sum);

            try {
                Integer failures = remainingFailures.get(engagement.engagementId());
                if (failures != null && failures > 0) {
                    remainingFailures.put(engagement.engagementId(), failures - 1);
                    boolean isTransient = isTransientFailure.getOrDefault(engagement.engagementId(), true);
                    if (isTransient) {
                        throw new DownstreamTransientException("Pod busy / timeout 503");
                    } else {
                        throw new DownstreamPermanentException("Unrecoverable corrupted workpaper payload 400");
                    }
                }

                Thread.sleep(15);
                processedEngagements.add(engagement.engagementId());
                return new RehydrationResponse(engagement.engagementId(), targetVersion, true, Duration.ofMillis(15));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DownstreamTransientException("Interrupted during call");
            } finally {
                activeConcurrency.decrementAndGet();
            }
        }

        public int getObservedPeakConcurrency() {
            return peakConcurrency.get();
        }

        public int getCallCount(String engagementId) {
            return callCounts.getOrDefault(engagementId, 0);
        }

        public List<String> getProcessedEngagementIds() {
            return processedEngagements;
        }
    }

    private static class InMemoryIdempotencyStore implements IdempotencyStore {
        private final Set<String> completedKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final Set<String> inProgressKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());

        @Override
        public synchronized ClaimStatus tryAcquire(String idempotencyKey, Duration ttl) {
            if (completedKeys.contains(idempotencyKey)) {
                return ClaimStatus.ALREADY_COMPLETED;
            }
            if (inProgressKeys.contains(idempotencyKey)) {
                return ClaimStatus.ALREADY_IN_PROGRESS;
            }
            inProgressKeys.add(idempotencyKey);
            return ClaimStatus.ACQUIRED;
        }

        @Override
        public synchronized void markCompleted(String idempotencyKey) {
            inProgressKeys.remove(idempotencyKey);
            completedKeys.add(idempotencyKey);
        }

        @Override
        public synchronized void release(String idempotencyKey) {
            inProgressKeys.remove(idempotencyKey);
        }

        @Override
        public synchronized boolean isCompleted(String idempotencyKey) {
            return completedKeys.contains(idempotencyKey);
        }
    }

    private static class InMemoryDeadLetterQueue implements DeadLetterQueuePort {
        private final List<DeadLetterEnvelope> envelopes = new CopyOnWriteArrayList<>();

        @Override
        public void sendToDeadLetterQueue(DeadLetterEnvelope envelope) {
            envelopes.add(envelope);
        }

        public List<DeadLetterEnvelope> getEnvelopes() {
            return envelopes;
        }
    }

    private static class TestMetrics implements MetricsPort {
        @Override public void recordTaskStarted() {}
        @Override public void recordTaskSuccess(Duration executionTime) {}
        @Override public void recordTaskSkipped(String reason) {}
        @Override public void recordRetryAttempt(int attemptNumber) {}
        @Override public void recordDeadLetter(String failureReason) {}
        @Override public void recordActiveDownstreamConcurrency(int activeCount) {}
    }
}
