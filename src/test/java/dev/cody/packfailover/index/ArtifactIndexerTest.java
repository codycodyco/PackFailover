package dev.cody.packfailover.index;

import dev.cody.packfailover.TestPackFiles;
import dev.cody.packfailover.artifact.ArtifactVerifier;
import dev.cody.packfailover.artifact.VerifiedArtifactRegistry;
import dev.cody.packfailover.cache.ImmutablePackCache;
import dev.cody.packfailover.provider.DirectoryPackProvider;
import dev.cody.packfailover.util.PfLogger;
import dev.cody.packfailover.util.Sha1Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactIndexerTest {

    @TempDir
    Path tempDir;

    private ExecutorService verifierExecutor;
    private ArtifactIndexer indexer;
    private VerifiedArtifactRegistry registry;
    private ImmutablePackCache cache;
    private Path providerRoot;

    @BeforeEach
    void setUp() throws Exception {
        Logger logger = Logger.getLogger("ArtifactIndexerTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        PfLogger log = new PfLogger(logger, () -> false);

        providerRoot = tempDir.resolve("RPEngine/output");
        Files.createDirectories(providerRoot);
        TestPackFiles.writeValidPack(providerRoot.resolve("pack-a.zip"), "pack-a");
        TestPackFiles.writeValidPack(providerRoot.resolve("nested/pack-b.zip"), "pack-b");
        TestPackFiles.writePlainFile(providerRoot.resolve("broken.zip"), "not a zip");
        TestPackFiles.writeZipWithoutMcmeta(providerRoot.resolve("no-meta.zip"));

        registry = new VerifiedArtifactRegistry();
        cache = new ImmutablePackCache(tempDir.resolve("cache"), log);
        verifierExecutor = Executors.newSingleThreadExecutor();
        ArtifactVerifier verifier = new ArtifactVerifier(verifierExecutor, () -> 10, log);
        DirectoryPackProvider provider = new DirectoryPackProvider(
                "rpengine", providerRoot, "**/*.zip", 4, 100, log);
        indexer = new ArtifactIndexer(List.of(provider), verifier, cache, registry, log);
    }

    @AfterEach
    void tearDown() {
        if (indexer != null) {
            indexer.close();
        }
        if (verifierExecutor != null) {
            verifierExecutor.shutdownNow();
        }
    }

    @Test
    void indexesValidPacksAndRejectsInvalidOnes() {
        ArtifactIndexer.IndexResult result = indexer.reindex();

        assertEquals(4, result.candidates());
        assertEquals(2, result.indexed());
        assertEquals(2, result.rejected());
        assertEquals(2, registry.size());
        assertEquals(2, cache.size());
        assertEquals(2, result.indexed());
    }

    @Test
    void secondReindexReusesUnchangedFiles() {
        indexer.reindex();
        ArtifactIndexer.IndexResult second = indexer.reindex();

        assertEquals(2, second.reused());
        assertEquals(0, second.indexed());
        assertEquals(2, second.rejected());
        assertEquals(2, registry.size());
        assertEquals(2, cache.size());
    }

    @Test
    void changedFileIsReindexedAndOldCacheIsRetained() throws Exception {
        Path packA = providerRoot.resolve("pack-a.zip");
        String oldSha1 = Sha1Utils.sha1Hex(packA);
        indexer.reindex();

        TestPackFiles.writeValidPack(packA, "pack-a-v2");
        ArtifactIndexer.IndexResult result = indexer.reindex();

        String newSha1 = Sha1Utils.sha1Hex(packA);
        assertEquals(1, result.indexed());
        assertEquals(1, result.reused());
        assertEquals(3, registry.size());
        assertTrue(registry.contains(newSha1));
        assertTrue(registry.contains(oldSha1));
        assertTrue(!newSha1.equals(oldSha1));
        // The old immutable cache file is retained for in-flight clients.
        assertEquals(3, cache.size());
        assertTrue(cache.has(oldSha1));
        assertTrue(cache.has(newSha1));
    }

    @Test
    void missingProviderDoesNotFail() {
        Logger logger = Logger.getLogger("ArtifactIndexerTest-missing-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        PfLogger log = new PfLogger(logger, () -> false);
        ArtifactVerifier verifier = new ArtifactVerifier(verifierExecutor, () -> 10, log);
        DirectoryPackProvider missing = new DirectoryPackProvider(
                "missing", tempDir.resolve("does-not-exist"), "**/*.zip", 2, 10, log);
        ArtifactIndexer local = new ArtifactIndexer(
                List.of(missing), verifier, cache, new VerifiedArtifactRegistry(), log);
        try {
            ArtifactIndexer.IndexResult result = local.reindex();
            assertEquals(0, result.candidates());
            assertEquals(0, result.errors());
            assertEquals("no verified artifacts", result.message());
        } finally {
            local.close();
        }
    }
}
