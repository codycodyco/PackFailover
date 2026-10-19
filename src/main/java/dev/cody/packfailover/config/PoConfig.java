package dev.cody.packfailover.config;

import java.util.List;

/**
 * Immutable, validated plugin configuration snapshot.
 */
public record PoConfig(
        boolean enabled,
        Tracking tracking,
        Providers providers,
        Fallback fallback,
        Http http,
        Cache cache,
        Artifacts artifacts,
        Poc poc,
        Logging logging
) {

    public record Tracking(
            boolean trackConfigPhase,
            boolean trackPlayPhase,
            List<String> primaryHostAllowlist,
            int maxTrackedPlayers,
            int historyPerPlayer
    ) {
        public Tracking {
            primaryHostAllowlist = List.copyOf(primaryHostAllowlist);
        }
    }

    public record Providers(
            int reindexSeconds,
            int maxCandidatesPerProvider,
            List<Directory> directories
    ) {
        public Providers {
            directories = List.copyOf(directories);
        }

        public record Directory(
                String id,
                boolean enabled,
                String path,
                String glob,
                int maxDepth
        ) {
        }
    }

    public record Fallback(
            boolean enabled,
            boolean dryRun,
            int maxAttemptsPerPack,
            boolean configPhase,
            boolean allowRequired
    ) {
        public Fallback {
            if (maxAttemptsPerPack < 0 || maxAttemptsPerPack > 1) {
                throw new IllegalArgumentException("maxAttemptsPerPack must be 0 or 1");
            }
        }

        public static int normalizeMaxAttempts(int requested) {
            return Math.max(0, Math.min(1, requested));
        }
    }

    public record Http(
            boolean enabled,
            String bindAddress,
            int port,
            String publicUrl,
            int workerThreads,
            int backlog,
            int cacheControlSeconds
    ) {
        public boolean hasPublicUrl() {
            return publicUrl != null && !publicUrl.isBlank();
        }
    }

    public record Cache(String directory) {
    }

    public record Artifacts(int maxSizeMb) {
    }

    public record Poc(boolean enabled, String badUrlTemplate) {
    }

    public record Logging(boolean debug) {
    }

    public static PoConfig defaults() {
        return new PoConfig(
                true,
                new Tracking(true, true, List.of("packs.resourcepack.ai"), 256, 16),
                new Providers(60, 200, List.of(
                        new Providers.Directory("rpengine", true, "plugins/RPEngine/output", "**/*.zip", 3)
                )),
                new Fallback(true, false, 1, true, true),
                new Http(true, "0.0.0.0", 8181, "", 4, 32, 3600),
                new Cache("plugins/PackFailover/cache"),
                new Artifacts(100),
                new Poc(false, "http://127.0.0.1:1/{sha1}.zip"),
                new Logging(false)
        );
    }

    public String describePublicUrl() {
        return http.hasPublicUrl() ? http.publicUrl() : "(not configured)";
    }
}
