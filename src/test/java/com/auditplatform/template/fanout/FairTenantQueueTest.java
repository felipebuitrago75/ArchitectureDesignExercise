package com.auditplatform.template.fanout;

import com.auditplatform.template.fanout.concurrency.FairTenantQueue;
import com.auditplatform.template.fanout.model.EngagementReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FairTenantQueueTest {

    @Test
    @DisplayName("Should prevent large firm from starving small firms via round-robin scheduling")
    void shouldInterleaveTenantsFairly() {
        FairTenantQueue queue = new FairTenantQueue(2); // Quantum = 2

        // Firm A (Enterprise) has 20 engagements
        for (int i = 1; i <= 20; i++) {
            queue.enqueue(new EngagementReference("eng-A-" + i, "firm-A", "prod-1", "temp-1", "v1.0", "US", Set.of()));
        }

        // Firm B (Median) has 2 engagements
        for (int i = 1; i <= 2; i++) {
            queue.enqueue(new EngagementReference("eng-B-" + i, "firm-B", "prod-1", "temp-1", "v1.0", "US", Set.of()));
        }

        // Firm C (Median) has 2 engagements
        for (int i = 1; i <= 2; i++) {
            queue.enqueue(new EngagementReference("eng-C-" + i, "firm-C", "prod-1", "temp-1", "v1.0", "US", Set.of()));
        }

        assertThat(queue.size()).isEqualTo(24);
        assertThat(queue.getActiveTenantCount()).isEqualTo(3);

        List<EngagementReference> batch1 = queue.pollFairBatch(6);
        assertThat(batch1).hasSize(6);

        long fromA = batch1.stream().filter(e -> e.firmId().equals("firm-A")).count();
        long fromB = batch1.stream().filter(e -> e.firmId().equals("firm-B")).count();
        long fromC = batch1.stream().filter(e -> e.firmId().equals("firm-C")).count();

        assertThat(fromA).isEqualTo(2);
        assertThat(fromB).isEqualTo(2);
        assertThat(fromC).isEqualTo(2);
    }
}
