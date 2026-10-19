package dev.cody.packfailover;

import com.github.retrooper.packetevents.PacketEvents;
import dev.cody.packfailover.artifact.ArtifactVerifier;
import dev.cody.packfailover.artifact.VerifiedArtifactRegistry;
import dev.cody.packfailover.bedrock.BedrockDetector;
import dev.cody.packfailover.cache.ImmutablePackCache;
import dev.cody.packfailover.command.PfCommand;
import dev.cody.packfailover.config.ConfigManager;
import dev.cody.packfailover.config.PoConfig;
import dev.cody.packfailover.failover.BukkitPlayPackSender;
import dev.cody.packfailover.failover.FallbackEndpoint;
import dev.cody.packfailover.failover.FailoverCoordinator;
import dev.cody.packfailover.failover.PlayPackSender;
import dev.cody.packfailover.http.HttpPackServer;
import dev.cody.packfailover.index.ArtifactIndexer;
import dev.cody.packfailover.provider.DirectoryPackProvider;
import dev.cody.packfailover.provider.PackProvider;
import dev.cody.packfailover.provider.RPEnginePackProvider;
import dev.cody.packfailover.session.PlayerPackSessionStore;
import dev.cody.packfailover.tracking.OutgoingSendRegistry;
import dev.cody.packfailover.tracking.PackRequestTracker;
import dev.cody.packfailover.tracking.PackStatusListener;
import dev.cody.packfailover.util.PfLogger;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PackFailover Stage 1 entry point.
 *
 * <p>Passively captures resource-pack pushes, indexes operator/plugin ZIP
 * directories by full SHA-1, serves immutable cached ZIPs over HTTP, and
 * automatically retries a failed download once with the same pack UUID and
 * SHA-1 from the fallback endpoint. The original distribution path is never
 * modified or cancelled.</p>
 */
