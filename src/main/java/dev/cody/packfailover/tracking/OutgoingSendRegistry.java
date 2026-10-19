package dev.cody.packfailover.tracking;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Marks resource-pack packets that PackFailover itself is about to send so the
 * passive tracker does not register them as fresh primary requests and create a
 * failover loop.
 *
 * <p>The marker is keyed by player, pack UUID and exact URL. Entries expire so a
 * lost marker cannot suppress a genuine third-party push forever.</p>
 */
public final class OutgoingSendRegistry {

    private static final long DEFAULT_TTL_NANOS = 5_000_000_000L;

    private record Key(UUID playerId, UUID packId, String url) {
    }

    private record Marker(SendKind kind, long expiresAtNanos) {
    }

    private final ConcurrentHashMap<Key, Marker> markers = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepNanos = new AtomicLong(System.nanoTime());

    public void expect(UUID playerId, UUID packId, String url, SendKind kind) {
        if (playerId == null || packId == null || url == null || kind == null) {
            return;
        }
        sweep();
        markers.put(new Key(playerId, packId, url), new Marker(kind, System.nanoTime() + DEFAULT_TTL_NANOS));
    }

    /**
     * Consumes a matching marker, if present.
     */
    public Optional<SendKind> consume(UUID playerId, UUID packId, String url) {
        if (playerId == null || packId == null || url == null) {
            return Optional.empty();
        }
        Marker marker = markers.remove(new Key(playerId, packId, url));
        if (marker == null || marker.expiresAtNanos() < System.nanoTime()) {
            return Optional.empty();
        }
        return Optional.of(marker.kind());
    }

    public int size() {
        return markers.size();
    }

    public void clear() {
        markers.clear();
    }

    private void sweep() {
        long now = System.nanoTime();
        long last = lastSweepNanos.get();
        if (now - last < 1_000_000_000L) {
            return;
        }
        if (lastSweepNanos.compareAndSet(last, now)) {
            markers.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() < now);
        }
    }
}
