package dev.cody.packfailover.tracking;

import dev.cody.packfailover.util.UrlPolicy;

import java.time.Instant;
import java.util.UUID;

/**
 * One correlated resource-pack attempt.
 *
 * <p>State mutation is synchronized because PacketEvents callbacks (configuration
 * phase) and the Paper main thread (play phase) can observe the same attempt.</p>
 */
public final class PackAttempt {

    private final UUID playerId;
    private final UUID packId;
    private final long generation;
    private final SendKind kind;
    private final ProtocolPhase phase;
    private final String url;
    private final String sha1;
    private final String sourceHost;
    private final boolean required;
    private final String prompt;
    private final Instant sentAt;

    private volatile long connectionEpoch;
    private volatile PackState state;
    private volatile String detail = "";
    private volatile Instant lastStatusAt;

    public PackAttempt(
            CapturedSend send,
            long connectionEpoch,
            long generation,
            SendKind kind,
            PackState initialState
    ) {
        this.playerId = send.playerId();
        this.packId = send.packId();
        this.generation = generation;
        this.kind = kind;
        this.phase = send.phase();
        this.url = send.url();
        this.sha1 = send.sha1();
        this.sourceHost = send.sourceHost();
        this.required = send.required();
        this.prompt = send.prompt();
        this.sentAt = send.sentAt();
        this.connectionEpoch = connectionEpoch;
        this.state = initialState;
    }

    public static PackAttempt primary(CapturedSend send, long connectionEpoch, long generation) {
        return new PackAttempt(send, connectionEpoch, generation, SendKind.PRIMARY, PackState.PRIMARY_PENDING);
    }

    public static PackAttempt pocSimulated(CapturedSend send, long connectionEpoch, long generation) {
        return new PackAttempt(send, connectionEpoch, generation, SendKind.POC_SIMULATED, PackState.PRIMARY_PENDING);
    }

    public static PackAttempt fallback(CapturedSend send, long connectionEpoch, long generation) {
        return new PackAttempt(send, connectionEpoch, generation, SendKind.FALLBACK, PackState.FALLBACK_PENDING);
    }

    public UUID playerId() {
        return playerId;
    }

    public UUID packId() {
        return packId;
    }

    public long generation() {
        return generation;
    }

    public SendKind kind() {
        return kind;
    }

    public ProtocolPhase phase() {
        return phase;
    }

    public String url() {
        return url;
    }

    public String sha1() {
        return sha1;
    }

    public String sourceHost() {
        return sourceHost;
    }

    public boolean required() {
        return required;
    }

    public String prompt() {
        return prompt;
    }

    public Instant sentAt() {
        return sentAt;
    }

    public long connectionEpoch() {
        return connectionEpoch;
    }

    public PackState state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    public Instant lastStatusAt() {
        return lastStatusAt;
    }

    public boolean hasCompleteSha1() {
        return sha1.length() == 40;
    }

    public boolean isPrimaryLike() {
        return kind == SendKind.PRIMARY || kind == SendKind.POC_SIMULATED;
    }

    public void adoptEpoch(long newEpoch) {
        this.connectionEpoch = newEpoch;
    }

    /**
     * Applies a status-derived state transition. Terminal states are sticky.
     *
     * @return true when the transition was applied
     */
    public synchronized boolean transition(PackState next, String reason) {
        if (state.isTerminal()) {
            return false;
        }
        this.state = next;
        this.detail = reason == null ? "" : reason;
        this.lastStatusAt = Instant.now();
        return true;
    }

    /**
     * Infrastructure transition (supersede/cancel), allowed even from a terminal
     * state because it describes the attempt lifecycle rather than client status.
     */
    public synchronized void forceState(PackState next, String reason) {
        this.state = next;
        this.detail = reason == null ? "" : reason;
        this.lastStatusAt = Instant.now();
    }

    public String describe() {
        return "gen=" + generation
                + " kind=" + kind
                + " phase=" + phase
                + " state=" + state
                + (detail.isEmpty() ? "" : "(" + detail + ")")
                + " packId=" + packId
                + " source=" + (sourceHost.isEmpty() ? "unknown" : sourceHost)
                + " sha1=" + (sha1.isEmpty() ? "unknown" : sha1)
                + " required=" + required
                + " url=" + UrlPolicy.sanitizeForLog(url)
                + " sentAt=" + sentAt
                + (lastStatusAt == null ? "" : " lastStatusAt=" + lastStatusAt);
    }
}
