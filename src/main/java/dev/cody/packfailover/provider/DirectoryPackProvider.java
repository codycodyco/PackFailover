package dev.cody.packfailover.provider;

import dev.cody.packfailover.util.PfLogger;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Bounded, read-only directory scanner.
 *
 * <p>Only regular files below the configured root are considered; symbolic links
 * are not followed and the normalized path must stay under the real root.</p>
 */
public class DirectoryPackProvider implements PackProvider {

    private final String id;
    private final Path root;
    private final String glob;
    private final int maxDepth;
    private final int maxCandidates;
    private final PfLogger log;

    public DirectoryPackProvider(
            String id,
            Path root,
            String glob,
            int maxDepth,
            int maxCandidates,
            PfLogger log
    ) {
        this.id = id;
        this.root = root;
        this.glob = glob;
        this.maxDepth = maxDepth;
        this.maxCandidates = maxCandidates;
        this.log = log;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String description() {
        return id + " (" + root + ", glob=" + glob + ", max-depth=" + maxDepth + ")";
    }

    public Path root() {
        return root;
    }

    @Override
    public List<Path> discoverCandidates() throws IOException {
        if (!Files.isDirectory(root)) {
            log.debug("provider_missing id=" + id + " path=" + root);
            return List.of();
        }
        Path realRoot = root.toRealPath();
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        // Java's glob "**/*.zip" does not match a file at the root itself.
        // RPEngine commonly writes ZIPs directly into its output directory, so
        // also match the pattern with a stripped "**/" prefix.
        PathMatcher rootMatcher = glob.startsWith("**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3))
                : null;
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(realRoot, maxDepth)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.normalize().startsWith(realRoot))
                    .filter(path -> matches(matcher, rootMatcher, realRoot, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(candidates::add);
        } catch (IOException e) {
            throw new IOException("cannot scan " + realRoot + ": " + e.getMessage(), e);
        }

        if (candidates.size() > maxCandidates) {
            log.warn("provider_candidate_limit id=" + id + " found=" + candidates.size()
                    + " limit=" + maxCandidates + " (only the first entries are indexed)");
            return List.copyOf(candidates.subList(0, maxCandidates));
        }
        return List.copyOf(candidates);
    }

    private static boolean matches(PathMatcher matcher, PathMatcher rootMatcher, Path root, Path path) {
        Path relative = root.relativize(path);
        Path fileName = path.getFileName();
        if (matcher.matches(relative) || matcher.matches(fileName)) {
            return true;
        }
        return rootMatcher != null
                && (rootMatcher.matches(relative) || rootMatcher.matches(fileName));
    }

    @Override
    public String toString() {
        return description();
    }
}
