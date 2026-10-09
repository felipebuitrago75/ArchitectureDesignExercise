# Template-Publish Fan-Out Worker (Part 2 Implementation)

**Author:** Andrés Felipe Buitrago Feria  
**Component:** `TemplatePublishFanOutWorker`  
**Stack:** Java 17+, Concurrency Utilities (`java.util.concurrent`), JUnit 5, AssertJ, Maven  

---

## 1. Overview & Problem Context

In our audit and assurance SaaS platform, publishing a product template update may affect up to **20,000 to 40,000 active customer engagements**. The downstream Engagement Management System (EMS) rehydrates engagement files into pod memory (a slow operation taking **~1 minute per file**) and is operated by an external team with **strictly limited capacity**.

This component (`TemplatePublishFanOutWorker`) implements a production-grade, highly resilient worker designed to handle this fan-out without crashing downstream pods, starving smaller firms, or creating duplicate evaluations under at-least-once message delivery.

```
TemplatePublishedEvent
        │
        ▼
┌─────────────────────────┐
│ EngagementCatalogPort   │ ── Paged Discovery (PageSize = 50-500)
└─────────────────────────┘
        │
        ▼
┌─────────────────────────┐
│ FairTenantQueue         │ ── Deficit Round-Robin (DRR) Partitioned by firmId
└─────────────────────────┘    (Prevents 40k-file Enterprise Firm from blocking 40-file Median Firms)
        │
        ▼
┌─────────────────────────┐
│ IdempotencyStore        │ ── Distributed Lease & Completion Check (SHA-256 Key)
└─────────────────────────┘
        │
        ▼
┌─────────────────────────┐
│ CapacityThrottler       │ ── Semaphore-guarded Quota (Strictly caps downstream concurrency)
└─────────────────────────┘
        │
        ▼
┌─────────────────────────┐
│ Downstream Rehydration  │ ── ~1 min pod rehydration (Owned by external team)
└─────────────────────────┘
        │
   ┌────┴───────────────────────────┐
   ▼                                ▼
[Success]               [Transient / Permanent Failure]
   │                                │
Mark Idempotency        Exponential Backoff + Jitter  OR  Dead-Letter Queue (DLQ)
```

---

## 2. Key Implementation Tradeoffs & Architectural Decisions

### Tradeoff 1: Adaptive Semaphore vs. Unbounded CompletableFuture Pools
- **Decision:** Implemented `DownstreamCapacityThrottler` using a fair `Semaphore` with acquisition timeouts (`Duration permitAcquisitionTimeout`), rather than relying solely on thread pool sizing.
- **Tradeoff Rationale:** Thread pool sizes do not protect against downstream backpressure if multiple worker instances run concurrently in Kubernetes. The throttler provides a hard boundary that mirrors the downstream team's SLA quota. If downstream response times degrade, permits are held longer, automatically slowing down worker ingestion without dropping tasks.

### Tradeoff 2: Fair Multi-Tenant Scheduling vs. Naive FIFO Queuing
- **Decision:** Built `FairTenantQueue` using round-robin interleaved batching across `firmId`s.
- **Tradeoff Rationale:** Engagement distribution is heavily skewed (median firm: ~40 files; largest firm: ~40,000 files). A standard FIFO queue would cause the largest firm's product update to occupy all worker permits for days ($40,000 \times 1\text{ min} / 15 \text{ pods} \approx 44.4\text{ hours}$), starving hundreds of smaller firms. With fair scheduling, median firms complete in their very first quantum rounds (< 15 minutes).

### Tradeoff 3: Eager Pre-Filtering vs. Downstream Delegation
- **Decision:** The worker inspects `activeBranch`, `currentVersion`, and `declinedVersions` *before* acquiring permits or invoking the downstream service.
- **Tradeoff Rationale:** Branch mismatches (e.g., US vs UK) and previously declined versions are filtered out in nanoseconds in-memory. This saves thousands of unnecessary 1-minute downstream rehydration calls.

### Tradeoff 4: Retry with Full Jitter vs. Immediate DLQ Routing
- **Decision:** Differentiates between `DownstreamTransientException` (HTTP 429/503, timeouts) and `DownstreamPermanentException` (schema corruption, 400).
- **Tradeoff Rationale:** Retrying permanent errors wastes scarce 1-minute rehydration slots. Permanent errors are routed to the DLQ immediately on attempt 1. Transient errors use exponential backoff with full jitter to avoid downstream thundering herds.

