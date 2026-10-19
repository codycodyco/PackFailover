package dev.cody.packfailover.provider;

import dev.cody.packfailover.TestPackFiles;
import dev.cody.packfailover.util.PfLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryPackProviderTest {

    @TempDir
    Path tempDir;

    private Path root;
    private PfLogger log;

    @BeforeEach
    void setUp() throws Exception {
        Logger logger = Logger.getLogger("DirectoryPackProviderTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        log = new PfLogger(logger, () -> false);
        root = tempDir.resolve("packs");
        TestPackFiles.writeValidPack(root.resolve("a.zip"), "a");
        TestPackFiles.writeValidPack(root.resolve("sub/b.zip"), "b");
        TestPackFiles.writeValidPack(root.resolve("sub/deep/c.zip"), "c");
    }

    @Test
    void respectsMaxDepth() throws Exception {
        DirectoryPackProvider provider = new DirectoryPackProvider(
                "test", root, "**/*.zip", 2, 100, log);
        List<Path> candidates = provider.discoverCandidates().stream().map(Path::getFileName).toList();

        assertEquals(2, candidates.size());
        assertTrue(candidates.contains(Path.of("a.zip")));
        assertTrue(candidates.contains(Path.of("b.zip")));
    }

    @Test
    void respectsGlob() throws Exception {
        DirectoryPackProvider provider = new DirectoryPackProvider(
                "test", root, "sub/*.zip", 3, 100, log);
        List<Path> candidates = provider.discoverCandidates().stream().map(Path::getFileName).toList();

        assertEquals(1, candidates.size());
        assertEquals(Path.of("b.zip"), candidates.get(0));
    }

    @Test
    void respectsCandidateLimit() throws Exception {
        DirectoryPackProvider provider = new DirectoryPackProvider(
                "test", root, "**/*.zip", 4, 2, log);

        assertEquals(2, provider.discoverCandidates().size());
    }

    @Test
    void missingRootIsEmptyNotAnError() throws Exception {
        DirectoryPackProvider provider = new DirectoryPackProvider(
                "test", tempDir.resolve("missing"), "**/*.zip", 2, 10, log);

        assertTrue(provider.discoverCandidates().isEmpty());
    }

    @Test
    void doesNotFollowSymlinks() throws Exception {
        Path outside = TestPackFiles.writeValidPack(tempDir.resolve("outside.zip"), "outside");
        Path link = root.resolve("link.zip");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            return; // platform does not allow symlinks in this test environment
        }

        DirectoryPackProvider provider = new DirectoryPackProvider(
                "test", root, "**/*.zip", 4, 100, log);
        List<String> names = provider.discoverCandidates().stream()
                .map(path -> path.getFileName().toString())
                .toList();

        assertTrue(!names.contains("link.zip"));
    }
}
