package dev.cody.packfailover.tracking;

import dev.cody.packfailover.util.UrlPolicy;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable metadata captured from a server-to-client resource-pack push.
 *
 * @param playerId  the player UUID associated with the connection
 * @param packId    the resource-pack UUID carried by the packet
 * @param url       the exact URL string carried by the packet
 * @param sha1      lowercase 40-hex SHA-1, or empty when the packet carried no
 *                  complete digest
 * @param sourceHost lowercase host of the original push, used for allowlisting
 * @param required  the packet's "required" flag
 * @param prompt    plain-text prompt (empty when absent)
 * @param phase     CONFIGURATION or PLAY
 * @param sentAt    capture timestamp
 */
public record CapturedSend(
        UUID playerId,
        UUID packId,
        String url,
        String sha1,
        String sourceHost,
        boolean required,
        String prompt,
        ProtocolPhase phase,
        Instant sentAt
) {

    public CapturedSend {
        if (playerId == null || packId == null) {
            throw new IllegalArgumentException("playerId and packId are required");
        }
        url = url == null ? "" : url;
        sha1 = sha1 == null ? "" : sha1;
        sourceHost = sourceHost == null ? "" : sourceHost;
        prompt = prompt == null ? "" : prompt;
        sentAt = sentAt == null ? Instant.now() : sentAt;
    }

    public boolean hasCompleteSha1() {
        return sha1.length() == 40;
    }

    public String describe() {
        return "player=" + playerId
                + " packId=" + packId
                + " phase=" + phase
                + " source=" + (sourceHost.isEmpty() ? "unknown" : sourceHost)
                + " sha1=" + (sha1.isEmpty() ? "unknown" : sha1)
                + " required=" + required
                + " prompt=" + (prompt.isEmpty() ? "none" : "\"" + prompt + "\"")
                + " url=" + UrlPolicy.sanitizeForLog(url);
    }
}
