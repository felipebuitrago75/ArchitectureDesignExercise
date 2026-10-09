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

        for (int i = 1; i <= 20; i++) {
            queue.enqueue(new EngagementReference("eng-A-" + i, "firm-A", "prod-1", "temp-1", "v1.0", "US", Set.of()));
        }

        for (int i = 1; i <= 2; i++) {
            queue.enqueue(new EngagementReference("eng-B-" + i, "firm-B", "prod-1", "temp-1", "v1.0", "US", Set.of()));
        }

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

        assertThat(queue.getActiveTenantCount()).isEqualTo(1);
        assertThat(queue.size()).isEqualTo(18);

        List<EngagementReference> remaining = new ArrayList<>();
        while (!queue.isEmpty()) {
            remaining.addAll(queue.pollFairBatch(10));
        }

        assertThat(remaining).hasSize(18);
        assertThat(remaining).allMatch(e -> e.firmId().equals("firm-A"));
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Should handle empty and edge-case single item tenant rotations without IndexOutOfBoundsException")
    void shouldHandleTenantDrainEdgeCases() {
        FairTenantQueue queue = new FairTenantQueue(1);

        queue.enqueue(new EngagementReference("eng-1", "firm-X", "prod-1", "temp-1", "v1.0", "US", Set.of()));
        queue.enqueue(new EngagementReference("eng-2", "firm-Y", "prod-1", "temp-1", "v1.0", "US", Set.of()));

        // Poll one item at a time
        List<EngagementReference> first = queue.pollFairBatch(1);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).firmId()).isEqualTo("firm-X");

        List<EngagementReference> second = queue.pollFairBatch(1);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).firmId()).isEqualTo("firm-Y");

        assertThat(queue.isEmpty()).isTrue();
        assertThat(queue.pollFairBatch(1)).isEmpty();
    }
}
