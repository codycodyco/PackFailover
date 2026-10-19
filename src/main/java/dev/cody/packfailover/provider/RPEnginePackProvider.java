package dev.cody.packfailover.provider;

import dev.cody.packfailover.util.PfLogger;

import java.nio.file.Path;

/**
 * Thin provider for RPEngine's output directory.
 *
 * <p>The concrete path is deliberately configuration-driven. Stage 0/1 must not
 * assume that every RPEngine version writes the same directory layout.</p>
 */
public final class RPEnginePackProvider extends DirectoryPackProvider {

    public static final String DEFAULT_PATH = "plugins/RPEngine/output";

    public RPEnginePackProvider(Path root, String glob, int maxDepth, int maxCandidates, PfLogger log) {
        super("rpengine", root, glob, maxDepth, maxCandidates, log);
    }
}
