package dev.cody.packfailover.config;

import dev.cody.packfailover.config.PoConfig.Providers.Directory;
import dev.cody.packfailover.util.UrlPolicy;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.FileSystems;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Loads and validates Stage 1 configuration. Security-relevant invalid values are
 * disabled with a warning instead of being silently accepted.
 */
public final class ConfigManager {

    private static final String SAMPLE_SHA1 = "0123456789abcdef0123456789abcdef01234567";

    private final JavaPlugin plugin;
    private final Logger logger;
    private volatile PoConfig current;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.current = PoConfig.defaults();
    }

    public PoConfig load() {
        FileConfiguration config = plugin.getConfig();

        boolean enabled = config.getBoolean("enabled", true);

        boolean trackConfig = config.getBoolean("tracking.track-config-phase", true);
        boolean trackPlay = config.getBoolean("tracking.track-play-phase", true);
        List<String> allowlist = readAllowlist(config.getStringList("tracking.primary-host-allowlist"));
        int maxPlayers = clamp(config.getInt("tracking.max-tracked-players", 256), 1, 4096);
        int history = clamp(config.getInt("tracking.history-per-player", 16), 1, 100);

        int reindexSeconds = clamp(config.getInt("providers.reindex-seconds", 60), 0, 3600);
        int maxCandidates = clamp(config.getInt("providers.max-candidates-per-provider", 200), 1, 1000);
        List<Directory> directories = readProviders(config);

        boolean fallbackEnabled = config.getBoolean("fallback.enabled", true);
        boolean dryRun = config.getBoolean("fallback.dry-run", false);
        int maxAttempts = clamp(config.getInt("fallback.max-attempts-per-pack", 1), 0, 5);
        boolean configPhase = config.getBoolean("fallback.config-phase",
                config.getBoolean("fallback.experimental-config-phase", true));
        boolean allowRequired = config.getBoolean("fallback.allow-required", true);

        boolean httpEnabled = config.getBoolean("http.enabled", true);
        String bindAddress = readBindAddress(config.getString("http.bind-address", "0.0.0.0"));
        int port = clamp(config.getInt("http.port", 8181), 1, 65535);
        String publicUrl = readPublicUrl(config);
        int workerThreads = clamp(config.getInt("http.worker-threads", 4), 1, 32);
        int backlog = clamp(config.getInt("http.backlog", 32), 1, 1024);
        int cacheControl = clamp(config.getInt("http.cache-control-seconds", 3600), 0, 86400);

        String cacheDirectory = config.getString("cache.directory", "plugins/PackFailover/cache");
        if (cacheDirectory == null || cacheDirectory.isBlank()) {
            cacheDirectory = "plugins/PackFailover/cache";
        }

        int maxSizeMb = clamp(config.getInt("artifact.max-size-mb", 100), 1, 1024);

        String badUrlTemplate = validateTemplate(
                config.getString("poc.bad-url-template", "http://127.0.0.1:1/{sha1}.zip"),
                true, "poc.bad-url-template");
        if (badUrlTemplate.isBlank()) {
            logger.warning("poc.bad-url-template is invalid; /pf poc simulate will be unavailable.");
        }

        boolean debug = config.getBoolean("logging.debug", false);

        PoConfig loaded = new PoConfig(
                enabled,
                new PoConfig.Tracking(trackConfig, trackPlay, allowlist, maxPlayers, history),
                new PoConfig.Providers(reindexSeconds, maxCandidates, directories),
                new PoConfig.Fallback(fallbackEnabled, dryRun, maxAttempts, configPhase, allowRequired),
                new PoConfig.Http(httpEnabled, bindAddress, port, publicUrl, workerThreads, backlog, cacheControl),
                new PoConfig.Cache(cacheDirectory),
                new PoConfig.Artifacts(maxSizeMb),
                new PoConfig.Poc(badUrlTemplate),
                new PoConfig.Logging(debug)
        );
        this.current = loaded;

        if (allowlist.isEmpty()) {
            logger.warning("tracking.primary-host-allowlist is empty; automatic fallback can never trigger.");
        }
        if (directories.isEmpty()) {
            logger.warning("providers.directories is empty; no ZIP can be indexed and fallback will be skipped.");
        }
        if (fallbackEnabled && httpEnabled && publicUrl.isBlank()) {
            logger.warning("fallback.enabled=true but http.public-url is empty; fallback URLs cannot be generated.");
        }
        if (!httpEnabled && fallbackEnabled) {
            logger.warning("fallback.enabled=true but http.enabled=false; fallback will be skipped.");
        }
        if (fallbackEnabled && dryRun) {
            logger.info("fallback is in dry-run mode; decisions are logged but nothing is sent.");
        }
        return loaded;
    }

    public PoConfig current() {
        return current;
    }

    private List<String> readAllowlist(List<String> raw) {
        List<String> result = new ArrayList<>();
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            String host = entry.trim().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) {
                continue;
            }
            if (host.contains("://") || host.contains("/") || host.contains("*") || host.contains(":")) {
                logger.warning("Ignoring allowlist entry that is not a bare hostname: " + entry);
                continue;
            }
            if (!result.contains(host)) {
                result.add(host);
            }
        }
        return List.copyOf(result);
    }

    private List<Directory> readProviders(FileConfiguration config) {
        List<Map<?, ?>> raw = config.getMapList("providers.directories");
        if (raw.isEmpty()) {
            return List.of(new Directory("rpengine", true, "plugins/RPEngine/output", "**/*.zip", 3));
        }
        List<Directory> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int index = 0;
        for (Map<?, ?> map : raw) {
            index++;
            String id = stringValue(map.get("id"), "provider-" + index);
            id = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
            if (id.isBlank()) {
                id = "provider-" + index;
            }
            while (!ids.add(id)) {
                id = id + "-" + index;
            }
            boolean providerEnabled = booleanValue(map.get("enabled"), true);
            String path = stringValue(map.get("path"), "");
            String glob = stringValue(map.get("glob"), "**/*.zip");
            try {
                FileSystems.getDefault().getPathMatcher("glob:" + glob);
            } catch (RuntimeException e) {
                logger.warning("Ignoring provider '" + id + "' with an invalid glob: " + glob);
                continue;
            }
            int maxDepth = clamp(intValue(map.get("max-depth"), 3), 1, 8);
            if (path.isBlank()) {
                logger.warning("Ignoring provider '" + id + "' with an empty path.");
                continue;
            }
            result.add(new Directory(id, providerEnabled, path, glob, maxDepth));
        }
        return List.copyOf(result);
    }

    private String readPublicUrl(FileConfiguration config) {
        String raw = config.getString("http.public-url", "");
        if (raw == null || raw.isBlank()) {
            // Backwards compatibility with the Stage 0 test-server template.
            String legacy = config.getString("fallback.url-template", "");
            if (legacy != null && !legacy.isBlank()) {
                String marker = "/packs/{sha1}.zip";
                int markerIndex = legacy.indexOf(marker);
                if (markerIndex > 0) {
                    raw = legacy.substring(0, markerIndex);
                    logger.warning("fallback.url-template is a Stage 0 setting; using its prefix as http.public-url. "
                            + "Please set http.public-url explicitly.");
                }
            }
        }
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        Optional<URI> parsed = UrlPolicy.parseHttp(value);
        if (parsed.isEmpty()) {
            logger.warning("http.public-url must be a strict http(s) URL; fallback URLs will be unavailable.");
            return "";
        }
        if (UrlPolicy.isLoopbackOrUnspecified(parsed.get())) {
            logger.warning("http.public-url must not point at localhost/loopback; the client downloads it remotely.");
            return "";
        }
        return value;
    }

    private String readBindAddress(String raw) {
        if (raw == null || raw.isBlank()) {
            return "0.0.0.0";
        }
        String value = raw.trim();
        if (!value.matches("[A-Za-z0-9.:\\[\\]]+")) {
            logger.warning("http.bind-address is invalid; using 0.0.0.0.");
            return "0.0.0.0";
        }
        return value;
    }

    private String validateTemplate(String raw, boolean allowLoopback, String key) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String template = raw.trim();
        Optional<String> resolved = UrlPolicy.resolveTemplate(template, SAMPLE_SHA1);
        if (resolved.isEmpty()) {
            logger.warning(key + " must be an http(s) URL containing the literal {sha1} placeholder.");
            return "";
        }
        Optional<URI> uri = UrlPolicy.parseHttp(resolved.get());
        if (uri.isEmpty()) {
            logger.warning(key + " does not resolve to a strict http(s) URL.");
            return "";
        }
        if (!allowLoopback && UrlPolicy.isLoopbackOrUnspecified(uri.get())) {
            logger.warning(key + " must not point at localhost/loopback; the client downloads it remotely.");
            return "";
        }
        if (!resolved.get().contains(SAMPLE_SHA1)) {
            logger.warning(key + " lost the SHA-1 placeholder during resolution.");
            return "";
        }
        return template;
    }

    private static String stringValue(Object value, String fallback) {
        return value instanceof String string && !string.isBlank() ? string.trim() : fallback;
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
