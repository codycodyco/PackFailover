package dev.cody.packfailover.artifact;

import dev.cody.packfailover.util.PfLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactVerifierTest {

    @TempDir
    Path tempDir;

    private ExecutorService executor;
    private PfLogger log;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        Logger logger = Logger.getLogger("ArtifactVerifierTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        log = new PfLogger(logger, () -> false);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private Path zip(String name, boolean includeMcmeta) throws IOException {
        Path file = tempDir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("assets/example.txt"));
            out.write("hello".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            if (includeMcmeta) {
                out.putNextEntry(new ZipEntry("pack.mcmeta"));
                out.write("{\"pack\":{\"pack_format\":84,\"description\":\"test\"}}".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    @Test
    void verifiesValidPackAndFullSha1() throws Exception {
        Path file = zip("valid.zip", true);
        ArtifactVerifier verifier = new ArtifactVerifier(executor, () -> 100, log);

        VerifiedArtifact artifact = verifier.verify(file);

        assertEquals(40, artifact.sha1().length());
        assertEquals(Files.size(file), artifact.sizeBytes());
        assertTrue(Files.isRegularFile(artifact.file()));
        assertEquals(artifact.sha1(), dev.cody.packfailover.util.Sha1Utils.sha1Hex(file));
    }

    @Test
    void rejectsNonZip() throws Exception {
        Path file = tempDir.resolve("not-a-zip.zip");
        Files.writeString(file, "definitely not a zip");
        ArtifactVerifier verifier = new ArtifactVerifier(executor, () -> 100, log);

        assertThrows(IOException.class, () -> verifier.verify(file));
    }

    @Test
    void rejectsZipWithoutPackMcmeta() throws Exception {
        Path file = zip("no-meta.zip", false);
        ArtifactVerifier verifier = new ArtifactVerifier(executor, () -> 100, log);

        assertThrows(IOException.class, () -> verifier.verify(file));
    }

    @Test
    void rejectsOversizedFile() throws Exception {
        Path file = zip("big.zip", true);
        ArtifactVerifier verifier = new ArtifactVerifier(executor, () -> 0, log);

        assertThrows(IOException.class, () -> verifier.verify(file));
    }

    @Test
    void rejectsMissingFile() {
        ArtifactVerifier verifier = new ArtifactVerifier(executor, () -> 100, log);
        assertThrows(IOException.class, () -> verifier.verify(tempDir.resolve("missing.zip")));
    }

    @Test
    void asyncVerificationCompletes() throws Exception {
        Path file = zip("async.zip", true);
        ArtifactVerifier verifier = new ArtifactVerifier(executor, () -> 100, log);

        VerifiedArtifact artifact = verifier.verifyAsync(file).get();

        assertEquals(40, artifact.sha1().length());
    }
}
