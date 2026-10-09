package com.auditplatform.template.fanout.port;

import com.auditplatform.template.fanout.model.EngagementReference;

import java.time.Duration;

/**
 * Port contract for the legacy downstream Engagement Management System (EMS).
 *
 * The downstream call rehydrates an engagement into memory pods (~1 min per file)
 * and is owned by another team with strictly limited capacity.
 */
public interface DownstreamRehydrationClient {

    RehydrationResponse rehydrateAndEvaluate(EngagementReference engagement, String targetVersion)
            throws DownstreamTransientException, DownstreamPermanentException;

    record RehydrationResponse(
            String engagementId,
            String targetVersion,
            boolean updateApplicable,
            Duration durationTaken
    ) {}

    class DownstreamException extends Exception {
        public DownstreamException(String message) {
            super(message);
        }

        public DownstreamException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class DownstreamTransientException extends DownstreamException {
        private final Duration suggestedRetryDelay;

        public DownstreamTransientException(String message) {
            this(message, Duration.ofSeconds(5));
        }

        public DownstreamTransientException(String message, Duration suggestedRetryDelay) {
            super(message);
            this.suggestedRetryDelay = suggestedRetryDelay;
        }

        public Duration getSuggestedRetryDelay() {
            return suggestedRetryDelay;
        }
    }

    class DownstreamPermanentException extends DownstreamException {
        public DownstreamPermanentException(String message) {
            super(message);
        }

        public DownstreamPermanentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
