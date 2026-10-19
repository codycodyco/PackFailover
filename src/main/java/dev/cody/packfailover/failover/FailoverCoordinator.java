package dev.cody.packfailover.failover;

import dev.cody.packfailover.artifact.VerifiedArtifactRegistry;
import dev.cody.packfailover.bedrock.BedrockDetector;
import dev.cody.packfailover.config.PoConfig;
import dev.cody.packfailover.session.PlayerPackSessionStore;
import dev.cody.packfailover.tracking.OutgoingSendRegistry;
import dev.cody.packfailover.tracking.PackAttempt;
import dev.cody.packfailover.tracking.PackState;
import dev.cody.packfailover.tracking.PackStatus;
import dev.cody.packfailover.tracking.ProtocolPhase;
import dev.cody.packfailover.tracking.SendKind;
import dev.cody.packfailover.util.PfLogger;
import dev.cody.packfailover.util.Sha1Utils;
import dev.cody.packfailover.util.UrlPolicy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Decides whether a failed download is eligible for exactly one fallback push.
 *
 * <p>All decisions are made from in-memory state only. Packet/HTTP I/O and file
 * hashing are never performed here. Play-phase sends are delegated to
 * {@link PlayPackSender}, which schedules on the Paper main thread; configuration-
 * phase sends are returned to the PacketEvents adapter.</p>
 */
public final class FailoverCoordinator {

    private final Supplier<PoConfig> config;
    private final PlayerPackSessionStore sessions;
    private final VerifiedArtifactRegistry artifacts;
    private final OutgoingSendRegistry outgoing;
    private final FallbackEndpoint endpoint;
    private final PlayPackSender playSender;
    private final BedrockDetector bedrock;
    private final PfLogger log;

    private final AtomicLong fallbackPlanned = new AtomicLong();
    private final AtomicLong recovered = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();

    public FailoverCoordinator(
            Supplier<PoConfig> config,
            PlayerPackSessionStore sessions,
            VerifiedArtifactRegistry artifacts,
            OutgoingSendRegistry outgoing,
            FallbackEndpoint endpoint,
            PlayPackSender playSender,
            BedrockDetector bedrock,
            PfLogger log
    ) {
        this.config = config;
        this.sessions = sessions;
        this.artifacts = artifacts;
        this.outgoing = outgoing;
        this.endpoint = endpoint;
        this.playSender = playSender;
        this.bedrock = bedrock;
        this.log = log;
    }

    /**
     * Handles a play-phase status delivered by {@code PlayerResourcePackStatusEvent}.
     */
    public void onPlayStatus(UUID playerId, UUID packId, PackStatus status) {
        PackAttempt attempt = sessions.current(playerId, packId).orElse(null);
        if (attempt == null) {
            log.info("status_unmatched phase=PLAY player=" + playerId + " packId=" + packId + " status=" + status);
            return;
        }
        if (!sessions.isCurrent(attempt)) {
            log.info("status_stale phase=PLAY player=" + playerId + " packId=" + packId
                    + " status=" + status + " attemptEpoch=" + attempt.connectionEpoch()
                    + " currentEpoch=" + sessions.currentEpoch(playerId));
            return;
        }
        if (attempt.state().isTerminal()) {
            log.info("status_ignored phase=PLAY terminal=" + attempt.state()
                    + " player=" + playerId + " packId=" + packId + " status=" + status);
            return;
        }

        applyStatus(attempt, status, ProtocolPhase.PLAY);

        if (status == PackStatus.FAILED_DOWNLOAD && attempt.isPrimaryLike() && !attempt.state().isTerminal()) {
            maybePlayFallback(attempt);
        }
    }

