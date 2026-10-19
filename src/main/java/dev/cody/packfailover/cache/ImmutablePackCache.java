package dev.cody.packfailover.cache;

import dev.cody.packfailover.artifact.VerifiedArtifact;
import dev.cody.packfailover.util.PfLogger;
import dev.cody.packfailover.util.Sha1Utils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Immutable, content-addressed cache: {@code <sha1>.zip}.
 *
 * <p>Files are copied to a temporary file, re-hashed, then atomically renamed.
 * HTTP readers therefore never observe a partially written ZIP. Existing files
 * with the same hash are reused; an atomic replacement keeps readers of the old
 * inode valid on Unix.</p>
 *
 * <p>The directory snapshot is refreshed by the indexer thread, not by HTTP
 * request threads or the server main thread.</p>
 */
public final class ImmutablePackCache {

    private final Path directory;
    private final PfLogger log;
    private volatile Map<String, Path> snapshot = Map.of();

    public ImmutablePackCache(Path directory, PfLogger log) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.log = log;
        Files.createDirectories(this.directory);
    }

    public Path directory() {
        return directory;
    }

    /**
     * Rebuilds the in-memory snapshot. Must run on the indexer/IO thread.
     */
    public void refresh() {
        Map<String, Path> next = new LinkedHashMap<>();
        if (Files.isDirectory(directory)) {
            try (Stream<Path> stream = Files.list(directory)) {
                stream.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(path -> {
                            String name = path.getFileName().toString();
                            if (name.length() != 44 || !name.endsWith(".zip")) {
                                return;
                            }
                            String sha1 = name.substring(0, 40);
                            if (Sha1Utils.normalize(sha1).isPresent()) {
                                next.put(sha1, path);
                            }
                        });
            } catch (IOException e) {
                log.warn("cache_refresh_failed directory=" + directory + " error=" + e.getMessage());
            }
        }
        this.snapshot = Map.copyOf(next);
    }

    public Path store(VerifiedArtifact artifact) throws IOException {
        String sha1 = Sha1Utils.normalize(artifact.sha1())
                .orElseThrow(() -> new IOException("artifact has no complete SHA-1"));
        Path target = directory.resolve(sha1 + ".zip");

        if (Files.isRegularFile(target) && Files.size(target) == artifact.sizeBytes()) {
            refresh();
            return target;
        }

        Path temp = Files.createTempFile(directory, ".pack-", ".tmp");
        try {
            Files.copy(artifact.file(), temp, StandardCopyOption.REPLACE_EXISTING);
            String copiedSha1 = Sha1Utils.sha1Hex(temp);
            if (!copiedSha1.equals(sha1)) {
                throw new IOException("cache copy SHA-1 mismatch: expected " + sha1 + " got " + copiedSha1);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("cache_stored sha1=" + sha1 + " size=" + artifact.sizeBytes() + " file=" + target);
            refresh();
            return target;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public Optional<Path> pathFor(String sha1) {
        Optional<String> normalized = Sha1Utils.normalize(sha1);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.get(normalized.get()));
    }

    public boolean has(String sha1) {
        return pathFor(sha1).isPresent();
    }

    public List<Path> files() {
        return snapshot.values().stream()
                .sorted(Comparator.comparing(Path::toString))
                .toList();
    }

    /**
     * Cache entries keyed by their filename SHA-1. Used to keep old versions
     * servable for players that are still downloading/retrying an older pack.
     */
    public Map<String, Path> entries() {
        return snapshot;
    }

    public int size() {
        return snapshot.size();
    }
}
