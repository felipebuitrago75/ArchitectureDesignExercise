package com.auditplatform.template.fanout;

import com.auditplatform.template.fanout.concurrency.DownstreamCapacityThrottler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamCapacityThrottlerTest {

    @Test
    @DisplayName("Should strictly enforce max concurrency limit under heavy thread contention")
    void shouldEnforceMaxCapacity() throws InterruptedException {
        int maxCapacity = 3;
        DownstreamCapacityThrottler throttler = new DownstreamCapacityThrottler(maxCapacity);
        int totalWorkers = 10;
        ExecutorService executor = Executors.newFixedThreadPool(totalWorkers);
        AtomicInteger observedMaxConcurrent = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalWorkers);

        for (int i = 0; i < totalWorkers; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean acquired = throttler.acquirePermit(Duration.ofSeconds(2));
                    if (acquired) {
                        try {
                            int current = throttler.getActiveCount();
                            observedMaxConcurrent.updateAndGet(max -> Math.max(max, current));
                            Thread.sleep(50);
                        } finally {
                            throttler.releasePermit();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(observedMaxConcurrent.get()).isLessThanOrEqualTo(maxCapacity);
        assertThat(throttler.getActiveCount()).isEqualTo(0);
        assertThat(throttler.getAvailablePermits()).isEqualTo(maxCapacity);
    }

    @Test
    @DisplayName("Should time out when permits are not released within duration")
    void shouldTimeoutWhenPermitUnavailable() throws InterruptedException {
        DownstreamCapacityThrottler throttler = new DownstreamCapacityThrottler(1);

        // Take the only permit
        assertThat(throttler.acquirePermit(Duration.ofMillis(100))).isTrue();
        assertThat(throttler.getActiveCount()).isEqualTo(1);

        // Next acquire should time out
        boolean acquiredSecond = throttler.acquirePermit(Duration.ofMillis(50));
        assertThat(acquiredSecond).isFalse();

        // Release and acquire again
        throttler.releasePermit();
        assertThat(throttler.getActiveCount()).isEqualTo(0);
        assertThat(throttler.acquirePermit(Duration.ofMillis(50))).isTrue();
        throttler.releasePermit();
    }
}
