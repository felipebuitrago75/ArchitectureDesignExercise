package com.auditplatform.template.fanout;

import com.auditplatform.template.fanout.concurrency.RetryPolicy;
import com.auditplatform.template.fanout.port.DownstreamRehydrationClient.DownstreamPermanentException;
import com.auditplatform.template.fanout.port.DownstreamRehydrationClient.DownstreamTransientException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    @Test
    @DisplayName("Should allow retries for transient exceptions up to max attempts")
    void shouldAllowTransientRetries() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(1), 2.0);

        DownstreamTransientException transientEx = new DownstreamTransientException("Pod 503");
        DownstreamPermanentException permanentEx = new DownstreamPermanentException("Schema 400");

        assertThat(policy.canRetry(1, transientEx)).isTrue();
        assertThat(policy.canRetry(2, transientEx)).isTrue();
        assertThat(policy.canRetry(3, transientEx)).isFalse();

        assertThat(policy.canRetry(1, permanentEx)).isFalse();
    }

    @Test
    @DisplayName("Should calculate exponential backoff within configured ceiling")
    void shouldCalculateBackoffWithinCeiling() {
        Duration initial = Duration.ofMillis(100);
        Duration max = Duration.ofMillis(500);
        RetryPolicy policy = new RetryPolicy(5, initial, max, 2.0);

        for (int attempt = 1; attempt <= 5; attempt++) {
            Duration backoff = policy.computeBackoff(attempt);
            assertThat(backoff.toMillis()).isGreaterThanOrEqualTo(0);
            assertThat(backoff.toMillis()).isLessThanOrEqualTo(max.toMillis());
        }
    }
}
