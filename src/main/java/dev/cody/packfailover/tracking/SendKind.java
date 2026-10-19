package dev.cody.packfailover.tracking;

/**
 * Describes who caused a server-to-client resource-pack push.
 */
public enum SendKind {
    /** A push captured from another plugin (for example RPEngine). */
    PRIMARY,
    /** A test push issued by {@code /pf poc} that reuses a captured pack UUID. */
    POC_SIMULATED,
    /** The single failover push issued by PackFailover itself. */
    FALLBACK
}
