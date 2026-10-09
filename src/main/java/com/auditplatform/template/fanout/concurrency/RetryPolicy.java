package com.auditplatform.template.fanout.concurrency;

import com.auditplatform.template.fanout.port.DownstreamRehydrationClient.DownstreamTransientException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff retry policy with full jitter to avoid downstream thundering herds.
 */
public class RetryPolicy {

    private final int maxAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double backoffMultiplier;

    public RetryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay, double backoffMultiplier) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
        }
        this.maxAttempts = maxAttempts;
        this.initialDelay = Objects.requireNonNull(initialDelay, "initialDelay must not be null");
        this.maxDelay = Objects.requireNonNull(maxDelay, "maxDelay must not be null");
        this.backoffMultiplier = backoffMultiplier;
    }

    public static RetryPolicy standard() {
        return new RetryPolicy(3, Duration.ofMillis(200), Duration.ofSeconds(5), 2.0);
    }

    public static RetryPolicy fastForTesting() {
        return new RetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(50), 2.0);
    }

    public boolean canRetry(int currentAttempt, Throwable error) {
        if (currentAttempt >= maxAttempts) {
            return false;
        }
        return isTransient(error);
    }

    public boolean isTransient(Throwable error) {
        return error instanceof DownstreamTransientException;
    }

    public Duration computeBackoff(int attempt) {
        long baseMillis = initialDelay.toMillis();
        double factor = Math.pow(backoffMultiplier, Math.max(0, attempt - 1));
        long calculated = (long) (baseMillis * factor);
        long ceiling = Math.min(maxDelay.toMillis(), calculated);
        if (ceiling <= 0) {
            return Duration.ZERO;
        }
        long jittered = ThreadLocalRandom.current().nextLong(ceiling + 1);
        return Duration.ofMillis(jittered);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }
}
