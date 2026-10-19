package dev.cody.packfailover.tracking;

import dev.cody.packfailover.config.PoConfig;
import dev.cody.packfailover.failover.FailoverCoordinator;
import dev.cody.packfailover.session.PlayerPackSessionStore;
import dev.cody.packfailover.util.PfLogger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.util.function.Supplier;

/**
 * Paper-side lifecycle and status listener.
 *
 * <p>{@code PlayerResourcePackStatusEvent} is the authoritative status source for
 * the play phase. Paper 26.2 does not fire it during configuration; the PacketEvents
 * receive path covers that phase instead.</p>
 */
public final class PackStatusListener implements Listener {

    private final Supplier<PoConfig> config;
    private final PlayerPackSessionStore sessions;
    private final FailoverCoordinator coordinator;
    private final PfLogger log;

    public PackStatusListener(
            Supplier<PoConfig> config,
            PlayerPackSessionStore sessions,
            FailoverCoordinator coordinator,
            PfLogger log
    ) {
        this.config = config;
        this.sessions = sessions;
        this.coordinator = coordinator;
        this.log = log;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        long epoch = sessions.beginConnection(event.getPlayer().getUniqueId());
        log.debug("connection_begin player=" + event.getPlayer().getName()
                + " uuid=" + event.getPlayer().getUniqueId() + " epoch=" + epoch);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sessions.endConnection(event.getPlayer().getUniqueId());
        log.debug("connection_end player=" + event.getPlayer().getName()
                + " uuid=" + event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (!config.get().enabled()) {
            return;
        }
        PackStatus status;
        try {
            status = PackStatus.valueOf(event.getStatus().name());
        } catch (IllegalArgumentException ignored) {
            return;
        }
        // Never use event.getHash(): it is deprecated for removal and always null.
        coordinator.onPlayStatus(event.getPlayer().getUniqueId(), event.getID(), status);
    }
}
