package dev.cody.packfailover.artifact;

import dev.cody.packfailover.util.PfLogger;
import dev.cody.packfailover.util.Sha1Utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.IntSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Hashes and structurally validates candidate ZIPs on a bounded I/O executor.
 * Never called from the server main thread.
 */
public final class ArtifactVerifier {

    private final ExecutorService ioExecutor;
    private final IntSupplier maxSizeMb;
    private final PfLogger log;

    public ArtifactVerifier(ExecutorService ioExecutor, IntSupplier maxSizeMb, PfLogger log) {
        this.ioExecutor = ioExecutor;
        this.maxSizeMb = maxSizeMb;
        this.log = log;
    }

    public CompletableFuture<VerifiedArtifact> verifyAsync(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return verify(absolute);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, ioExecutor);
    }

    /**
     * Synchronous verification, intended for tests and the I/O executor only.
     */
    public VerifiedArtifact verify(Path file) throws IOException {
        Path real;
        try {
            real = file.toRealPath();
        } catch (IOException e) {
            throw new IOException("cannot resolve file: " + file + " (" + e.getMessage() + ")", e);
        }
        if (!Files.isRegularFile(real)) {
            throw new IOException("not a regular file: " + real);
        }
        long size = Files.size(real);
        long maxBytes = (long) maxSizeMb.getAsInt() * 1024L * 1024L;
        if (size <= 0) {
            throw new IOException("empty file: " + real);
        }
        if (size > maxBytes) {
            throw new IOException("file is " + size + " bytes, over artifact.max-size-mb=" + maxSizeMb.getAsInt());
        }

        String sha1 = Sha1Utils.sha1Hex(real);

        try (ZipFile zip = new ZipFile(real.toFile())) {
            ZipEntry meta = zip.getEntry("pack.mcmeta");
            if (meta == null) {
                throw new IOException("ZIP has no pack.mcmeta at its root; not a usable resource pack");
            }
        } catch (ZipException e) {
            throw new IOException("not a valid ZIP archive: " + e.getMessage(), e);
        }

        log.info("artifact_verified sha1=" + sha1 + " size=" + size + " file=" + real);
        return new VerifiedArtifact(sha1, real, size, java.time.Instant.now());
    }

    /**
     * Best-effort human-readable failure reason.
     */
    public static String failureMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor instanceof CompletionException && cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        Optional<String> message = Optional.ofNullable(cursor.getMessage());
        return message.filter(s -> !s.isBlank()).orElse(cursor.getClass().getSimpleName());
    }
}
