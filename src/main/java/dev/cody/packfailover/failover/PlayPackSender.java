package dev.cody.packfailover.failover;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Sends a play-phase resource-pack push through the Paper player API on the
 * server's main thread.
 */
public interface PlayPackSender {

    /**
     * @param onFailure receives a short reason when the push could not be issued
     */
    void send(
            UUID playerId,
            UUID packId,
            String url,
            String sha1,
            boolean required,
            String prompt,
            Consumer<String> onFailure
    );
}
