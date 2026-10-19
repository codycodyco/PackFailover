package dev.cody.packfailover.tracking;

/**
 * Correlated state for one resource-pack attempt.
 *
 * <p>Terminal states never accept further status transitions. {@code DOWNLOADED}
 * is intentionally not terminal: the client may still report
 * {@code SUCCESSFULLY_LOADED} or {@code FAILED_RELOAD} afterwards.</p>
 */
public enum PackState {
    PRIMARY_PENDING,
    PRIMARY_DOWNLOADED,
    PRIMARY_LOADED,
    PRIMARY_FAILED_DOWNLOAD,
    FALLBACK_PENDING,
    FALLBACK_DOWNLOADED,
    RECOVERED,
    DECLINED,
    FAILED_RELOAD,
    INVALID_URL,
    DISCARDED,
    SKIPPED,
    EXHAUSTED,
    CANCELLED,
    SUPERSEDED;

    public boolean isTerminal() {
        return switch (this) {
            case PRIMARY_LOADED, RECOVERED, DECLINED, FAILED_RELOAD, INVALID_URL, DISCARDED,
                 SKIPPED, EXHAUSTED, CANCELLED, SUPERSEDED -> true;
            default -> false;
        };
    }

    public boolean isFallbackState() {
        return this == FALLBACK_PENDING || this == FALLBACK_DOWNLOADED
                || this == RECOVERED || this == EXHAUSTED;
    }
}
