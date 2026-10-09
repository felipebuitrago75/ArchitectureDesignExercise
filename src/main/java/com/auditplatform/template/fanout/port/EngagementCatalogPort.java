package com.auditplatform.template.fanout.port;

import com.auditplatform.template.fanout.model.EngagementReference;

import java.util.List;

/**
 * Port to query candidate engagements for a given product/template.
 * Decoupled from the storage mechanism (PostgreSQL, DynamoDB, or Regional EMI).
 */
public interface EngagementCatalogPort {

    /**
     * Retrieves a page of active engagements for the specified product.
     *
     * @param productId The product identifier
     * @param pageOffset The zero-indexed page offset
     * @param pageSize   Number of records per page
     * @return List of active engagement references
     */
    List<EngagementReference> findActiveEngagementsByProduct(String productId, int pageOffset, int pageSize);

    /**
     * Total count of active engagements under the specified product.
     *
     * @param productId The product identifier
     * @return count of active engagements
     */
    long countActiveEngagementsByProduct(String productId);
}
