package dev.cody.packfailover.tracking;

import dev.cody.packfailover.session.PlayerPackSessionStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerPackSessionStoreTest {

    private static final String SHA1 = "abcdef0123456789abcdef0123456789abcdef01";
    private static final String PRIMARY_URL = "https://packs.resourcepack.ai/h/" + SHA1 + ".zip";
    private static final String FALLBACK_URL = "http://fallback.example:8181/packs/" + SHA1 + ".zip";

    private final UUID player = UUID.randomUUID();
    private final UUID pack = UUID.randomUUID();
    private final PlayerPackSessionStore store = new PlayerPackSessionStore(4, 8);

    private CapturedSend send(String url, ProtocolPhase phase) {
        return new CapturedSend(player, pack, url, SHA1, "packs.resourcepack.ai",
                false, "prompt", phase, Instant.now());
    }

    @Test
    void configurationPhasePushIsAdoptedOnJoin() {
        PackAttempt attempt = store.recordSend(send(PRIMARY_URL, ProtocolPhase.CONFIGURATION), SendKind.PRIMARY);
        assertEquals(0, attempt.connectionEpoch());

        long epoch = store.beginConnection(player);
        assertEquals(1, epoch);
        assertEquals(1, attempt.connectionEpoch());
        assertTrue(store.isCurrent(attempt));
    }

    @Test
    void repeatedPushCreatesNewGenerationAndSupersedesOld() {
        store.beginConnection(player);
        PackAttempt first = store.recordSend(send(PRIMARY_URL, ProtocolPhase.PLAY), SendKind.PRIMARY);
        PackAttempt second = store.recordSend(send(PRIMARY_URL + "?v=2", ProtocolPhase.PLAY), SendKind.PRIMARY);

        assertEquals(1, first.generation());
        assertEquals(2, second.generation());
        assertEquals(PackState.SUPERSEDED, first.state());
        assertTrue(store.isCurrent(second));
        assertFalse(store.isCurrent(first));
    }

    @Test
    void fallbackIsCreatedOnceWithSamePackAndSha1() {
        store.beginConnection(player);
        PackAttempt primary = store.recordSend(send(PRIMARY_URL, ProtocolPhase.PLAY), SendKind.PRIMARY);

        Optional<PackAttempt> fallback = store.beginFallback(primary, FALLBACK_URL, 1);
        assertTrue(fallback.isPresent());
        assertEquals(SendKind.FALLBACK, fallback.get().kind());
        assertEquals(pack, fallback.get().packId());
        assertEquals(SHA1, fallback.get().sha1());
        assertEquals(FALLBACK_URL, fallback.get().url());
        assertEquals(1, store.fallbackCount(player, pack));
        assertTrue(store.isCurrent(fallback.get()));

        assertTrue(store.beginFallback(primary, FALLBACK_URL, 1).isEmpty());
    }

    @Test
    void maxAttemptsZeroBlocksFallback() {
        store.beginConnection(player);
        PackAttempt primary = store.recordSend(send(PRIMARY_URL, ProtocolPhase.PLAY), SendKind.PRIMARY);
        assertTrue(store.beginFallback(primary, FALLBACK_URL, 0).isEmpty());
        assertEquals(0, store.fallbackCount(player, pack));
    }

    @Test
    void endConnectionCancelsAndClearsCurrent() {
        store.beginConnection(player);
        PackAttempt primary = store.recordSend(send(PRIMARY_URL, ProtocolPhase.PLAY), SendKind.PRIMARY);

        store.endConnection(player);

        assertEquals(PackState.CANCELLED, primary.state());
        assertTrue(store.current(player, pack).isEmpty());
        assertEquals(2, store.currentEpoch(player));
    }

    @Test
    void rejoinAdoptsOnlyNewestConfigurationPush() {
        store.beginConnection(player);
        PackAttempt firstConnection = store.recordSend(send(PRIMARY_URL, ProtocolPhase.PLAY), SendKind.PRIMARY);
        store.endConnection(player);

        PackAttempt secondConfig = store.recordSend(send(PRIMARY_URL + "?v=2", ProtocolPhase.CONFIGURATION), SendKind.PRIMARY);
        long epoch = store.beginConnection(player);

        assertEquals(epoch, secondConfig.connectionEpoch());
        assertTrue(store.isCurrent(secondConfig));
        assertEquals(PackState.CANCELLED, firstConnection.state());
    }

    @Test
    void rejoinResetsFallbackBudget() {
        store.beginConnection(player);
        PackAttempt primary = store.recordSend(send(PRIMARY_URL, ProtocolPhase.PLAY), SendKind.PRIMARY);
        assertTrue(store.beginFallback(primary, FALLBACK_URL, 1).isPresent());
        assertEquals(1, store.fallbackCount(player, pack));

        store.beginConnection(player);

        assertEquals(0, store.fallbackCount(player, pack));
    }

    @Test
    void pocReplayUsesSamePackUuidWithFailingUrl() {
        store.beginConnection(player);
        PackAttempt primary = store.recordSend(send(PRIMARY_URL, ProtocolPhase.PLAY), SendKind.PRIMARY);

        PackAttempt poc = store.registerPocSimulated(primary, "http://127.0.0.1:1/" + SHA1 + ".zip");

        assertEquals(SendKind.POC_SIMULATED, poc.kind());
        assertEquals(primary.packId(), poc.packId());
        assertEquals(primary.sha1(), poc.sha1());
        assertNotEquals(primary.url(), poc.url());
        assertTrue(store.isCurrent(poc));
        assertTrue(store.latestReplayable(player, pack).isPresent());
    }

    @Test
    void historyIsBounded() {
        PlayerPackSessionStore bounded = new PlayerPackSessionStore(4, 3);
        UUID p = UUID.randomUUID();
        UUID k = UUID.randomUUID();
        bounded.beginConnection(p);
        for (int i = 0; i < 10; i++) {
            bounded.recordSend(new CapturedSend(p, k, PRIMARY_URL + "?i=" + i, SHA1,
                    "packs.resourcepack.ai", false, "", ProtocolPhase.PLAY, Instant.now()), SendKind.PRIMARY);
        }
        assertEquals(3, bounded.history(p).size());
    }
}
