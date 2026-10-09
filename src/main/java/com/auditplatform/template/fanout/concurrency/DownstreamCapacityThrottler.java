package com.auditplatform.template.fanout.concurrency;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Strict concurrency limiter protecting the downstream Engagement Management System (EMS).
 *
 * The downstream service rehydrates memory-heavy pods (~1 min per engagement) and is owned
 * by another team with strictly limited capacity. This throttler acts as a hard boundary.
 */
public class DownstreamCapacityThrottler {

    private final int maxPermits;
    private final Semaphore semaphore;
    private final AtomicInteger activePermits = new AtomicInteger(0);

    public DownstreamCapacityThrottler(int maxPermits) {
        if (maxPermits <= 0) {
            throw new IllegalArgumentException("maxPermits must be strictly positive");
        }
        this.maxPermits = maxPermits;
        this.semaphore = new Semaphore(maxPermits, true); // Fair semaphore
    }

    /**
     * Acquires an execution permit, blocking up to the specified timeout.
     *
     * @param timeout Maximum duration to wait for a downstream permit
     * @return true if permit was acquired, false if timed out
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean acquirePermit(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        boolean acquired = semaphore.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (acquired) {
            activePermits.incrementAndGet();
        }
        return acquired;
    }

    /**
     * Releases an acquired permit back to the pool.
     */
    public void releasePermit() {
        activePermits.decrementAndGet();
        semaphore.release();
    }

    public int getActiveCount() {
        return activePermits.get();
    }

    public int getMaxCapacity() {
        return maxPermits;
    }

    public int getAvailablePermits() {
        return semaphore.availablePermits();
    }
}
