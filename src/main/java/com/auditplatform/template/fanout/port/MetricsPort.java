package com.auditplatform.template.fanout.port;

import java.time.Duration;

/**
 * Port for observability, metrics collection, and alerting.
 */
public interface MetricsPort {

    void recordTaskStarted();

    void recordTaskSuccess(Duration executionTime);

    void recordTaskSkipped(String reason);

    void recordRetryAttempt(int attemptNumber);

    void recordDeadLetter(String failureReason);

    void recordActiveDownstreamConcurrency(int activeCount);
}
