package dev.cody.packfailover.failover;

import dev.cody.packfailover.util.PfLogger;
import dev.cody.packfailover.util.Sha1Utils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Paper implementation of {@link PlayPackSender}.
 *
 * <p>Uses {@code Player#addResourcePack}, not {@code setResourcePack}. In Paper
 * 26.2 {@code setResourcePack} first sends a resource-pack "pop" packet that clears
 * every currently applied pack, which would destroy RPEngine's pack stack. The
 * {@code addResourcePack} path only pushes the requested pack.</p>
 */
public final class BukkitPlayPackSender implements PlayPackSender {

    private final Plugin plugin;
    private final PfLogger log;

    public BukkitPlayPackSender(Plugin plugin, PfLogger log) {
        this.plugin = plugin;
        this.log = log;
    }

    @Override
    public void send(
            UUID playerId,
            UUID packId,
            String url,
            String sha1,
            boolean required,
            String prompt,
            Consumer<String> onFailure
    ) {
        Runnable task = () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                onFailure.accept("player_offline");
                return;
            }
            Optional<String> normalized = Sha1Utils.normalize(sha1);
            if (normalized.isEmpty()) {
                onFailure.accept("sha1_unknown");
                return;
            }
            try {
                player.addResourcePack(
                        packId,
                        url,
                        Sha1Utils.toBytes(normalized.get()),
                        safePrompt(prompt),
                        required
                );
                log.info("fallback_push_sent player=" + player.getName()
                        + " packId=" + packId
                        + " sha1=" + normalized.get()
                        + " required=" + required
                        + " url=" + dev.cody.packfailover.util.UrlPolicy.sanitizeForLog(url));
            } catch (RuntimeException e) {
                log.warn("fallback_push_exception player=" + player.getName()
                        + " packId=" + packId + " cause=" + e.getClass().getSimpleName()
                        + " message=" + e.getMessage());
                onFailure.accept("send_exception:" + e.getClass().getSimpleName());
            }
        };

        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    private static String safePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        return prompt.length() <= 256 ? prompt : prompt.substring(0, 256);
    }
}