### Tradeoff 5: Atomic Lease-Based Idempotency
- **Decision:** `IdempotencyStore.tryAcquire(...)` with lease TTL and state tracking (`ACQUIRED`, `ALREADY_COMPLETED`, `ALREADY_IN_PROGRESS`).
- **Tradeoff Rationale:** Distributed event brokers (SQS, EventBridge) guarantee at-least-once delivery. Redelivered messages check the idempotency store and skip execution immediately without invoking downstream pods.

---

## 3. Project Structure

```
├── pom.xml                                   # Maven build file (Java 17+, JUnit 5, AssertJ)
├── README.md                                 # This document
├── design/
│   ├── ARCHITECTURE_DESIGN_DOCUMENT.md       # Primary 2-4 page Staff Design Document
│   ├── AI_USAGE_REFLECTIVE_STATEMENT.md      # AI usage reflection and engineering judgment
│   ├── AI_SESSION_HISTORY.md                 # Interactive prompt history and collaboration log
│   └── DIAGRAMS.md                           # Architectural diagrams and workflows
└── src/
    ├── main/java/com/auditplatform/template/fanout/
    │   ├── model/
    │   │   ├── TemplatePublishedEvent.java   # Domain event record
    │   │   ├── EngagementReference.java      # Engagement pointer record
    │   │   ├── EvaluationResult.java         # Task result record
    │   │   └── TaskStatus.java               # Outcome status enum
    │   ├── port/
    │   │   ├── EngagementCatalogPort.java    # Paged catalog access contract
    │   │   ├── DownstreamRehydrationClient.java # Legacy slow (~1 min) downstream client
    │   │   ├── IdempotencyStore.java         # Atomic deduplication contract
    │   │   ├── DeadLetterQueuePort.java      # Poison pill DLQ contract
    │   │   └── MetricsPort.java              # Observability contract
    │   ├── concurrency/
    │   │   ├── DownstreamCapacityThrottler.java # Semaphore-based capacity protector
    │   │   ├── FairTenantQueue.java          # Anti-starvation round-robin scheduler
    │   │   └── RetryPolicy.java              # Exponential backoff with full jitter
    │   └── service/
    │       └── TemplatePublishFanOutWorker.java # Central fan-out orchestrator
    └── test/java/com/auditplatform/template/fanout/
        ├── DownstreamCapacityThrottlerTest.java # Concurrency bounds tests
        ├── FairTenantQueueTest.java          # Anti-starvation tenant tests
        ├── RetryPolicyTest.java              # Backoff & jitter tests
        └── TemplatePublishFanOutWorkerTest.java # Full integration & resilience tests
```

---

## 4. How to Build & Run Tests

### Prerequisites
- JDK 17 or higher
- Apache Maven 3.8+

### Execution Commands

```bash
# Compile and run all unit and integration tests
mvn clean test

# Package jar
mvn package -DskipTests
```

### Test Suite Coverage Summary
- `DownstreamCapacityThrottlerTest`: Validates strict permit bounding under heavy thread contention and timeout semantics.
- `FairTenantQueueTest`: Verifies that a 20-engagement enterprise firm does not block small 2-engagement firms from finishing in the first batch, and validates drain edge-cases.
- `RetryPolicyTest`: Confirms exponential backoff bounds and transient vs permanent error classification.
- `TemplatePublishFanOutWorkerTest`:
  - `shouldProcessEngagementsSuccessfully`: Verifies multi-firm end-to-end processing with downstream concurrency capping.
  - `shouldFilterAndSkipIneligibleEngagements`: Validates branch and version filtering with zero downstream overhead.
  - `shouldEnforceIdempotencyOnRedelivery`: Proves duplicate deliveries never call downstream a second time.
  - `shouldRetryOnTransientDownstreamErrorAndSucceed`: Confirms self-healing transient retry loop.
  - `shouldRouteToDLQWhenRetriesExhausted`: Validates dead-letter routing after 3 failed attempts.
  - `shouldRouteToDLQImmediatelyOnPermanentError`: Verifies single-attempt routing for corrupted payloads without wasteful retries.
