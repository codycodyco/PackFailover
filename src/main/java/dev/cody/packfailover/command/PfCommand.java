package dev.cody.packfailover.command;

import dev.cody.packfailover.artifact.ArtifactVerifier;
import dev.cody.packfailover.artifact.VerifiedArtifact;
import dev.cody.packfailover.artifact.VerifiedArtifactRegistry;
import dev.cody.packfailover.cache.ImmutablePackCache;
import dev.cody.packfailover.config.ConfigManager;
import dev.cody.packfailover.config.PoConfig;
import dev.cody.packfailover.failover.FailoverCoordinator;
import dev.cody.packfailover.failover.PlayPackSender;
import dev.cody.packfailover.http.HttpPackServer;
import dev.cody.packfailover.index.ArtifactIndexer;
import dev.cody.packfailover.provider.PackProvider;
import dev.cody.packfailover.session.PlayerPackSessionStore;
import dev.cody.packfailover.tracking.OutgoingSendRegistry;
import dev.cody.packfailover.tracking.PackAttempt;
import dev.cody.packfailover.tracking.PackState;
import dev.cody.packfailover.tracking.SendKind;
import dev.cody.packfailover.util.PfLogger;
import dev.cody.packfailover.util.Sha1Utils;
import dev.cody.packfailover.util.UrlPolicy;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Administrative commands and opt-in diagnostic tools.
 */
