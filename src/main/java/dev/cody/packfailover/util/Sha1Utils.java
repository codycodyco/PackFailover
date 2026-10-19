package dev.cody.packfailover.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Strict SHA-1 helpers. Only a complete 40-hex digest is ever considered usable:
 * truncated 8-character hashes are rejected instead of being guessed or expanded.
 */
public final class Sha1Utils {

    public static final int SHA1_HEX_LENGTH = 40;
    public static final int SHA1_BYTES = 20;

    private static final Pattern FULL_HEX = Pattern.compile("^[0-9a-fA-F]{40}$");
    private static final HexFormat HEX = HexFormat.of();

    private Sha1Utils() {
    }

    /**
     * Normalises a complete SHA-1 hex digest to lowercase.
     *
     * @return the lowercase digest, or empty when the input is null, blank,
     *         truncated, or not hexadecimal
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (!FULL_HEX.matcher(trimmed).matches()) {
            return Optional.empty();
        }
        return Optional.of(trimmed.toLowerCase(Locale.ROOT));
    }

    /**
     * Decodes a full 40-hex digest into the 20-byte form expected by Paper's
     * {@code Player#setResourcePack}/{@code addResourcePack} APIs.
     */
    public static byte[] toBytes(String sha1Hex) {
        String normalized = normalize(sha1Hex)
                .orElseThrow(() -> new IllegalArgumentException("not a complete SHA-1 hex digest"));
        return HEX.parseHex(normalized);
    }

    public static String toHex(byte[] digest) {
        return HEX.formatHex(digest);
    }

    /**
     * Streams a file through SHA-1. Callers must run this off the server thread.
     */
    public static String sha1Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by the resource-pack protocol", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }
}
