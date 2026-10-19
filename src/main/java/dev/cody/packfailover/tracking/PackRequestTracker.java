package dev.cody.packfailover.tracking;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientResourcePackStatus;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerResourcePackSend;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResourcePackSend;
import dev.cody.packfailover.config.PoConfig;
import dev.cody.packfailover.failover.FailoverCoordinator;
import dev.cody.packfailover.failover.FallbackPlan;
import dev.cody.packfailover.session.PlayerPackSessionStore;
import dev.cody.packfailover.util.PfLogger;
import dev.cody.packfailover.util.Sha1Utils;
import dev.cody.packfailover.util.UrlPolicy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Passive PacketEvents listener.
 *
 * <p>Captures server-to-client resource-pack pushes in both CONFIGURATION and PLAY,
 * and receives CONFIGURATION-phase client status packets. Paper 26.2 does not fire
 * {@code PlayerResourcePackStatusEvent} during configuration, so the PacketEvents
 * receive path is required for that phase.</p>
 *
 * <p>This listener never modifies or cancels third-party packets. It only records
 * lightweight metadata; hashing, file access and HTTP I/O never happen here.</p>
 */
public final class PackRequestTracker extends PacketListenerAbstract {

    private final Supplier<PoConfig> config;
    private final PlayerPackSessionStore sessions;
    private final OutgoingSendRegistry outgoing;
    private final FailoverCoordinator coordinator;
    private final PfLogger log;
    private final AtomicLong lastWarningNanos = new AtomicLong();

