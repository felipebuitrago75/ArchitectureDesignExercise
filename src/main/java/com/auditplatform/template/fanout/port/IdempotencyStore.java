package com.auditplatform.template.fanout.port;

import java.time.Duration;

/**
 * Port for distributed atomic idempotency guarantees.
 * Prevents duplicate downstream calls during at-least-once message re-deliveries.
 */
public interface IdempotencyStore {

    enum ClaimStatus {
        ACQUIRED,
        ALREADY_COMPLETED,
        ALREADY_IN_PROGRESS
    }

    /**
     * Atomically attempts to claim execution rights for a given idempotency key.
     *
     * @param idempotencyKey Deterministic key (e.g., engagementId + targetVersion + releaseId)
     * @param ttl            Time-to-live for in-progress lease (e.g. 10 minutes)
     * @return ClaimStatus indicating whether execution can proceed
     */
    ClaimStatus tryAcquire(String idempotencyKey, Duration ttl);

    /**
     * Marks the task as successfully completed permanently.
     *
     * @param idempotencyKey The idempotency key
     */
    void markCompleted(String idempotencyKey);

    /**
     * Releases an in-progress lock in case of transient failure or shutdown.
     *
     * @param idempotencyKey The idempotency key
     */
    void release(String idempotencyKey);

    /**
     * Checks if a key has already been completed.
     *
     * @param idempotencyKey The idempotency key
     * @return true if already marked completed
     */
    boolean isCompleted(String idempotencyKey);
}
