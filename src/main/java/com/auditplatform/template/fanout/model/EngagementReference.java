package com.auditplatform.template.fanout.model;

import java.util.Objects;
import java.util.Set;

/**
 * Lightweight pointer to an active customer engagement file.
 *
 * @param engagementId      Unique engagement file UUID
 * @param firmId            Tenant accounting firm identifier
 * @param productId         Product identifier
 * @param templateId        Template identifier currently linked
 * @param currentVersion    The current version pinned to this engagement
 * @param activeBranch      The market branch the engagement follows
 * @param declinedVersions  Set of previously declined template versions
 */
public record EngagementReference(
        String engagementId,
        String firmId,
        String productId,
        String templateId,
        String currentVersion,
        String activeBranch,
        Set<String> declinedVersions
) {
    public EngagementReference {
        Objects.requireNonNull(engagementId, "engagementId must not be null");
        Objects.requireNonNull(firmId, "firmId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(templateId, "templateId must not be null");
        Objects.requireNonNull(currentVersion, "currentVersion must not be null");
        Objects.requireNonNull(activeBranch, "activeBranch must not be null");
        declinedVersions = (declinedVersions == null) ? Set.of() : Set.copyOf(declinedVersions);
    }
}
