package com.auditplatform.template.fanout.model;

import java.time.Duration;

/**
 * Result returned after evaluating an individual engagement against the new template version.
 *
 * @param engagementId   Engagement identifier
 * @param targetVersion  Template version evaluated
 * @param status         Final status
 * @param executionTime  Duration spent on this task
 * @param retryAttempts  Number of retry attempts made
 * @param errorMessage   Error message if failed, or null if successful
 */
public record EvaluationResult(
        String engagementId,
        String targetVersion,
        TaskStatus status,
        Duration executionTime,
        int retryAttempts,
        String errorMessage
) {
    public static EvaluationResult success(String engagementId, String targetVersion, Duration executionTime, int attempts) {
        return new EvaluationResult(engagementId, targetVersion, TaskStatus.SUCCESS, executionTime, attempts, null);
    }

    public static EvaluationResult skipped(String engagementId, String targetVersion, TaskStatus skipReason) {
        return new EvaluationResult(engagementId, targetVersion, skipReason, Duration.ZERO, 0, null);
    }

    public static EvaluationResult failure(String engagementId, String targetVersion, TaskStatus status, int attempts, String error) {
        return new EvaluationResult(engagementId, targetVersion, status, Duration.ZERO, attempts, error);
    }

    public boolean isSuccessfulOrSkipped() {
        return status == TaskStatus.SUCCESS || status == TaskStatus.SKIPPED_IDEMPOTENT || status == TaskStatus.SKIPPED_BRANCH_MISMATCH;
    }
}
