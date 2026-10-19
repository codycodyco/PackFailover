package dev.cody.packfailover.index;

import dev.cody.packfailover.artifact.ArtifactVerifier;
import dev.cody.packfailover.artifact.VerifiedArtifact;
import dev.cody.packfailover.artifact.VerifiedArtifactRegistry;
import dev.cody.packfailover.cache.ImmutablePackCache;
import dev.cody.packfailover.provider.PackProvider;
import dev.cody.packfailover.util.PfLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scans providers, verifies ZIPs by full SHA-1, stores immutable cache copies and
 * atomically swaps the registry snapshot.
 *
 * <p>All filesystem/hash work runs on a dedicated single-thread executor. A
 * size+mtime fingerprint avoids re-hashing unchanged files on periodic reindex.</p>
 */
public final class ArtifactIndexer implements AutoCloseable {

    private record Fingerprint(long size, FileTime modified, String sha1) {
    }

    public record IndexResult(
            int providers,
            int candidates,
            int indexed,
            int reused,
            int rejected,
            int errors,
            long durationMs,
            String message
    ) {
        public static IndexResult busy() {
            return new IndexResult(0, 0, 0, 0, 0, 0, 0, "already running");
        }
    }

    private final List<PackProvider> providers;
    private final ArtifactVerifier verifier;
    private final ImmutablePackCache cache;
    private final VerifiedArtifactRegistry registry;
    private final PfLogger log;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ConcurrentHashMap<Path, Fingerprint> fingerprints = new ConcurrentHashMap<>();
    private volatile IndexResult lastResult;

    public ArtifactIndexer(
            List<PackProvider> providers,
            ArtifactVerifier verifier,
            ImmutablePackCache cache,
            VerifiedArtifactRegistry registry,
            PfLogger log
    ) {
        this.providers = List.copyOf(providers);
        this.verifier = verifier;
        this.cache = cache;
        this.registry = registry;
        this.log = log;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PackFailover-Indexer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<IndexResult> reindexAsync() {
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(IndexResult.busy());
        }
        return CompletableFuture.supplyAsync(this::reindex, executor)
                .whenComplete((result, throwable) -> running.set(false));
    }

    /**
     * Synchronous indexing, for tests and the indexer executor only.
     */
    public IndexResult reindex() {
        long start = System.nanoTime();
        cache.refresh();
        Map<String, VerifiedArtifact> found = new HashMap<>();
        Map<Path, Fingerprint> nextFingerprints = new HashMap<>();
        int candidates = 0;
        int indexed = 0;
        int reused = 0;
        int rejected = 0;
        int errors = 0;
        int providerErrors = 0;

        for (PackProvider provider : providers) {
            List<Path> paths;
            try {
                paths = List.copyOf(provider.discoverCandidates());
            } catch (IOException | RuntimeException e) {
                providerErrors++;
                errors++;
                log.warn("provider_scan_failed id=" + provider.id() + " error=" + e.getMessage());
                continue;
            }
            log.debug("provider_scanned id=" + provider.id() + " candidates=" + paths.size());
            for (Path path : paths) {
                candidates++;
                try {
                    Path real = path.toRealPath();
                    if (!Files.isRegularFile(real)) {
                        rejected++;
                        continue;
                    }
                    long size = Files.size(real);
                    FileTime modified = Files.getLastModifiedTime(real);
                    Fingerprint previous = fingerprints.get(real);
                    if (previous != null
                            && previous.size() == size
                            && previous.modified().equals(modified)
                            && registry.contains(previous.sha1())
                            && cache.has(previous.sha1())) {
                        registry.get(previous.sha1()).ifPresent(artifact -> found.put(artifact.sha1(), artifact));
                        nextFingerprints.put(real, previous);
                        reused++;
                        continue;
                    }

                    VerifiedArtifact verified = verifier.verify(real);
                    Path cached = cache.store(verified);
                    VerifiedArtifact stored = new VerifiedArtifact(
                            verified.sha1(), cached, Files.size(cached), verified.verifiedAt());
                    found.put(stored.sha1(), stored);
                    nextFingerprints.put(real, new Fingerprint(size, modified, stored.sha1()));
                    indexed++;
                } catch (Exception e) {
                    rejected++;
                    log.debug("candidate_rejected file=" + path + " reason=" + e.getMessage());
                }
            }
        }

        // Keep previously cached versions servable: a player may still be
        // retrying an older hash while RPEngine has already published a new one.
        for (var entry : cache.entries().entrySet()) {
            if (found.containsKey(entry.getKey())) {
                continue;
            }
            try {
                Path cached = entry.getValue();
                found.put(entry.getKey(), new VerifiedArtifact(
                        entry.getKey(), cached, Files.size(cached),
                        Files.getLastModifiedTime(cached).toInstant()));
            } catch (IOException e) {
                log.debug("cache_entry_unreadable sha1=" + entry.getKey() + " reason=" + e.getMessage());
            }
        }

        if (found.isEmpty() && providerErrors > 0) {
            log.warn("index_scan_failed_preserving_previous providers=" + providers.size()
                    + " providerErrors=" + providerErrors);
        } else {
            registry.replaceAll(found.values());
            fingerprints.clear();
            fingerprints.putAll(nextFingerprints);
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        IndexResult result = new IndexResult(
                providers.size(),
                candidates,
                indexed,
                reused,
                rejected,
                errors,
                durationMs,
                found.isEmpty() ? "no verified artifacts" : "ok"
        );
        lastResult = result;
        log.info("index_done providers=" + result.providers()
                + " candidates=" + result.candidates()
                + " indexed=" + result.indexed()
                + " reused=" + result.reused()
                + " rejected=" + result.rejected()
                + " errors=" + result.errors()
                + " indexedSha1=" + registry.size()
                + " durationMs=" + result.durationMs());
        return result;
    }

    public IndexResult lastResult() {
        return lastResult;
    }

    public boolean isRunning() {
        return running.get();
    }

    public List<PackProvider> providers() {
        return providers;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
