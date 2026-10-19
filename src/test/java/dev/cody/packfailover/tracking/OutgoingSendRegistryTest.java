package dev.cody.packfailover.tracking;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutgoingSendRegistryTest {

    private final UUID player = UUID.randomUUID();
    private final UUID pack = UUID.randomUUID();
    private final OutgoingSendRegistry registry = new OutgoingSendRegistry();

    @Test
    void consumesMatchingMarkerOnce() {
        registry.expect(player, pack, "http://fallback.example:8181/x.zip", SendKind.FALLBACK);

        Optional<SendKind> first = registry.consume(player, pack, "http://fallback.example:8181/x.zip");
        Optional<SendKind> second = registry.consume(player, pack, "http://fallback.example:8181/x.zip");

        assertTrue(first.isPresent());
        assertEquals(SendKind.FALLBACK, first.get());
        assertTrue(second.isEmpty());
    }

    @Test
    void doesNotMatchDifferentUrlOrPack() {
        registry.expect(player, pack, "http://fallback.example:8181/x.zip", SendKind.FALLBACK);

        assertTrue(registry.consume(player, pack, "http://fallback.example:8181/y.zip").isEmpty());
        assertTrue(registry.consume(player, UUID.randomUUID(), "http://fallback.example:8181/x.zip").isEmpty());
        assertTrue(registry.consume(UUID.randomUUID(), pack, "http://fallback.example:8181/x.zip").isEmpty());
        assertEquals(1, registry.size());
    }

    @Test
    void clearRemovesMarkers() {
        registry.expect(player, pack, "http://fallback.example:8181/x.zip", SendKind.FALLBACK);
        registry.clear();
        assertTrue(registry.consume(player, pack, "http://fallback.example:8181/x.zip").isEmpty());
    }
}
