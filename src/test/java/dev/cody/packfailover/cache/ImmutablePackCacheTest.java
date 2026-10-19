package dev.cody.packfailover.cache;

import dev.cody.packfailover.artifact.VerifiedArtifact;
import dev.cody.packfailover.util.PfLogger;
import dev.cody.packfailover.util.Sha1Utils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmutablePackCacheTest {

    @TempDir
    Path tempDir;

    private PfLogger log() {
        Logger logger = Logger.getLogger("ImmutablePackCacheTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        return new PfLogger(logger, () -> false);
    }

    private VerifiedArtifact artifact(Path source) throws IOException {
        String sha1 = Sha1Utils.sha1Hex(source);
        return new VerifiedArtifact(sha1, source, Files.size(source), Instant.now());
    }

    @Test
    void storesContentAddressedFileAndReusesIt() throws Exception {
        Path source = tempDir.resolve("source.zip");
        Files.writeString(source, "pack-bytes", StandardCharsets.UTF_8);
        ImmutablePackCache cache = new ImmutablePackCache(tempDir.resolve("cache"), log());

        VerifiedArtifact artifact = artifact(source);
        Path first = cache.store(artifact);
        Path second = cache.store(artifact);

        assertEquals(first, second);
        assertEquals(artifact.sha1() + ".zip", first.getFileName().toString());
        assertEquals(artifact.sha1(), Sha1Utils.sha1Hex(first));
        assertTrue(cache.has(artifact.sha1()));
        assertEquals(1, cache.size());
        assertEquals(1, cache.files().size());
    }

    @Test
    void rejectsMismatchedSha1WithoutLeavingTarget() throws Exception {
        Path source = tempDir.resolve("source.zip");
        Files.writeString(source, "pack-bytes", StandardCharsets.UTF_8);
        ImmutablePackCache cache = new ImmutablePackCache(tempDir.resolve("cache"), log());

        VerifiedArtifact wrong = new VerifiedArtifact(
                "f".repeat(40), source, Files.size(source), Instant.now());

        assertThrows(IOException.class, () -> cache.store(wrong));
        assertFalse(cache.has(wrong.sha1()));
        assertEquals(0, cache.size());
    }

    @Test
    void ignoresInvalidSha1Lookups() throws Exception {
        ImmutablePackCache cache = new ImmutablePackCache(tempDir.resolve("cache"), log());
        assertTrue(cache.pathFor("deadbeef").isEmpty());
        assertTrue(cache.pathFor(null).isEmpty());
    }
}