    public PackRequestTracker(
            Supplier<PoConfig> config,
            PlayerPackSessionStore sessions,
            OutgoingSendRegistry outgoing,
            FailoverCoordinator coordinator,
            PfLogger log
    ) {
        super(PacketListenerPriority.MONITOR);
        this.config = config;
        this.sessions = sessions;
        this.outgoing = outgoing;
        this.coordinator = coordinator;
        this.log = log;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PoConfig cfg = config.get();
        if (!cfg.enabled()) {
            return;
        }
        try {
            PacketTypeCommon type = event.getPacketType();
            if (type == PacketType.Configuration.Server.RESOURCE_PACK_SEND) {
                if (!cfg.tracking().trackConfigPhase()) {
                    return;
                }
                WrapperConfigServerResourcePackSend wrapper = new WrapperConfigServerResourcePackSend(event);
                capture(event, wrapper.getPackId(), wrapper.getUrl(), wrapper.getHash(),
                        wrapper.isRequired(), wrapper.getPrompt(), ProtocolPhase.CONFIGURATION);
            } else if (type == PacketType.Play.Server.RESOURCE_PACK_SEND) {
                if (!cfg.tracking().trackPlayPhase()) {
                    return;
                }
                WrapperPlayServerResourcePackSend wrapper = new WrapperPlayServerResourcePackSend(event);
                capture(event, wrapper.getPackId(), wrapper.getUrl(), wrapper.getHash(),
                        wrapper.isRequired(), wrapper.getPrompt(), ProtocolPhase.PLAY);
            }
        } catch (Throwable throwable) {
            warnRateLimited("packet_send_capture_failed", throwable);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PoConfig cfg = config.get();
        if (!cfg.enabled() || !cfg.tracking().trackConfigPhase()) {
            return;
        }
        if (event.getPacketType() != PacketType.Configuration.Client.RESOURCE_PACK_STATUS) {
            return;
        }
        try {
            WrapperConfigClientResourcePackStatus wrapper = new WrapperConfigClientResourcePackStatus(event);
            User user = event.getUser();
            UUID playerId = uuidOf(user);
            if (playerId == null) {
                log.info("config_status_skipped reason=no_player_uuid packId=" + wrapper.getPackId());
                return;
            }
            PackStatus status = PackStatus.valueOf(wrapper.getResult().name());
            Optional<FallbackPlan> plan = coordinator.onConfigStatus(playerId, wrapper.getPackId(), status);
            plan.ifPresent(value -> sendConfigPhaseFallback(user, value));
        } catch (Throwable throwable) {
            warnRateLimited("config_status_capture_failed", throwable);
        }
    }

    private void capture(
            PacketSendEvent event,
            UUID packId,
            String url,
            String rawHash,
            boolean required,
            Component prompt,
            ProtocolPhase phase
    ) {
        if (event.isCancelled()) {
            log.debug("send_capture_skipped reason=cancelled phase=" + phase + " packId=" + packId);
            return;
        }
        UUID playerId = playerIdOf(event);
        if (playerId == null) {
            log.info("send_capture_skipped reason=no_player_uuid phase=" + phase
                    + " packId=" + packId + " url=" + UrlPolicy.sanitizeForLog(url));
            return;
        }

        Optional<String> sha1 = Sha1Utils.normalize(rawHash);
        if (!rawHash.isEmpty() && sha1.isEmpty()) {
            // Never expand or guess a truncated hash. The attempt stays unverified.
            log.info("captured_hash_unusable phase=" + phase + " player=" + playerId
                    + " packId=" + packId + " length=" + rawHash.length());
        }
        String sourceHost = UrlPolicy.host(url).orElse("");
        if (sourceHost.isEmpty()) {
            log.warn("captured_invalid_url phase=" + phase + " player=" + playerId + " packId=" + packId);
        }

        Optional<SendKind> selfSend = outgoing.consume(playerId, packId, url);
        if (selfSend.isPresent()) {
            log.debug("self_send_confirmed kind=" + selfSend.get() + " player=" + playerId + " packId=" + packId);
            return;
        }

        CapturedSend send = new CapturedSend(
                playerId,
                packId,
                url,
                sha1.orElse(""),
                sourceHost,
                required,
                plainText(prompt),
                phase,
                Instant.now()
        );
        PackAttempt attempt = sessions.recordSend(send, SendKind.PRIMARY);
        log.info("captured " + send.describe() + " gen=" + attempt.generation());
    }

    private void sendConfigPhaseFallback(User user, FallbackPlan plan) {
        if (user == null) {
            coordinator.markSendFailed(plan, "no_user_for_config_send");
            return;
        }
        try {
            String prompt = plan.request().prompt();
            String safePrompt = prompt == null || prompt.isBlank() ? null
                    : (prompt.length() <= 256 ? prompt : prompt.substring(0, 256));
            Component promptComponent = safePrompt == null ? null : Component.text(safePrompt);
            user.sendPacket(new WrapperConfigServerResourcePackSend(
                    plan.request().parent().packId(),
                    plan.request().url(),
                    plan.request().sha1(),
                    plan.request().required(),
                    promptComponent
            ));
            log.info("fallback_push_sent phase=CONFIGURATION player=" + plan.request().parent().playerId()
                    + " packId=" + plan.request().parent().packId()
                    + " sha1=" + plan.request().sha1()
                    + " url=" + UrlPolicy.sanitizeForLog(plan.request().url()));
        } catch (Throwable throwable) {
            log.warn("fallback_push_exception phase=CONFIGURATION", throwable);
            coordinator.markSendFailed(plan, "config_send_exception");
        }
    }

    private UUID playerIdOf(PacketSendEvent event) {
        UUID fromUser = uuidOf(event.getUser());
        if (fromUser != null) {
            return fromUser;
        }
        try {
            Object player = event.getPlayer();
            if (player instanceof Player bukkitPlayer) {
                return bukkitPlayer.getUniqueId();
            }
        } catch (Throwable ignored) {
            // Configuration phase has no Bukkit player; this is expected.
        }
        return null;
    }

    private static UUID uuidOf(User user) {
        if (user == null) {
            return null;
        }
        try {
            return user.getUUID();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String plainText(Component component) {
        if (component == null) {
            return "";
        }
        try {
            return PlainTextComponentSerializer.plainText().serialize(component);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void warnRateLimited(String message, Throwable throwable) {
        long now = System.nanoTime();
        long last = lastWarningNanos.get();
        if (now - last > 30_000_000_000L && lastWarningNanos.compareAndSet(last, now)) {
            log.warn(message, throwable);
        }
    }
}