    /**
     * Handles a configuration-phase status observed through PacketEvents.
     *
     * @return a plan that the PacketEvents adapter should send, or empty
     */
    public Optional<FallbackPlan> onConfigStatus(UUID playerId, UUID packId, PackStatus status) {
        PackAttempt attempt = sessions.current(playerId, packId).orElse(null);
        if (attempt == null) {
            log.info("status_unmatched phase=CONFIGURATION player=" + playerId + " packId=" + packId + " status=" + status);
            return Optional.empty();
        }
        if (!sessions.isCurrent(attempt)) {
            log.info("status_stale phase=CONFIGURATION player=" + playerId + " packId=" + packId + " status=" + status);
            return Optional.empty();
        }
        if (attempt.state().isTerminal()) {
            log.info("status_ignored phase=CONFIGURATION terminal=" + attempt.state()
                    + " player=" + playerId + " packId=" + packId + " status=" + status);
            return Optional.empty();
        }

        applyStatus(attempt, status, ProtocolPhase.CONFIGURATION);

        if (status != PackStatus.FAILED_DOWNLOAD || !attempt.isPrimaryLike() || attempt.state().isTerminal()) {
            return Optional.empty();
        }

        PoConfig cfg = config.get();
        if (!cfg.fallback().configPhase()) {
            skip(attempt, "config_phase_fallback_disabled");
            return Optional.empty();
        }
        if (attempt.kind() != SendKind.POC_SIMULATED && (!cfg.fallback().enabled() || cfg.fallback().dryRun())) {
            skip(attempt, cfg.fallback().enabled() ? "dry_run" : "fallback_disabled");
            return Optional.empty();
        }

        Optional<FallbackRequest> request = buildRequest(attempt, true);
        if (request.isEmpty()) {
            return Optional.empty();
        }
        Optional<FallbackPlan> plan = beginFallback(request.get());
        plan.ifPresent(p -> {
            fallbackPlanned.incrementAndGet();
            log.info("fallback_planned phase=CONFIGURATION player=" + p.request().parent().playerId()
                    + " packId=" + p.request().parent().packId()
                    + " sha1=" + p.request().sha1()
                    + " url=" + UrlPolicy.sanitizeForLog(p.request().url()));
        });
        return plan;
    }

    /**
     * Explicit admin-triggered re-push, used by {@code /pf poc resend}. It bypasses
     * the "FAILED_DOWNLOAD only" and enabled/dry-run gates because a human asked for
     * it, but it still requires a verified artifact, an allowlisted source and a
     * valid fallback URL.
     */
    public boolean manualResend(UUID playerId, UUID packId) {
        PackAttempt template = sessions.latest(playerId, packId)
                .filter(PackAttempt::isPrimaryLike)
                .orElse(null);
        if (template == null) {
            log.warn("manual_resend_unavailable player=" + playerId + " packId=" + packId + " reason=no_captured_pack");
            return false;
        }
        Optional<String> sha1 = Sha1Utils.normalize(template.sha1());
        if (sha1.isEmpty()) {
            log.warn("manual_resend_unavailable player=" + playerId + " packId=" + packId + " reason=sha1_unknown");
            return false;
        }
        if (!artifacts.contains(sha1.get())) {
            log.warn("manual_resend_unavailable player=" + playerId + " packId=" + packId
                    + " reason=no_verified_artifact sha1=" + sha1.get());
            return false;
        }
        if (!endpoint.isServable(sha1.get())) {
            log.warn("manual_resend_unavailable player=" + playerId + " packId=" + packId
                    + " reason=cache_file_missing sha1=" + sha1.get());
            return false;
        }
        if (!isSourceAllowed(template)) {
            log.warn("manual_resend_unavailable player=" + playerId + " packId=" + packId
                    + " reason=source_not_allowlisted source=" + template.sourceHost());
            return false;
        }
        if (bedrock.isBedrock(playerId)) {
            log.warn("manual_resend_unavailable player=" + playerId + " packId=" + packId + " reason=bedrock_player");
            return false;
        }
        if (!endpoint.isReady()) {
            log.warn("manual_resend_unavailable player=" + playerId + " packId=" + packId
                    + " reason=http_not_ready");
            return false;
        }
        Optional<String> url = endpoint.publicUrlFor(sha1.get());
        if (url.isEmpty()) {
            log.warn("manual_resend_unavailable player=" + playerId + " packId=" + packId
                    + " reason=public_url_not_configured");
            return false;
        }
        if (url.get().equals(template.url())) {
            log.warn("manual_resend_unavailable player=" + playerId + " packId=" + packId
                    + " reason=fallback_url_equals_primary");
            return false;
        }

        sessions.resetFallbackCount(playerId, packId);
        FallbackRequest request = new FallbackRequest(
                template, url.get(), sha1.get(), template.required(), template.prompt(), ProtocolPhase.PLAY, true);
        PackAttempt fallback = sessions.registerManualFallback(template, request.url());
        outgoing.expect(playerId, packId, request.url(), SendKind.FALLBACK);
        FallbackPlan plan = new FallbackPlan(request, fallback);
        fallbackPlanned.incrementAndGet();
        log.info("fallback_planned trigger=manual player=" + playerId + " packId=" + packId
                + " sha1=" + request.sha1() + " url=" + UrlPolicy.sanitizeForLog(request.url()));
        playSender.send(
                playerId,
                packId,
                request.url(),
                request.sha1(),
                request.required(),
                request.prompt(),
                reason -> markSendFailed(plan, reason)
        );
        return true;
    }

