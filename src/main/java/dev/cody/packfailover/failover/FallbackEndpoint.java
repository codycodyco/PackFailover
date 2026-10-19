package dev.cody.packfailover.failover;

import java.util.Optional;

/**
 * Abstraction over the fallback HTTP endpoint used by the coordinator.
 */
public interface FallbackEndpoint {

    boolean isReady();

    boolean isServable(String sha1);

    Optional<String> publicUrlFor(String sha1);

    /**
     * Endpoint used when the HTTP server is disabled or failed to start. It never
     * reports readiness, so the coordinator safely skips fallback.
     */
    static FallbackEndpoint disabled() {
        return new FallbackEndpoint() {
            @Override
            public boolean isReady() {
                return false;
            }

            @Override
            public boolean isServable(String sha1) {
                return false;
            }

            @Override
            public Optional<String> publicUrlFor(String sha1) {
                return Optional.empty();
            }
        };
    }
}
