package com.auditplatform.template.fanout.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain event emitted when a new Product Template version is published by platform content teams.
 *
 * @param eventId          Unique event identifier (UUID)
 * @param productId        The product family identifier (e.g., "audit-financial-us")
 * @param templateId       The specific template identifier
 * @param publishedVersion The newly published version string (e.g., "v2.4.0")
 * @param branchId         The market branch (e.g., "US-GAAP", "CA-ASPE", "UK-FRS102")
 * @param publishedAt      Timestamp of publication
 * @param summaryDigestSha SHA-256 hash of the precomputed audit summary
 */
public record TemplatePublishedEvent(
        String eventId,
        String productId,
        String templateId,
        String publishedVersion,
        String branchId,
        Instant publishedAt,
        String summaryDigestSha
) {
    public TemplatePublishedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(templateId, "templateId must not be null");
        Objects.requireNonNull(publishedVersion, "publishedVersion must not be null");
        Objects.requireNonNull(branchId, "branchId must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        Objects.requireNonNull(summaryDigestSha, "summaryDigestSha must not be null");
    }
}
