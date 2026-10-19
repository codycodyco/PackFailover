package dev.cody.packfailover.failover;

import dev.cody.packfailover.artifact.VerifiedArtifact;
import dev.cody.packfailover.artifact.VerifiedArtifactRegistry;
import dev.cody.packfailover.bedrock.BedrockDetector;
import dev.cody.packfailover.config.PoConfig;
import dev.cody.packfailover.session.PlayerPackSessionStore;
import dev.cody.packfailover.tracking.CapturedSend;
import dev.cody.packfailover.tracking.OutgoingSendRegistry;
import dev.cody.packfailover.tracking.PackAttempt;
import dev.cody.packfailover.tracking.PackState;
import dev.cody.packfailover.tracking.PackStatus;
import dev.cody.packfailover.tracking.ProtocolPhase;
import dev.cody.packfailover.tracking.SendKind;
import dev.cody.packfailover.util.PfLogger;
import dev.cody.packfailover.util.UrlPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailoverCoordinatorTest {

    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";
    private static final String PRIMARY_URL = "https://packs.resourcepack.ai/h/" + SHA1 + ".zip";
    private static final String FALLBACK_URL = "http://fallback.example:8181/packs/" + SHA1 + ".zip";

    private final UUID player = UUID.randomUUID();
    private final UUID pack = UUID.randomUUID();

    private AtomicReference<PoConfig> config;
    private PlayerPackSessionStore sessions;
    private VerifiedArtifactRegistry artifacts;
    private OutgoingSendRegistry outgoing;
    private RecordingPlaySender sender;
    private FakeEndpoint endpoint;
    private FailoverCoordinator coordinator;
    private PfLogger log;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger("FailoverCoordinatorTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        log = new PfLogger(logger, () -> false);

        config = new AtomicReference<>(config(true, false, 1, false, true));
        sessions = new PlayerPackSessionStore(16, 32);
        artifacts = new VerifiedArtifactRegistry();
        artifacts.put(new VerifiedArtifact(SHA1, Path.of("/tmp/fake.zip"), 1024, Instant.now()));
        outgoing = new OutgoingSendRegistry();
        sender = new RecordingPlaySender();
        endpoint = new FakeEndpoint();
        coordinator = new FailoverCoordinator(
                config::get, sessions, artifacts, outgoing, endpoint, sender, new BedrockDetector(), log);
    }

    @Test
    void failedDownloadSendsOneFallbackWithSameUuidAndSha1() {
        beginPlayAndPush(false, PRIMARY_URL);

        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_DOWNLOAD);

        assertEquals(1, sender.count());
        Sent sent = sender.last();
        assertEquals(player, sent.playerId());
        assertEquals(pack, sent.packId());
        assertEquals(SHA1, sent.sha1());
        assertEquals(FALLBACK_URL, sent.url());
        assertFalse(sent.required());

        PackAttempt current = sessions.current(player, pack).orElseThrow();
        assertEquals(SendKind.FALLBACK, current.kind());
        assertEquals(PackState.FALLBACK_PENDING, current.state());
        assertEquals(1, outgoing.size());
        assertEquals(1, coordinator.fallbackPlannedCount());
    }

    @Test
    void secondFailedDownloadDoesNotLoop() {
        beginPlayAndPush(false, PRIMARY_URL);

        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_DOWNLOAD);
        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_DOWNLOAD);

        assertEquals(1, sender.count());
        assertEquals(PackState.EXHAUSTED, sessions.current(player, pack).orElseThrow().state());
        assertEquals(1, coordinator.fallbackPlannedCount());
    }

    @Test
    void successfullyLoadedFallbackCountsAsRecovered() {
        beginPlayAndPush(false, PRIMARY_URL);
        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_DOWNLOAD);

        coordinator.onPlayStatus(player, pack, PackStatus.SUCCESSFULLY_LOADED);

        assertEquals(PackState.RECOVERED, sessions.current(player, pack).orElseThrow().state());
        assertEquals(1, coordinator.recoveredCount());
    }

    @Test
    void declinedIsNeverRetried() {
        PackAttempt primary = beginPlayAndPush(false, PRIMARY_URL);

        coordinator.onPlayStatus(player, pack, PackStatus.DECLINED);

        assertEquals(0, sender.count());
        assertEquals(PackState.DECLINED, primary.state());
        assertEquals(0, coordinator.fallbackPlannedCount());
    }

    @Test
    void discardedDoesNotRetry() {
        PackAttempt primary = beginPlayAndPush(false, PRIMARY_URL);

        coordinator.onPlayStatus(player, pack, PackStatus.DISCARDED);

        assertEquals(0, sender.count());
        assertEquals(PackState.DISCARDED, primary.state());
    }

    @Test
    void fallbackOnlyTouchesFailedPackInStack() {
        sessions.beginConnection(player);
        PackAttempt first = sessions.recordSend(send(ProtocolPhase.PLAY, PRIMARY_URL, false), SendKind.PRIMARY);
        UUID secondPack = UUID.randomUUID();
        CapturedSend secondSend = new CapturedSend(
                player, secondPack, PRIMARY_URL + "?second", SHA1, "packs.resourcepack.ai",
                false, "", ProtocolPhase.PLAY, Instant.now());
        PackAttempt second = sessions.recordSend(secondSend, SendKind.PRIMARY);

        coordinator.onPlayStatus(player, secondPack, PackStatus.SUCCESSFULLY_LOADED);
        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_DOWNLOAD);

        assertEquals(1, sender.count());
        assertEquals(pack, sender.last().packId());
        assertEquals(PackState.PRIMARY_LOADED, second.state());
        assertEquals(SendKind.FALLBACK, sessions.current(player, pack).orElseThrow().kind());
        assertEquals(PackState.PRIMARY_LOADED, sessions.current(player, secondPack).orElseThrow().state());
        assertEquals(PackState.PRIMARY_FAILED_DOWNLOAD, first.state());
    }

    @Test
    void failedReloadDoesNotSwitchSource() {
        PackAttempt primary = beginPlayAndPush(false, PRIMARY_URL);

        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_RELOAD);

        assertEquals(0, sender.count());
        assertEquals(PackState.FAILED_RELOAD, primary.state());
    }

    @Test
    void missingVerifiedArtifactSkipsFallback() {
        artifacts = new VerifiedArtifactRegistry();
        coordinator = new FailoverCoordinator(
                config::get, sessions, artifacts, outgoing, endpoint, sender, new BedrockDetector(), log);
        beginPlayAndPush(false, PRIMARY_URL);

        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_DOWNLOAD);

        assertEquals(0, sender.count());
        assertEquals(1, coordinator.skippedCount());
    }

    @Test
    void nonAllowlistedSourceSkipsFallback() {
        beginPlayAndPush(false, "https://other.example/h/" + SHA1 + ".zip");

        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_DOWNLOAD);

        assertEquals(0, sender.count());
        assertEquals(1, coordinator.skippedCount());
    }

    @Test
    void dryRunDoesNotSend() {
        config.set(config(true, true, 1, false, true));
        PackAttempt primary = beginPlayAndPush(false, PRIMARY_URL);

        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_DOWNLOAD);

        assertEquals(0, sender.count());
        assertEquals(PackState.PRIMARY_FAILED_DOWNLOAD, primary.state());
        assertEquals(1, coordinator.skippedCount());
    }

    @Test
    void disabledFallbackDoesNotSendForPrimary() {
        config.set(config(false, false, 1, false, true));
        beginPlayAndPush(false, PRIMARY_URL);

        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_DOWNLOAD);

        assertEquals(0, sender.count());
        assertEquals(1, coordinator.skippedCount());
    }

    @Test
    void pocSessionBypassesDisabledAndDryRunFallback() {
        config.set(config(false, true, 1, false, true));
        sessions.beginConnection(player);
        sessions.recordSend(send(ProtocolPhase.PLAY, PRIMARY_URL, false), SendKind.POC_SIMULATED);

        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_DOWNLOAD);

        assertEquals(1, sender.count());
        assertEquals(SHA1, sender.last().sha1());
        assertEquals(FALLBACK_URL, sender.last().url());
    }

    @Test
    void configPhaseFallbackIsDisabledByDefault() {
        sessions.recordSend(send(ProtocolPhase.CONFIGURATION, PRIMARY_URL, false), SendKind.PRIMARY);
        sessions.beginConnection(player);

        assertTrue(coordinator.onConfigStatus(player, pack, PackStatus.FAILED_DOWNLOAD).isEmpty());
        assertEquals(0, sender.count());
        assertEquals(1, coordinator.skippedCount());
    }

    @Test
    void experimentalConfigPhaseReturnsPlanForPacketEventsTransport() {
        config.set(config(true, false, 1, true, true));
        sessions.recordSend(send(ProtocolPhase.CONFIGURATION, PRIMARY_URL, false), SendKind.PRIMARY);
        sessions.beginConnection(player);

        var plan = coordinator.onConfigStatus(player, pack, PackStatus.FAILED_DOWNLOAD);

        assertTrue(plan.isPresent());
        assertEquals(ProtocolPhase.CONFIGURATION, plan.get().request().phase());
        assertEquals(SendKind.FALLBACK, plan.get().fallbackAttempt().kind());
        assertEquals(FALLBACK_URL, plan.get().request().url());
        assertEquals(1, outgoing.size());
        // The PacketEvents adapter performs the actual config-phase send.
        assertEquals(0, sender.count());
    }

    @Test
    void httpNotReadySkipsFallback() {
        endpoint.setReady(false);
        beginPlayAndPush(false, PRIMARY_URL);

        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_DOWNLOAD);

        assertEquals(0, sender.count());
        assertEquals(1, coordinator.skippedCount());
    }

    @Test
    void requiredPackCanBeDisabledByConfig() {
        config.set(config(true, false, 1, false, false));
        beginPlayAndPush(true, PRIMARY_URL);

        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_DOWNLOAD);

        assertEquals(0, sender.count());
        assertEquals(1, coordinator.skippedCount());
    }

    @Test
    void maxAttemptsZeroSkips() {
        config.set(config(true, false, 0, false, true));
        beginPlayAndPush(false, PRIMARY_URL);

        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_DOWNLOAD);

        assertEquals(0, sender.count());
        assertEquals(1, coordinator.skippedCount());
    }

    @Test
    void terminalSuccessIgnoresLaterFailure() {
        beginPlayAndPush(false, PRIMARY_URL);
        coordinator.onPlayStatus(player, pack, PackStatus.SUCCESSFULLY_LOADED);

        coordinator.onPlayStatus(player, pack, PackStatus.FAILED_DOWNLOAD);

        assertEquals(0, sender.count());
        assertEquals(PackState.PRIMARY_LOADED, sessions.current(player, pack).orElseThrow().state());
    }

    @Test
    void manualResendPushesWithoutFailureEvent() {
        beginPlayAndPush(false, PRIMARY_URL);
        assertEquals(PackState.PRIMARY_PENDING, sessions.current(player, pack).orElseThrow().state());

        assertTrue(coordinator.manualResend(player, pack));

        assertEquals(1, sender.count());
        assertEquals(FALLBACK_URL, sender.last().url());
        assertEquals(SendKind.FALLBACK, sessions.current(player, pack).orElseThrow().kind());
        assertEquals(ProtocolPhase.PLAY, sessions.current(player, pack).orElseThrow().phase());
    }

    @Test
    void manualResendRejectsUnknownPack() {
        sessions.beginConnection(player);
        assertFalse(coordinator.manualResend(player, UUID.randomUUID()));
        assertEquals(0, sender.count());
    }

    @Test
    void unmatchedStatusIsIgnored() {
        sessions.beginConnection(player);
        coordinator.onPlayStatus(player, UUID.randomUUID(), PackStatus.FAILED_DOWNLOAD);
        assertEquals(0, sender.count());
    }

    private PackAttempt beginPlayAndPush(boolean required, String url) {
        sessions.beginConnection(player);
        return sessions.recordSend(send(ProtocolPhase.PLAY, url, required), SendKind.PRIMARY);
    }

    private CapturedSend send(ProtocolPhase phase, String url, boolean required) {
        return new CapturedSend(
                player,
                pack,
                url,
                SHA1,
                UrlPolicy.host(url).orElse(""),
                required,
                "",
                phase,
                Instant.now()
        );
    }

    private static PoConfig config(
            boolean fallbackEnabled,
            boolean dryRun,
            int maxAttempts,
            boolean configPhase,
            boolean allowRequired
    ) {
        return new PoConfig(
                true,
                new PoConfig.Tracking(true, true, List.of("packs.resourcepack.ai"), 16, 32),
                new PoConfig.Providers(0, 200, List.of()),
                new PoConfig.Fallback(fallbackEnabled, dryRun, maxAttempts, configPhase, allowRequired),
                new PoConfig.Http(true, "127.0.0.1", 8181, "http://fallback.example:8181", 4, 32, 3600),
                new PoConfig.Cache("build/test-cache"),
                new PoConfig.Artifacts(10),
                new PoConfig.Poc("http://127.0.0.1:1/{sha1}.zip"),
                new PoConfig.Logging(false)
        );
    }

    private static final class FakeEndpoint implements FallbackEndpoint {
        private volatile boolean ready = true;

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public boolean isServable(String sha1) {
            return ready;
        }

        @Override
        public java.util.Optional<String> publicUrlFor(String sha1) {
            return ready ? java.util.Optional.of(
                    "http://fallback.example:8181/packs/" + sha1 + ".zip") : java.util.Optional.empty();
        }

        void setReady(boolean ready) {
            this.ready = ready;
        }
    }

    private record Sent(
            UUID playerId,
            UUID packId,
            String url,
            String sha1,
            boolean required,
            String prompt
    ) {
    }

    private static final class RecordingPlaySender implements PlayPackSender {
        private final List<Sent> sent = new ArrayList<>();

        @Override
        public void send(
                UUID playerId,
                UUID packId,
                String url,
                String sha1,
                boolean required,
                String prompt,
                Consumer<String> onFailure
        ) {
            sent.add(new Sent(playerId, packId, url, sha1, required, prompt));
        }

        int count() {
            return sent.size();
        }

        Sent last() {
            return sent.get(sent.size() - 1);
        }
    }
}
