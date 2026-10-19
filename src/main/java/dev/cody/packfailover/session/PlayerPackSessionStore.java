package dev.cody.packfailover.session;

import dev.cody.packfailover.tracking.CapturedSend;
import dev.cody.packfailover.tracking.PackAttempt;
import dev.cody.packfailover.tracking.PackState;
import dev.cody.packfailover.tracking.ProtocolPhase;
import dev.cody.packfailover.tracking.SendKind;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, thread-safe store of per-player resource-pack attempts.
 *
 * <p>Correlation key: {@code (player UUID, pack UUID)} plus a connection epoch and
 * a per-push generation. The connection epoch is assigned by the login/config
 * lifecycle, not by the player name or URL.</p>
 *
 * <p>Protocol limitation: the client status packet carries only the pack UUID, not
 * a generation. When the same UUID is pushed again, late statuses from the previous
 * generation cannot be distinguished at the protocol layer. The store therefore
 * keeps only the current attempt per pack and preserves older attempts in history
 * for diagnostics.</p>
 */
public final class PlayerPackSessionStore {

    private final int maxPlayers;
    private final int historyPerPlayer;
    private final ConcurrentHashMap<UUID, PlayerSession> players = new ConcurrentHashMap<>();

    public PlayerPackSessionStore(int maxPlayers, int historyPerPlayer) {
        this.maxPlayers = Math.max(1, maxPlayers);
        this.historyPerPlayer = Math.max(1, historyPerPlayer);
    }

    public static final class PlayerSession {
        private final AtomicLong connectionCounter = new AtomicLong();
        private final ConcurrentHashMap<UUID, PackAttempt> current = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, AtomicInteger> fallbackCounts = new ConcurrentHashMap<>();
        private final Deque<PackAttempt> history = new ConcurrentLinkedDeque<>();
        private volatile long lastTouchedNanos = System.nanoTime();
    }

    /**
     * Called on PlayerJoinEvent. Adopts configuration-phase pushes captured for the
     * incoming connection so that play-phase statuses can still be correlated.
     */
    public long beginConnection(UUID playerId) {
        PlayerSession session = session(playerId);
        synchronized (session) {
            long preJoinEpoch = session.connectionCounter.get();
            long newEpoch = session.connectionCounter.incrementAndGet();
            // Retry budgets are per connection: a rejoin starts with a clean budget.
            session.fallbackCounts.clear();
            for (PackAttempt attempt : new ArrayList<>(session.current.values())) {
                if (attempt.connectionEpoch() == preJoinEpoch) {
                    attempt.adoptEpoch(newEpoch);
                } else {
                    // A stale attempt from an older connection with the same UUID.
                    attempt.forceState(PackState.CANCELLED, "connection_replaced");
                    session.current.remove(attempt.packId(), attempt);
                }
            }
            session.lastTouchedNanos = System.nanoTime();
            return newEpoch;
        }
    }

    /**
     * Called on PlayerQuitEvent. Cancels pending attempts, keeps diagnostic history.
     */
    public void endConnection(UUID playerId) {
        PlayerSession session = players.get(playerId);
        if (session == null) {
            return;
        }
        synchronized (session) {
            for (PackAttempt attempt : session.current.values()) {
                attempt.forceState(PackState.CANCELLED, "player_left");
            }
            session.current.clear();
            session.fallbackCounts.clear();
            session.connectionCounter.incrementAndGet();
            session.lastTouchedNanos = System.nanoTime();
        }
    }

    /**
     * Records a server-to-client push captured passively.
     */
    public PackAttempt recordSend(CapturedSend send, SendKind kind) {
        PlayerSession session = session(send.playerId());
        synchronized (session) {
            long epoch = session.connectionCounter.get();
            PackAttempt previous = session.current.get(send.packId());
            long generation = previous == null ? 1 : previous.generation() + 1;
            if (previous != null) {
                previous.forceState(PackState.SUPERSEDED, "new_push_generation=" + generation);
            }
            PackAttempt attempt = kind == SendKind.POC_SIMULATED
                    ? PackAttempt.pocSimulated(send, epoch, generation)
                    : PackAttempt.primary(send, epoch, generation);
            session.current.put(send.packId(), attempt);
            addHistory(session, attempt);
            session.lastTouchedNanos = System.nanoTime();
            return attempt;
        }
    }

