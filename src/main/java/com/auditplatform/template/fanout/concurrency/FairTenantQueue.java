package com.auditplatform.template.fanout.concurrency;

import com.auditplatform.template.fanout.model.EngagementReference;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tenant-fair queue preventing large enterprise firms (~40,000 engagements) from
 * starving median-sized firms (~40 engagements).
 *
 * Implements Deficit Round-Robin / Interleaved Tenant Scheduling.
 */
public class FairTenantQueue {

    private final int quantumPerTurn;
    private final Map<String, Queue<EngagementReference>> tenantQueues = new ConcurrentHashMap<>();
    private final List<String> activeTenantOrder = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger totalSize = new AtomicInteger(0);
    private int currentTenantIndex = 0;

    public FairTenantQueue(int quantumPerTurn) {
        if (quantumPerTurn <= 0) {
            throw new IllegalArgumentException("quantumPerTurn must be > 0");
        }
        this.quantumPerTurn = quantumPerTurn;
    }

    public synchronized void enqueueAll(List<EngagementReference> engagements) {
        for (EngagementReference ref : engagements) {
            enqueue(ref);
        }
    }

    public synchronized void enqueue(EngagementReference ref) {
        String firmId = ref.firmId();
        tenantQueues.computeIfAbsent(firmId, k -> {
            activeTenantOrder.add(k);
            return new ConcurrentLinkedQueue<>();
        }).offer(ref);
        totalSize.incrementAndGet();
    }

    /**
     * Polls the next fair batch of tasks across tenants.
     * Fixed: Prevents IndexOutOfBoundsException when a tenant queue is drained and removed mid-batch.
     */
    public synchronized List<EngagementReference> pollFairBatch(int maxBatchSize) {
        List<EngagementReference> batch = new ArrayList<>();
        if (activeTenantOrder.isEmpty() || maxBatchSize <= 0) {
            return batch;
        }

        int iterations = 0;
        int tenantsCount = activeTenantOrder.size();

        while (batch.size() < maxBatchSize && !activeTenantOrder.isEmpty() && iterations < tenantsCount * 2) {
            if (currentTenantIndex >= activeTenantOrder.size()) {
                currentTenantIndex = 0;
            }

            String currentFirmId = activeTenantOrder.get(currentTenantIndex);
            Queue<EngagementReference> firmQueue = tenantQueues.get(currentFirmId);

            int polledForThisTenant = 0;
            while (polledForThisTenant < quantumPerTurn && batch.size() < maxBatchSize && firmQueue != null && !firmQueue.isEmpty()) {
                EngagementReference item = firmQueue.poll();
                if (item != null) {
                    batch.add(item);
                    totalSize.decrementAndGet();
                    polledForThisTenant++;
                }
            }

            if (firmQueue == null || firmQueue.isEmpty()) {
                tenantQueues.remove(currentFirmId);
                activeTenantOrder.remove(currentTenantIndex);
                // Do not increment currentTenantIndex because removal shifts subsequent elements left
            } else {
                currentTenantIndex++;
            }
            iterations++;
        }

        return batch;
    }

    public synchronized boolean isEmpty() {
        return totalSize.get() == 0;
    }

    public int size() {
        return totalSize.get();
    }

    public int getActiveTenantCount() {
        return activeTenantOrder.size();
    }
}
