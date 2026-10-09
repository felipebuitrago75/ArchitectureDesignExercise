package com.auditplatform.template.fanout.model;

public enum TaskStatus {
    /**
     * Successfully evaluated downstream and notification/index updated.
     */
    SUCCESS,

    /**
     * Skipped because this version was already processed or already declined.
     */
    SKIPPED_IDEMPOTENT,

    /**
     * Skipped because engagement branch does not match published template branch.
     */
    SKIPPED_BRANCH_MISMATCH,

    /**
     * Transient error (e.g., timeout, downstream pod busy 429/503), eligible for backoff retry.
     */
    TRANSIENT_FAILURE,

    /**
     * Permanent failure (e.g., corrupted engagement schema, non-retryable 400), routed to DLQ.
     */
    PERMANENT_FAILURE
}
