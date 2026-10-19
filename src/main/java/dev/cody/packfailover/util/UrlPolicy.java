package dev.cody.packfailover.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * Strict URL handling for resource-pack pushes and the configured fallback endpoint.
 *
 * <p>Allowlisting matches the exact URL host, never a substring: a source such as
 * {@code packs.resourcepack.ai.evil.example} must not satisfy an allowlist entry of
 * {@code packs.resourcepack.ai}.</p>
 */
public final class UrlPolicy {

    public static final String SHA1_PLACEHOLDER = "{sha1}";

    private UrlPolicy() {
    }

    /**
     * Parses a strict HTTP(S) resource-pack URL.
     *
     * <p>Embedded credentials are rejected; fragments and query strings are accepted
     * because some hosting providers use them for signed downloads.</p>
     */
    public static Optional<URI> parseHttp(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null) {
                return Optional.empty();
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return Optional.empty();
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return Optional.empty();
            }
            if (uri.getUserInfo() != null) {
                return Optional.empty();
            }
            if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) {
                return Optional.empty();
            }
            // URI#getHost returns null for many malformed authorities, which is
            // already rejected above; this also catches whitespace and control chars.
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c <= 0x20 || c >= 0x7f) {
                    return Optional.empty();
                }
            }
            return Optional.of(uri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns the lowercase host of a valid HTTP(S) URL.
     */
    public static Optional<String> host(String raw) {
        return parseHttp(raw).map(uri -> uri.getHost().toLowerCase(Locale.ROOT));
    }

    /**
     * Exact, case-insensitive host matching against the configured allowlist.
     * An empty allowlist matches nothing.
     */
    public static boolean isAllowlisted(String rawUrl, Collection<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) {
            return false;
        }
        return host(rawUrl)
                .map(host -> allowlist.stream().anyMatch(allowed -> allowed.equalsIgnoreCase(host)))
                .orElse(false);
    }

    /**
     * True for loopback/unspecified destinations, which must never be handed to a
     * remote Minecraft client as a public fallback URL.
     */
    public static boolean isLoopbackOrUnspecified(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return true;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]")) {
            lower = lower.substring(1, lower.length() - 1);
        }
        if (lower.equals("localhost") || lower.equals("0.0.0.0")
                || lower.equals("::") || lower.equals("::1")) {
            return true;
        }
        // Pure string parsing: never resolve a hostname on the server thread.
        String[] parts = lower.split("\\.", -1);
        if (parts.length == 4) {
            boolean allZero = true;
            boolean valid = true;
            for (int i = 0; i < parts.length; i++) {
                int octet = parseOctet(parts[i]);
                if (octet < 0) {
                    valid = false;
                    break;
                }
                if (i == 0 && octet == 127) {
                    return true;
                }
                if (octet != 0) {
                    allZero = false;
                }
            }
            return valid && allZero;
        }
        return false;
    }

    private static int parseOctet(String value) {
        if (value.isEmpty() || value.length() > 3) {
            return -1;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
        return parsed >= 0 && parsed <= 255 ? parsed : -1;
    }

    /**
     * Resolves a template such as {@code http://host:8181/packs/{sha1}.zip}.
     * The template must contain the placeholder and the result must be a strict
     * HTTP(S) URL that still contains the full digest.
     */
    public static Optional<String> resolveTemplate(String template, String sha1Hex) {
        Optional<String> normalized = Sha1Utils.normalize(sha1Hex);
        if (template == null || template.isBlank() || normalized.isEmpty()
                || !template.contains(SHA1_PLACEHOLDER)) {
            return Optional.empty();
        }
        String resolved = template.replace(SHA1_PLACEHOLDER, normalized.get()).trim();
        if (!resolved.contains(normalized.get()) || parseHttp(resolved).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    /**
     * Removes query strings, fragments and user-info before logging. Resource-pack
     * URLs may contain signed query parameters that should not be written to logs.
     */
    public static String sanitizeForLog(String raw) {
        Optional<URI> parsed = parseHttp(raw);
        if (parsed.isEmpty()) {
            return "<invalid-url>";
        }
        URI uri = parsed.get();
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme().toLowerCase(Locale.ROOT)).append("://").append(uri.getHost());
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        String path = uri.getRawPath();
        sb.append(path == null || path.isEmpty() ? "/" : path);
        if (uri.getQuery() != null) {
            sb.append("?<redacted>");
        }
        return sb.toString();
    }
}