    public void markSendFailed(FallbackPlan plan, String reason) {
        plan.fallbackAttempt().forceState(PackState.CANCELLED, "send_failed:" + reason);
        log.warn("fallback_send_failed player=" + plan.request().parent().playerId()
                + " packId=" + plan.request().parent().packId()
                + " reason=" + reason);
    }

    public long fallbackPlannedCount() {
        return fallbackPlanned.get();
    }

    public long recoveredCount() {
        return recovered.get();
    }

    public long skippedCount() {
        return skipped.get();
    }

    private void maybePlayFallback(PackAttempt attempt) {
        PoConfig cfg = config.get();
        if (attempt.kind() != SendKind.POC_SIMULATED) {
            if (!cfg.fallback().enabled()) {
                skip(attempt, "fallback_disabled");
                return;
            }
            if (cfg.fallback().dryRun()) {
                skip(attempt, "dry_run");
                return;
            }
        }

        Optional<FallbackRequest> request = buildRequest(attempt, false);
        if (request.isEmpty()) {
            return;
        }
        Optional<FallbackPlan> plan = beginFallback(request.get());
        if (plan.isEmpty()) {
            return;
        }
        FallbackPlan fallback = plan.get();
        fallbackPlanned.incrementAndGet();
        log.info("fallback_planned phase=PLAY player=" + attempt.playerId()
                + " packId=" + attempt.packId()
                + " sha1=" + request.get().sha1()
                + " url=" + UrlPolicy.sanitizeForLog(request.get().url()));
        playSender.send(
                attempt.playerId(),
                attempt.packId(),
                request.get().url(),
                request.get().sha1(),
                request.get().required(),
                request.get().prompt(),
                reason -> markSendFailed(fallback, reason)
        );
    }

    private Optional<FallbackRequest> buildRequest(PackAttempt attempt, boolean allowConfigPhase) {
        PoConfig cfg = config.get();
        if (attempt.kind() == SendKind.FALLBACK) {
            skip(attempt, "already_fallback");
            return Optional.empty();
        }
        Optional<String> sha1 = Sha1Utils.normalize(attempt.sha1());
        if (sha1.isEmpty()) {
            skip(attempt, "sha1_unknown");
            return Optional.empty();
        }
        if (!artifacts.contains(sha1.get())) {
            skip(attempt, "no_verified_artifact sha1=" + sha1.get());
            return Optional.empty();
        }
        if (!endpoint.isServable(sha1.get())) {
            skip(attempt, "cache_file_missing sha1=" + sha1.get());
            return Optional.empty();
        }
        if (attempt.phase() == ProtocolPhase.CONFIGURATION) {
            if (!allowConfigPhase) {
                skip(attempt, "config_phase_fallback_disabled");
                return Optional.empty();
            }
            if (!cfg.fallback().configPhase()) {
                skip(attempt, "config_phase_fallback_disabled");
                return Optional.empty();
            }
        }
        if (attempt.required() && !cfg.fallback().allowRequired()) {
            skip(attempt, "required_pack_not_supported");
            return Optional.empty();
        }
        if (bedrock.isBedrock(attempt.playerId())) {
            skip(attempt, "bedrock_player");
            return Optional.empty();
        }
        if (!isSourceAllowed(attempt)) {
            skip(attempt, "source_not_allowlisted source=" + attempt.sourceHost());
            return Optional.empty();
        }
        if (sessions.fallbackCount(attempt.playerId(), attempt.packId()) >= cfg.fallback().maxAttemptsPerPack()) {
            skip(attempt, "max_attempts_reached");
            return Optional.empty();
        }
        if (!endpoint.isReady()) {
            skip(attempt, "http_not_ready");
            return Optional.empty();
        }
        Optional<String> url = endpoint.publicUrlFor(sha1.get());
        if (url.isEmpty()) {
            skip(attempt, "public_url_not_configured");
            return Optional.empty();
        }
        if (url.get().equals(attempt.url())) {
            skip(attempt, "fallback_url_equals_primary");
            return Optional.empty();
        }
        return Optional.of(new FallbackRequest(
                attempt,
                url.get(),
                sha1.get(),
                attempt.required(),
                attempt.prompt(),
                attempt.phase(),
                attempt.kind() == SendKind.POC_SIMULATED
        ));
    }