public final class PackFailoverPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private PlayerPackSessionStore sessions;
    private VerifiedArtifactRegistry artifacts;
    private OutgoingSendRegistry outgoing;
    private BedrockDetector bedrock;
    private FailoverCoordinator coordinator;
    private PackRequestTracker tracker;
    private PlayPackSender playSender;
    private ArtifactVerifier artifactVerifier;
    private ImmutablePackCache cache;
    private HttpPackServer httpServer;
    private ArtifactIndexer indexer;
    private List<PackProvider> providers = List.of();
    private ExecutorService ioExecutor;
    private PfLogger log;
    private BukkitTask maintenanceTask;
    private BukkitTask reindexTask;
    private volatile boolean packetEventsHooked;
    private final AtomicLong lastMaintenanceLog = new AtomicLong();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        PoConfig initial = configManager.load();
        log = new PfLogger(getLogger(), () -> configManager.current().logging().debug());

        sessions = new PlayerPackSessionStore(
                initial.tracking().maxTrackedPlayers(),
                initial.tracking().historyPerPlayer());
        artifacts = new VerifiedArtifactRegistry();
        outgoing = new OutgoingSendRegistry();
        bedrock = new BedrockDetector();

        ioExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "PackFailover-IO");
            thread.setDaemon(true);
            return thread;
        });
        artifactVerifier = new ArtifactVerifier(
                ioExecutor,
                () -> configManager.current().artifacts().maxSizeMb(),
                log);

        providers = buildProviders(initial);
        try {
            cache = new ImmutablePackCache(resolvePath(initial.cache().directory()), log);
        } catch (IOException e) {
            cache = null;
            log.warn("cache_init_failed directory=" + initial.cache().directory()
                    + " error=" + e.getMessage() + "; HTTP and automatic fallback are disabled.");
        }

        FallbackEndpoint endpoint = FallbackEndpoint.disabled();
        if (initial.http().enabled() && cache != null) {
            HttpPackServer server = new HttpPackServer(
                    initial.http().bindAddress(),
                    initial.http().port(),
                    initial.http().publicUrl(),
                    initial.http().workerThreads(),
                    initial.http().backlog(),
                    initial.http().cacheControlSeconds(),
                    cache,
                    log);
            try {
                server.start();
                httpServer = server;
                endpoint = server;
            } catch (IOException e) {
                log.warn("http_start_failed bind=" + initial.http().bindAddress() + ":"
                        + initial.http().port() + " error=" + e.getMessage()
                        + "; automatic fallback is disabled, tracking continues.");
            }
        } else if (!initial.http().enabled()) {
            log.info("http_disabled: fallback URLs cannot be generated, tracking continues.");
        }

        playSender = new BukkitPlayPackSender(this, log);
        coordinator = new FailoverCoordinator(
                configManager::current,
                sessions,
                artifacts,
                outgoing,
                endpoint,
                playSender,
                bedrock,
                log);

        getServer().getPluginManager().registerEvents(
                new PackStatusListener(configManager::current, sessions, coordinator, log), this);

        registerPacketEvents();

        if (cache != null) {
            indexer = new ArtifactIndexer(providers, artifactVerifier, cache, artifacts, log);
            indexer.reindexAsync();
        }

        PfCommand command = new PfCommand(
                this,
                configManager,
                configManager::current,
                sessions,
                artifacts,
                artifactVerifier,
                cache,
                httpServer,
                indexer,
                providers,
                coordinator,
                outgoing,
                playSender,
                this::packetEventsHooked,
                log);
        PluginCommand pf = getCommand("pf");
        if (pf == null) {
            getLogger().severe("plugin.yml is missing the /pf command definition; admin commands are unavailable.");
        } else {
            pf.setExecutor(command);
            pf.setTabCompleter(command);
        }

        maintenanceTask = getServer().getScheduler().runTaskTimer(
                this,
                this::logCounters,
                20L * 300L,
                20L * 300L);

        if (indexer != null && initial.providers().reindexSeconds() > 0) {
            long period = 20L * initial.providers().reindexSeconds();
            reindexTask = getServer().getScheduler().runTaskTimer(
                    this,
                    () -> {
                        if (indexer != null) {
                            indexer.reindexAsync();
                        }
                    },
                    20L * 10L,
                    period);
        }

        log.info("stage1_enabled paper=26.2 java=25"
                + " packetevents=" + (packetEventsHooked ? "hooked" : "UNAVAILABLE")
                + " fallbackEnabled=" + initial.fallback().enabled()
                + " dryRun=" + initial.fallback().dryRun()
                + " configPhase=" + initial.fallback().configPhase()
                + " maxAttempts=" + initial.fallback().maxAttemptsPerPack()
                + " allowlist=" + initial.tracking().primaryHostAllowlist());
        log.info("stage1_http ready=" + (httpServer != null && httpServer.isReady())
                + " bind=" + initial.http().bindAddress() + ":" + initial.http().port()
                + " publicUrl=" + initial.describePublicUrl()
                + " cache=" + (cache == null ? "<unavailable>" : cache.directory()));
        log.info("stage1_providers " + providers.stream().map(PackProvider::description).toList());
        if (!bedrock.available()) {
            log.info("floodgate_not_present: Bedrock detection is inactive; Java packs are still gated by tracking/allowlist.");
        }
    }

    @Override
    public void onDisable() {
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
            maintenanceTask = null;
        }
        if (reindexTask != null) {
            reindexTask.cancel();
            reindexTask = null;
        }
        if (packetEventsHooked && tracker != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(tracker);
            } catch (Throwable throwable) {
                getLogger().warning("Could not unregister PacketEvents listener: " + throwable);
            }
        }
        tracker = null;
        if (sessions != null) {
            sessions.cancelAll("plugin_disabled");
        }
        if (outgoing != null) {
            outgoing.clear();
        }
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        }
        if (indexer != null) {
            indexer.close();
            indexer = null;
        }
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
        }
        if (log != null) {
            log.info("stage1_disabled");
        }
    }

    private List<PackProvider> buildProviders(PoConfig config) {
        List<PackProvider> result = new ArrayList<>();
        Path serverRoot = Path.of("").toAbsolutePath();
        for (PoConfig.Providers.Directory directory : config.providers().directories()) {
            if (!directory.enabled()) {
                log.info("provider_disabled id=" + directory.id());
                continue;
            }
            Path root;
            try {
                root = Path.of(directory.path());
            } catch (InvalidPathException e) {
                log.warn("provider_invalid_path id=" + directory.id() + " path=" + directory.path());
                continue;
            }
            if (!root.isAbsolute()) {
                root = serverRoot.resolve(root);
            }
            Path normalized = root.normalize();
            if ("rpengine".equals(directory.id())) {
                result.add(new RPEnginePackProvider(
                        normalized, directory.glob(), directory.maxDepth(),
                        config.providers().maxCandidatesPerProvider(), log));
            } else {
                result.add(new DirectoryPackProvider(
                        directory.id(), normalized, directory.glob(), directory.maxDepth(),
                        config.providers().maxCandidatesPerProvider(), log));
            }
            log.info("provider_configured id=" + directory.id() + " root=" + normalized
                    + " glob=" + directory.glob() + " maxDepth=" + directory.maxDepth());
        }
        return List.copyOf(result);
    }

    private Path resolvePath(String raw) {
        Path path = Path.of(raw);
        return path.isAbsolute() ? path.normalize() : Path.of("").toAbsolutePath().resolve(path).normalize();
    }

    private void registerPacketEvents() {
        try {
            if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().isInitialized()) {
                log.warn("packetevents_unavailable: PacketEvents API is not initialized. Passive capture and the CONFIG status bridge are disabled.");
                return;
            }
            tracker = new PackRequestTracker(
                    configManager::current,
                    sessions,
                    outgoing,
                    coordinator,
                    log);
            PacketEvents.getAPI().getEventManager().registerListener(tracker);
            packetEventsHooked = true;
            log.info("packetevents_hooked version=" + PacketEvents.getAPI().getVersion());
        } catch (Throwable throwable) {
            packetEventsHooked = false;
            log.warn("packetevents_hook_failed: capture disabled; the plugin stays loaded and /pf status remains available.", throwable);
        }
    }

    private boolean packetEventsHooked() {
        return packetEventsHooked;
    }

    private void logCounters() {
        if (sessions.activeAttempts() == 0 && !configManager.current().logging().debug()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMaintenanceLog.get() < 60_000L) {
            return;
        }
        lastMaintenanceLog.set(now);
        log.info("counters trackedPlayers=" + sessions.trackedPlayers()
                + " activeAttempts=" + sessions.activeAttempts()
                + " indexedArtifacts=" + artifacts.size()
                + " cacheFiles=" + (cache == null ? -1 : cache.size())
                + " httpReady=" + (httpServer != null && httpServer.isReady())
                + " fallbackPlanned=" + coordinator.fallbackPlannedCount()
                + " recovered=" + coordinator.recoveredCount()
                + " skipped=" + coordinator.skippedCount());
    }
}