public final class PfCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT = List.of(
            "status", "debug", "index", "cache", "reload", "artifact", "poc", "help");
    private static final List<String> POC = List.of("simulate", "send", "resend", "abort");

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final Supplier<PoConfig> config;
    private final PlayerPackSessionStore sessions;
    private final VerifiedArtifactRegistry artifacts;
    private final ArtifactVerifier verifier;
    private final ImmutablePackCache cache;
    private final HttpPackServer httpServer;
    private final ArtifactIndexer indexer;
    private final List<PackProvider> providers;
    private final FailoverCoordinator coordinator;
    private final OutgoingSendRegistry outgoing;
    private final PlayPackSender playSender;
    private final BooleanSupplier packetEventsReady;
    private final PfLogger log;

    public PfCommand(
            JavaPlugin plugin,
            ConfigManager configManager,
            Supplier<PoConfig> config,
            PlayerPackSessionStore sessions,
            VerifiedArtifactRegistry artifacts,
            ArtifactVerifier verifier,
            ImmutablePackCache cache,
            HttpPackServer httpServer,
            ArtifactIndexer indexer,
            List<PackProvider> providers,
            FailoverCoordinator coordinator,
            OutgoingSendRegistry outgoing,
            PlayPackSender playSender,
            BooleanSupplier packetEventsReady,
            PfLogger log
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.config = config;
        this.sessions = sessions;
        this.artifacts = artifacts;
        this.verifier = verifier;
        this.cache = cache;
        this.httpServer = httpServer;
        this.indexer = indexer;
        this.providers = List.copyOf(providers);
        this.coordinator = coordinator;
        this.outgoing = outgoing;
        this.playSender = playSender;
        this.packetEventsReady = packetEventsReady;
        this.log = log;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("packfailover.admin")) {
            msg(sender, "No permission (packfailover.admin).");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "status" -> status(sender);
            case "debug" -> debug(sender, args);
            case "index" -> index(sender);
            case "cache" -> cache(sender);
            case "reload" -> reload(sender);
            case "artifact" -> artifact(sender, args);
            case "poc" -> {
                if (!config.get().poc().enabled()) {
                    msg(sender, "PoC diagnostics are disabled. Enable poc.enabled on a test server and run /pf reload.");
                } else {
                    poc(sender, args);
                }
            }
            default -> help(sender);
        }
        return true;
    }

    private void status(CommandSender sender) {
        PoConfig cfg = config.get();
        boolean httpReady = httpServer != null && httpServer.isReady();
        msg(sender, "PackFailover (Paper 26.2 / Java 25)");
        msg(sender, "enabled=" + cfg.enabled()
                + " packetevents=" + (packetEventsReady.getAsBoolean() ? "ready" : "UNAVAILABLE")
                + " http=" + (httpReady ? "ready" : "DISABLED/NOT-READY")
                + " publicUrl=" + cfg.describePublicUrl());
        msg(sender, "tracking config=" + cfg.tracking().trackConfigPhase()
                + " play=" + cfg.tracking().trackPlayPhase()
                + " allowlist=" + cfg.tracking().primaryHostAllowlist()
                + " trackedPlayers=" + sessions.trackedPlayers()
                + " activeAttempts=" + sessions.activeAttempts());
        msg(sender, "fallback enabled=" + cfg.fallback().enabled()
                + " dryRun=" + cfg.fallback().dryRun()
                + " maxAttempts=" + cfg.fallback().maxAttemptsPerPack()
                + " configPhase=" + cfg.fallback().configPhase()
                + " allowRequired=" + cfg.fallback().allowRequired());
        msg(sender, "index indexed=" + artifacts.size()
                + " cacheFiles=" + (cache == null ? -1 : cache.size())
                + " cacheDir=" + (cache == null ? "<unavailable>" : cache.directory()));
        ArtifactIndexer.IndexResult last = indexer == null ? null : indexer.lastResult();
        if (last != null) {
            msg(sender, "index.last providers=" + last.providers()
                    + " candidates=" + last.candidates()
                    + " indexed=" + last.indexed()
                    + " reused=" + last.reused()
                    + " rejected=" + last.rejected()
                    + " errors=" + last.errors()
                    + " durationMs=" + last.durationMs()
                    + " message=" + last.message());
        }
        for (PackProvider provider : providers) {
            msg(sender, "provider " + provider.description());
        }
        msg(sender, "fallbackPlanned=" + coordinator.fallbackPlannedCount()
                + " recovered=" + coordinator.recoveredCount()
                + " skipped=" + coordinator.skippedCount());
        msg(sender, "Commands: /pf index, /pf cache, /pf debug <player>, /pf artifact <zip>");
    }

    private void debug(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage: /pf debug <player>");
            return;
        }
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) {
            msg(sender, "Player is not online: " + args[1]);
            return;
        }
        UUID playerId = player.getUniqueId();
        msg(sender, "debug player=" + player.getName() + " uuid=" + playerId
                + " epoch=" + sessions.currentEpoch(playerId));
        List<PackAttempt> current = sessions.currentAttempts(playerId);
        if (current.isEmpty()) {
            msg(sender, "  current: none");
        } else {
            for (PackAttempt attempt : current) {
                msg(sender, "  current: " + attempt.describe());
            }
        }
        List<PackAttempt> history = sessions.history(playerId);
        msg(sender, "  history (" + history.size() + "):");
        for (PackAttempt attempt : history) {
            msg(sender, "    " + attempt.describe());
        }
    }

    private void index(CommandSender sender) {
        if (indexer == null) {
            msg(sender, "Indexer is unavailable (cache/HTTP startup failed); see console.");
            return;
        }
        if (indexer.isRunning()) {
            msg(sender, "Indexing is already running.");
            return;
        }
        msg(sender, "Indexing providers asynchronously...");
        indexer.reindexAsync().whenComplete((result, throwable) -> runMain(() -> {
            if (throwable != null) {
                msg(sender, "Index failed: " + ArtifactVerifier.failureMessage(throwable));
                return;
            }
            msg(sender, "Index done: candidates=" + result.candidates()
                    + " indexed=" + result.indexed()
                    + " reused=" + result.reused()
                    + " rejected=" + result.rejected()
                    + " errors=" + result.errors()
                    + " durationMs=" + result.durationMs()
                    + " message=" + result.message());
            msg(sender, "Verified artifacts=" + artifacts.size() + ", cacheFiles=" + cache.size());
        }));
    }

    private void cache(CommandSender sender) {
        if (cache == null) {
            msg(sender, "Cache is unavailable; see console.");
            return;
        }
        msg(sender, "Cache directory: " + cache.directory());
        List<Path> files = cache.files();
        if (files.isEmpty()) {
            msg(sender, "  (empty)");
            return;
        }
        for (Path file : files) {
            try {
                msg(sender, "  " + file.getFileName() + " (" + Files.size(file) + " bytes)");
            } catch (IOException e) {
                msg(sender, "  " + file.getFileName() + " (unreadable)");
            }
        }
    }

    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        PoConfig loaded = configManager.load();
        msg(sender, "Reloaded. enabled=" + loaded.enabled()
                + " fallbackEnabled=" + loaded.fallback().enabled()
                + " dryRun=" + loaded.fallback().dryRun()
                + " configPhase=" + loaded.fallback().configPhase());
        msg(sender, "Note: HTTP bind/port/public-url, providers and reindex interval changes require a server restart.");
    }

    private void artifact(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "Usage: /pf artifact <zip-path> | /pf artifact list");
            return;
        }
        if ("list".equalsIgnoreCase(args[1])) {
            var all = artifacts.all();
            if (all.isEmpty()) {
                msg(sender, "No verified artifacts.");
                return;
            }
            for (VerifiedArtifact artifact : all) {
                msg(sender, "  sha1=" + artifact.sha1()
                        + " size=" + artifact.sizeBytes()
                        + " file=" + artifact.file());
            }
            return;
        }
        if (cache == null) {
            msg(sender, "Cache is unavailable; cannot add a servable artifact.");
            return;
        }
        String rawPath = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Path path;
        try {
            path = Path.of(rawPath);
        } catch (RuntimeException e) {
            msg(sender, "Invalid path: " + rawPath);
            return;
        }
        msg(sender, "Verifying " + path.toAbsolutePath().normalize() + " asynchronously...");
        verifier.verifyAsync(path)
                .thenApply(artifact -> {
                    try {
                        Path cached = cache.store(artifact);
                        return new VerifiedArtifact(
                                artifact.sha1(), cached, Files.size(cached), artifact.verifiedAt());
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                })
                .whenComplete((artifact, throwable) -> runMain(() -> {
                    if (throwable != null) {
                        msg(sender, "Verification failed: " + ArtifactVerifier.failureMessage(throwable));
                        return;
                    }
                    artifacts.put(artifact);
                    msg(sender, "Verified sha1=" + artifact.sha1()
                            + " size=" + artifact.sizeBytes()
                            + " file=" + artifact.file());
                    msg(sender, "Cached and servable at " + config.get().describePublicUrl()
                            + "/packs/" + artifact.sha1() + ".zip");
                }));
    }

    private void poc(CommandSender sender, String[] args) {
        if (args.length < 2) {
            pocHelp(sender);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "simulate" -> pocSimulate(sender, args);
            case "send" -> pocSend(sender, args);
            case "resend" -> pocResend(sender, args);
            case "abort" -> pocAbort(sender, args);
            default -> pocHelp(sender);
        }
    }

    private void pocSimulate(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg(sender, "Usage: /pf poc simulate <player> [pack-uuid]");
            return;
        }
        Player player = Bukkit.getPlayerExact(args[2]);
        if (player == null) {
            msg(sender, "Player is not online: " + args[2]);
            return;
        }
        UUID filter = null;
        if (args.length >= 4) {
            filter = parseUuid(args[3]);
            if (filter == null) {
                msg(sender, "Invalid pack UUID: " + args[3]);
                return;
            }
        }
        Optional<PackAttempt> captured = sessions.latestReplayable(player.getUniqueId(), filter);
        if (captured.isEmpty()) {
            msg(sender, "No captured primary pack with a full SHA-1 for " + player.getName() + ".");
            msg(sender, "Join with RPEngine active first, or use /pf poc send <player> <sha1>.");
            return;
        }
        PackAttempt original = captured.get();
        Optional<String> sha1 = Sha1Utils.normalize(original.sha1());
        if (sha1.isEmpty()) {
            msg(sender, "Captured pack has no complete SHA-1; refusing to replay.");
            return;
        }
        if (!artifacts.contains(sha1.get())) {
            msg(sender, "No verified artifact for sha1=" + sha1.get() + ".");
            msg(sender, "Run /pf index first (or /pf artifact <path-to-identical.zip>).");
            return;
        }
        Optional<String> badUrl = UrlPolicy.resolveTemplate(config.get().poc().badUrlTemplate(), sha1.get());
        if (badUrl.isEmpty()) {
            msg(sender, "poc.bad-url-template is missing or invalid in config.yml.");
            return;
        }

        PackAttempt poc = sessions.registerPocSimulated(original, badUrl.get());
        outgoing.expect(player.getUniqueId(), original.packId(), badUrl.get(), SendKind.POC_SIMULATED);
        playSender.send(
                player.getUniqueId(),
                original.packId(),
                badUrl.get(),
                sha1.get(),
                original.required(),
                original.prompt(),
                reason -> poc.forceState(PackState.CANCELLED, "poc_send_failed:" + reason)
        );

        msg(sender, "PoC replay sent to " + player.getName() + ":");
        msg(sender, "  same pack UUID=" + original.packId() + " sha1=" + sha1.get()
                + " required=" + original.required());
        msg(sender, "  failing URL=" + UrlPolicy.sanitizeForLog(badUrl.get()));
        msg(sender, "Expect FAILED_DOWNLOAD, then one automatic fallback using the same UUID/SHA-1.");
        if (!httpReady()) {
            msg(sender, "WARNING: HTTP server/public-url is not ready; automatic fallback will be skipped.");
        }
        log.info("poc_simulate_sent player=" + player.getName() + " packId=" + original.packId()
                + " sha1=" + sha1.get() + " badUrl=" + UrlPolicy.sanitizeForLog(badUrl.get()));
    }

    private void pocSend(CommandSender sender, String[] args) {
        if (args.length < 4) {
            msg(sender, "Usage: /pf poc send <player> <sha1> [required]");
            return;
        }
        Player player = Bukkit.getPlayerExact(args[2]);
        if (player == null) {
            msg(sender, "Player is not online: " + args[2]);
            return;
        }
        Optional<String> sha1 = Sha1Utils.normalize(args[3]);
        if (sha1.isEmpty()) {
            msg(sender, "Not a complete 40-hex SHA-1: " + args[3]);
            return;
        }
        if (!artifacts.contains(sha1.get())) {
            msg(sender, "No verified artifact for sha1=" + sha1.get() + ". Run /pf index or /pf artifact <zip> first.");
            return;
        }
        boolean required = args.length >= 5 && Boolean.parseBoolean(args[4]);
        Optional<String> badUrl = UrlPolicy.resolveTemplate(config.get().poc().badUrlTemplate(), sha1.get());
        if (badUrl.isEmpty()) {
            msg(sender, "poc.bad-url-template is missing or invalid in config.yml.");
            return;
        }
        UUID packId = UUID.nameUUIDFromBytes(("packfailover-poc:" + sha1.get()).getBytes(StandardCharsets.UTF_8));
        PackAttempt poc = sessions.registerSyntheticPoc(
                player.getUniqueId(), packId, sha1.get(), badUrl.get(), required, "PackFailover PoC");
        outgoing.expect(player.getUniqueId(), packId, badUrl.get(), SendKind.POC_SIMULATED);
        playSender.send(
                player.getUniqueId(),
                packId,
                badUrl.get(),
                sha1.get(),
                required,
                "PackFailover PoC",
                reason -> poc.forceState(PackState.CANCELLED, "poc_send_failed:" + reason)
        );
        msg(sender, "Synthetic PoC pack sent to " + player.getName()
                + " packId=" + packId + " sha1=" + sha1.get() + " required=" + required);
        msg(sender, "Failing URL=" + UrlPolicy.sanitizeForLog(badUrl.get()));
        if (!httpReady()) {
            msg(sender, "WARNING: HTTP server/public-url is not ready; automatic fallback will be skipped.");
        }
    }

    private void pocResend(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg(sender, "Usage: /pf poc resend <player> [pack-uuid]");
            return;
        }
        Player player = Bukkit.getPlayerExact(args[2]);
        if (player == null) {
            msg(sender, "Player is not online: " + args[2]);
            return;
        }
        if (!httpReady()) {
            msg(sender, "HTTP server/public-url is not ready; cannot resend.");
            return;
        }
        UUID packId;
        if (args.length >= 4) {
            packId = parseUuid(args[3]);
            if (packId == null) {
                msg(sender, "Invalid pack UUID: " + args[3]);
                return;
            }
        } else {
            Optional<PackAttempt> latest = sessions.latestReplayable(player.getUniqueId(), null);
            if (latest.isEmpty()) {
                msg(sender, "No captured pack available for " + player.getName() + ".");
                return;
            }
            packId = latest.get().packId();
        }
        if (coordinator.manualResend(player.getUniqueId(), packId)) {
            msg(sender, "Manual fallback push requested for packId=" + packId + ". Watch console for status transitions.");
        } else {
            msg(sender, "Manual resend unavailable (missing verified SHA-1, allowlist, cache, or public URL). See console.");
        }
    }

    private void pocAbort(CommandSender sender, String[] args) {
        if (args.length < 3) {
            msg(sender, "Usage: /pf poc abort <player>");
            return;
        }
        Player player = Bukkit.getPlayerExact(args[2]);
        if (player == null) {
            msg(sender, "Player is not online: " + args[2]);
            return;
        }
        sessions.abortPlayer(player.getUniqueId());
        msg(sender, "Cleared PoC tracking for " + player.getName() + ".");
    }

    private boolean httpReady() {
        return httpServer != null && httpServer.isReady() && config.get().http().hasPublicUrl();
    }

    private void help(CommandSender sender) {
        msg(sender, "PackFailover commands:");
        msg(sender, "  /pf status                     - configuration, HTTP, index and counters");
        msg(sender, "  /pf debug <player>             - tracked attempts and history");
        msg(sender, "  /pf index                      - reindex provider ZIPs asynchronously");
        msg(sender, "  /pf cache                      - list immutable cached ZIPs");
        msg(sender, "  /pf reload                     - reload config.yml (restart for HTTP/providers)");
        msg(sender, "  /pf artifact <zip>             - verify + cache a local ZIP");
        msg(sender, "  /pf artifact list              - list verified SHA-1s");
        if (config.get().poc().enabled()) {
            msg(sender, "  /pf poc simulate <player>      - replay captured pack with failing URL");
        msg(sender, "  /pf poc send <player> <sha1>   - synthetic same-UUID PoC pack");
        msg(sender, "  /pf poc resend <player>        - explicitly push the fallback URL");
        msg(sender, "  /pf poc abort <player>         - clear PoC state");
        }
    }

    private void pocHelp(CommandSender sender) {
        msg(sender, "Usage: /pf poc <simulate|send|resend|abort> ...");
        msg(sender, "  simulate <player> [pack-uuid]");
        msg(sender, "  send <player> <sha1> [required]");
        msg(sender, "  resend <player> [pack-uuid]");
        msg(sender, "  abort <player>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("packfailover.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(config.get().poc().enabled() ? ROOT : ROOT.stream().filter(s -> !s.equals("poc")).toList(), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("poc")) {
            if (!config.get().poc().enabled()) return List.of();
            if (args.length == 2) {
                return filter(POC, args[1]);
            }
            if (args.length == 3) {
                return playerNames(args[2]);
            }
            if (args.length == 4 && (args[1].equalsIgnoreCase("simulate")
                    || args[1].equalsIgnoreCase("resend"))) {
                return List.of();
            }
        }
        if (sub.equals("debug") && args.length == 2) {
            return playerNames(args[1]);
        }
        if (sub.equals("artifact") && args.length == 2) {
            return filter(List.of("list"), args[1]);
        }
        return List.of();
    }

    private void runMain(Runnable runnable) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, runnable);
        } else {
            runnable.run();
        }
    }

    private static List<String> playerNames(String prefix) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                names.add(player.getName());
            }
        }
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void msg(CommandSender sender, String text) {
        sender.sendMessage(Component.text("[PackFailover] " + text));
    }
}
