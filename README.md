# Template-Publish Fan-Out Worker (Part 2)

**Author:** Andrés Felipe Buitrago Feria · Java 17, JUnit 5, AssertJ, Maven

Design document: [`design/ARCHITECTURE_DESIGN_DOCUMENT.md`](design/ARCHITECTURE_DESIGN_DOCUMENT.md) · Diagrams: [`design/DIAGRAMS.md`](design/DIAGRAMS.md)

## What it does

When a template version is published, the worker:

1. Reads the product's engagements page by page.
2. Skips the ones that can't be affected: different market branch, already on that version, or version previously declined.
3. Puts the rest in one queue per firm and takes a few from each firm in turn.
4. Calls the downstream service (about 1 minute per engagement), never more than N calls at a time.
5. Retries temporary errors with backoff and sends permanent errors to a dead-letter queue.
6. Returns a summary: discovered, successful, skipped, dead-lettered.

## Key tradeoffs

**Hard limit on downstream calls.** A semaphore caps concurrent calls to the downstream service, whatever the thread pool size. The downstream is slow and owned by another team, so this cap is what protects it. If no permit frees up within a timeout, the attempt counts as a temporary error.

**Fair queue per firm instead of FIFO.** One firm has about 40,000 engagements; the median has about 40. With a single FIFO queue, small firms would wait behind the large one for days. The queue takes up to 3 engagements from each firm per turn.

**Filter before calling downstream.** Branch, version and declined checks happen in memory first, so no slow call is spent on an engagement that can't be affected.

**Retry only what can succeed.** Temporary errors (429, 503, timeouts) are retried up to 3 times with exponential backoff and random jitter. Permanent errors (bad data, 400) go straight to the dead-letter queue, since retrying them wastes slow downstream capacity.

**Idempotency.** Before calling downstream, the worker claims a key (engagement + version + event) with a time limit. If the key is already done or in progress, it skips the engagement. This makes repeated deliveries of the same event safe.

## Project structure

```
src/main/java/com/auditplatform/template/fanout/
├── model/        Event, engagement reference, result, status
├── port/         Interfaces: catalog, downstream client, idempotency store, DLQ, metrics
├── concurrency/  DownstreamCapacityThrottler, FairTenantQueue, RetryPolicy
└── service/      TemplatePublishFanOutWorker
src/test/java/com/auditplatform/template/fanout/
└── Unit tests with in-memory fakes (12 tests)
```

## Tests

| Test class | What it checks |
| :--- | :--- |
| `DownstreamCapacityThrottlerTest` | Concurrent calls never exceed the limit; the timeout works |
| `FairTenantQueueTest` | Small firms aren't blocked by a large one; edge cases when a firm's queue empties |
| `RetryPolicyTest` | Backoff stays within limits; temporary vs permanent errors |
| `TemplatePublishFanOutWorkerTest` | End-to-end processing, filtering, idempotency, retries, dead-letter routing |

## Build and run

Requires JDK 17+ and Maven 3.8+.

```bash
mvn clean test
```
