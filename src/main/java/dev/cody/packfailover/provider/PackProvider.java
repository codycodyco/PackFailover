package dev.cody.packfailover.provider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

/**
 * Discovers candidate resource-pack ZIPs. A provider never rebuilds or modifies
 * another plugin's files.
 */
public interface PackProvider {

    String id();

    String description();

    Collection<Path> discoverCandidates() throws IOException;
}
