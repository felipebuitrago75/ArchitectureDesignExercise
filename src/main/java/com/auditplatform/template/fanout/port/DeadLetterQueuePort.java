package com.auditplatform.template.fanout.port;

import com.auditplatform.template.fanout.model.TaskStatus;

import java.time.Instant;

/**
 * Port to route unrecoverable poison pills or exhausted retry tasks to an auditable Dead-Letter Queue (DLQ).
 */
public interface DeadLetterQueuePort {

    record DeadLetterEnvelope(
            String engagementId,
            String firmId,
            String productId,
            String targetVersion,
            TaskStatus failureReason,
            int totalAttempts,
            String errorMessage,
            Instant failedAt
    ) {}

    /**
     * Publishes an unrecoverable failure envelope to the DLQ.
     *
     * @param envelope Detailed failure context
     */
    void sendToDeadLetterQueue(DeadLetterEnvelope envelope);
}
