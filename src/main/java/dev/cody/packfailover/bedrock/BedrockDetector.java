package dev.cody.packfailover.bedrock;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Optional Floodgate integration. V0.1 must never send Java resource packs to
 * Bedrock players, so detection is conservative: if Floodgate is absent or the
 * reflective call fails, the player is treated as Java and normal eligibility
 * checks still apply.
 *
 * <p>The call uses reflection on purpose: Stage 0 must not force a compile-time
 * dependency on a specific Floodgate API build.</p>
 */
public final class BedrockDetector {

    private final Method isFloodgatePlayer;
    private final Object floodgateApi;
    private final boolean available;

    public BedrockDetector() {
        Method method = null;
        Object api = null;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            api = apiClass.getMethod("getInstance").invoke(null);
            method = apiClass.getMethod("isFloodgatePlayer", UUID.class);
        } catch (Throwable ignored) {
            method = null;
            api = null;
        }
        this.isFloodgatePlayer = method;
        this.floodgateApi = api;
        this.available = method != null && api != null;
    }

    public boolean available() {
        return available;
    }

    public boolean isBedrock(UUID playerId) {
        if (!available || playerId == null) {
            return false;
        }
        try {
            Object result = isFloodgatePlayer.invoke(floodgateApi, playerId);
            return result instanceof Boolean value && value;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