    private Optional<FallbackPlan> beginFallback(FallbackRequest request) {
        PoConfig cfg = config.get();
        Optional<PackAttempt> fallback = sessions.beginFallback(
                request.parent(), request.url(), cfg.fallback().maxAttemptsPerPack());
        if (fallback.isEmpty()) {
            skip(request.parent(), "max_attempts_reached");
            return Optional.empty();
        }
        outgoing.expect(
                request.parent().playerId(),
                request.parent().packId(),
                request.url(),
                SendKind.FALLBACK
        );
        return Optional.of(new FallbackPlan(request, fallback.get()));
    }

    private boolean isSourceAllowed(PackAttempt attempt) {
        String host = attempt.sourceHost();
        if ("poc.synthetic".equals(host)) {
            return true;
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        return config.get().tracking().primaryHostAllowlist().stream()
                .anyMatch(allowed -> allowed.equalsIgnoreCase(host));
    }

    private void applyStatus(PackAttempt attempt, PackStatus status, ProtocolPhase phase) {
        PackState before = attempt.state();
        PackState next = switch (status) {
            case ACCEPTED -> attempt.kind() == SendKind.FALLBACK
                    ? PackState.FALLBACK_PENDING : PackState.PRIMARY_PENDING;
            case DOWNLOADED -> attempt.kind() == SendKind.FALLBACK
                    ? PackState.FALLBACK_DOWNLOADED : PackState.PRIMARY_DOWNLOADED;
            case SUCCESSFULLY_LOADED -> attempt.kind() == SendKind.FALLBACK
                    ? PackState.RECOVERED : PackState.PRIMARY_LOADED;
            case FAILED_DOWNLOAD -> attempt.kind() == SendKind.FALLBACK
                    ? PackState.EXHAUSTED : PackState.PRIMARY_FAILED_DOWNLOAD;
            case DECLINED -> PackState.DECLINED;
            case FAILED_RELOAD -> PackState.FAILED_RELOAD;
            case INVALID_URL -> PackState.INVALID_URL;
            case DISCARDED -> PackState.DISCARDED;
        };
        boolean applied = attempt.transition(next, "status=" + status);
        if (applied && next == PackState.RECOVERED) {
            recovered.incrementAndGet();
            log.info("recovered player=" + attempt.playerId() + " packId=" + attempt.packId()
                    + " sha1=" + attempt.sha1());
        }
        log.info("status phase=" + phase
                + " player=" + attempt.playerId()
                + " packId=" + attempt.packId()
                + " kind=" + attempt.kind()
                + " status=" + status
                + " state=" + before + "->" + attempt.state());
    }

    private void skip(PackAttempt attempt, String reason) {
        skipped.incrementAndGet();
        log.info("fallback_skipped player=" + attempt.playerId()
                + " packId=" + attempt.packId()
                + " reason=" + reason
                + " phase=" + attempt.phase()
                + " sha1=" + (attempt.sha1().isEmpty() ? "unknown" : attempt.sha1()));
    }
}