    /**
     * Creates the single fallback attempt allowed for a pack.
     */
    public Optional<PackAttempt> beginFallback(PackAttempt parent, String fallbackUrl, int maxAttempts) {
        PlayerSession session = session(parent.playerId());
        synchronized (session) {
            if (session.current.get(parent.packId()) != parent) {
                return Optional.empty();
            }
            if (maxAttempts <= 0) {
                return Optional.empty();
            }
            AtomicInteger counter = session.fallbackCounts
                    .computeIfAbsent(parent.packId(), ignored -> new AtomicInteger());
            if (counter.get() >= maxAttempts) {
                return Optional.empty();
            }
            counter.incrementAndGet();
            CapturedSend fallbackSend = new CapturedSend(
                    parent.playerId(),
                    parent.packId(),
                    fallbackUrl,
                    parent.sha1(),
                    parent.sourceHost(),
                    parent.required(),
                    parent.prompt(),
                    parent.phase(),
                    Instant.now()
            );
            PackAttempt fallback = PackAttempt.fallback(fallbackSend, parent.connectionEpoch(), parent.generation() + 1);
            session.current.put(parent.packId(), fallback);
            addHistory(session, fallback);
            session.lastTouchedNanos = System.nanoTime();
            return Optional.of(fallback);
        }
    }

    /**
     * Creates an admin-triggered fallback attempt even when the current attempt for
     * the pack is already a fallback. Used only by {@code /pf poc resend}.
     */
    public PackAttempt registerManualFallback(PackAttempt template, String fallbackUrl) {
        PlayerSession session = session(template.playerId());
        synchronized (session) {
            long epoch = session.connectionCounter.get();
            PackAttempt previous = session.current.get(template.packId());
            long generation = (previous == null ? template.generation() : previous.generation()) + 1;
            CapturedSend send = new CapturedSend(
                    template.playerId(),
                    template.packId(),
                    fallbackUrl,
                    template.sha1(),
                    template.sourceHost(),
                    template.required(),
                    template.prompt(),
                    ProtocolPhase.PLAY,
                    Instant.now()
            );
            PackAttempt attempt = PackAttempt.fallback(send, epoch, generation);
            session.current.put(template.packId(), attempt);
            addHistory(session, attempt);
            session.lastTouchedNanos = System.nanoTime();
            return attempt;
        }
    }

    /**
     * Re-registers a captured pack as an explicit PoC replay using a failing URL.
     * The original attempt remains in history untouched.
     */
    public PackAttempt registerPocSimulated(PackAttempt original, String badUrl) {
        PlayerSession session = session(original.playerId());
        synchronized (session) {
            long epoch = session.connectionCounter.get();
            PackAttempt previous = session.current.get(original.packId());
            long generation = (previous == null ? original.generation() : previous.generation()) + 1;
            session.fallbackCounts.remove(original.packId());
            CapturedSend replay = new CapturedSend(
                    original.playerId(),
                    original.packId(),
                    badUrl,
                    original.sha1(),
                    original.sourceHost(),
                    original.required(),
                    original.prompt(),
                    ProtocolPhase.PLAY,
                    Instant.now()
            );
            PackAttempt attempt = PackAttempt.pocSimulated(replay, epoch, generation);
            session.current.put(original.packId(), attempt);
            addHistory(session, attempt);
            session.lastTouchedNanos = System.nanoTime();
            return attempt;
        }
    }

    /**
     * Creates a synthetic PoC pack (used when no upstream push has been captured yet).
     */
    public PackAttempt registerSyntheticPoc(
            UUID playerId,
            UUID packId,
            String sha1,
            String badUrl,
            boolean required,
            String prompt
    ) {
        PlayerSession session = session(playerId);
        synchronized (session) {
            long epoch = session.connectionCounter.get();
            PackAttempt previous = session.current.get(packId);
            long generation = previous == null ? 1 : previous.generation() + 1;
            session.fallbackCounts.remove(packId);
            CapturedSend send = new CapturedSend(
                    playerId,
                    packId,
                    badUrl,
                    sha1,
                    "poc.synthetic",
                    required,
                    prompt,
                    ProtocolPhase.PLAY,
                    Instant.now()
            );
            PackAttempt attempt = PackAttempt.pocSimulated(send, epoch, generation);
            session.current.put(packId, attempt);
            addHistory(session, attempt);
            session.lastTouchedNanos = System.nanoTime();
            return attempt;
        }
    }

