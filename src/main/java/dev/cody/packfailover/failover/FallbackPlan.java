package dev.cody.packfailover.failover;

import dev.cody.packfailover.tracking.PackAttempt;

/**
 * A fallback request plus the attempt record created for it.
 */
public record FallbackPlan(FallbackRequest request, PackAttempt fallbackAttempt) {
}
