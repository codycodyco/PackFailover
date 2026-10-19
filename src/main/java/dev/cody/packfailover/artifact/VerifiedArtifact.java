package dev.cody.packfailover.artifact;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A binary artifact whose complete SHA-1 has been verified by the plugin.
 *
 * @param sha1       lowercase 40-hex SHA-1
 * @param file       real (symlink-resolved) path of the verified ZIP
 * @param sizeBytes  file size at verification time
 * @param verifiedAt verification timestamp
 */
public record VerifiedArtifact(String sha1, Path file, long sizeBytes, Instant verifiedAt) {

    public VerifiedArtifact {
        if (sha1 == null || sha1.length() != 40) {
            throw new IllegalArgumentException("verified artifact requires a full SHA-1");
        }
        if (file == null || sizeBytes <= 0) {
            throw new IllegalArgumentException("verified artifact requires a readable file");
        }
        verifiedAt = verifiedAt == null ? Instant.now() : verifiedAt;
    }
}