    public Optional<PackAttempt> current(UUID playerId, UUID packId) {
        PlayerSession session = players.get(playerId);
        if (session == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(session.current.get(packId));
    }

    public boolean isCurrent(PackAttempt attempt) {
        if (attempt == null) {
            return false;
        }
        PlayerSession session = players.get(attempt.playerId());
        if (session == null) {
            return false;
        }
        return session.current.get(attempt.packId()) == attempt
                && attempt.connectionEpoch() == session.connectionCounter.get();
    }

    public int fallbackCount(UUID playerId, UUID packId) {
        PlayerSession session = players.get(playerId);
        if (session == null) {
            return 0;
        }
        AtomicInteger counter = session.fallbackCounts.get(packId);
        return counter == null ? 0 : counter.get();
    }

    public void resetFallbackCount(UUID playerId, UUID packId) {
        PlayerSession session = players.get(playerId);
        if (session != null) {
            session.fallbackCounts.remove(packId);
        }
    }

    /**
     * Latest captured primary-like push for a player, ignoring fallback pushes.
     */
    public Optional<PackAttempt> latestReplayable(UUID playerId, UUID packIdFilter) {
        PlayerSession session = players.get(playerId);
        if (session == null) {
            return Optional.empty();
        }
        for (PackAttempt attempt : session.history) {
            if (!attempt.isPrimaryLike() || !attempt.hasCompleteSha1()) {
                continue;
            }
            if (packIdFilter != null && !attempt.packId().equals(packIdFilter)) {
                continue;
            }
            return Optional.of(attempt);
        }
        return Optional.empty();
    }

    public Optional<PackAttempt> latest(UUID playerId, UUID packId) {
        PlayerSession session = players.get(playerId);
        if (session == null) {
            return Optional.empty();
        }
        for (PackAttempt attempt : session.history) {
            if (attempt.packId().equals(packId)) {
                return Optional.of(attempt);
            }
        }
        return Optional.empty();
    }

    public List<PackAttempt> history(UUID playerId) {
        PlayerSession session = players.get(playerId);
        if (session == null) {
            return List.of();
        }
        return List.copyOf(session.history);
    }

    public List<PackAttempt> currentAttempts(UUID playerId) {
        PlayerSession session = players.get(playerId);
        if (session == null) {
            return List.of();
        }
        List<PackAttempt> result = new ArrayList<>(session.current.values());
        result.sort(Comparator.comparing(PackAttempt::sentAt));
        return result;
    }

    public long currentEpoch(UUID playerId) {
        PlayerSession session = players.get(playerId);
        return session == null ? 0 : session.connectionCounter.get();
    }

    public void abortPlayer(UUID playerId) {
        PlayerSession session = players.get(playerId);
        if (session == null) {
            return;
        }
        synchronized (session) {
            for (PackAttempt attempt : session.current.values()) {
                attempt.forceState(PackState.CANCELLED, "poc_aborted");
            }
            session.current.clear();
            session.fallbackCounts.clear();
        }
    }

    public void cancelAll(String reason) {
        for (PlayerSession session : players.values()) {
            synchronized (session) {
                for (PackAttempt attempt : session.current.values()) {
                    attempt.forceState(PackState.CANCELLED, reason);
                }
                session.current.clear();
                session.fallbackCounts.clear();
            }
        }
    }

    public int trackedPlayers() {
        return players.size();
    }

    public int activeAttempts() {
        int total = 0;
        for (PlayerSession session : players.values()) {
            total += session.current.size();
        }
        return total;
    }

    public void clearAll() {
        players.clear();
    }

    private PlayerSession session(UUID playerId) {
        PlayerSession existing = players.get(playerId);
        if (existing != null) {
            return existing;
        }
        if (players.size() >= maxPlayers) {
            // Bounded memory: evict one tracked player instead of growing forever.
            var iterator = players.entrySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        return players.computeIfAbsent(playerId, ignored -> new PlayerSession());
    }

    private void addHistory(PlayerSession session, PackAttempt attempt) {
        session.history.addFirst(attempt);
        while (session.history.size() > historyPerPlayer) {
            session.history.pollLast();
        }
    }
}
