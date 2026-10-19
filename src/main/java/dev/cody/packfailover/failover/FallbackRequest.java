package dev.cody.packfailover.failover;

import dev.cody.packfailover.tracking.PackAttempt;
import dev.cody.packfailover.tracking.ProtocolPhase;

/**
 * Fully validated inputs for one fallback push.
 */
public record FallbackRequest(
        PackAttempt parent,
        String url,
        String sha1,
        boolean required,
        String prompt,
        ProtocolPhase phase,
        boolean poc
) {
}
