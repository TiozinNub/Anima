package dev.luizloyola.anima.mod.client;

import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Client-side shadow of the latest {@link CellOverlayPayload} per source — what
 * {@link CellOverlayRenderer} redraws until it is replaced, cleared, or times out.
 *
 * <p>Same discipline as {@link DebugViewClient}: read-only, non-authoritative, swapped wholesale.
 * A {@link ConcurrentHashMap} rather than one volatile reference because sources are independent;
 * each entry is an immutable payload replaced atomically, so a frame never sees a half-torn one.
 *
 * <p>Expiry is game time from receipt, so a {@code /tick freeze} keeps the overlay up.
 */
@Environment(EnvType.CLIENT)
public final class CellOverlayClient {
    private CellOverlayClient() {}

    /** One source's latest frame and the game time it stops drawing at ({@code ttlTicks} out). */
    record Held(CellOverlayPayload overlay, long expiresAt) {}

    private static final Map<String, Held> LATEST = new ConcurrentHashMap<>();

    public static void install() {
        ClientPlayNetworking.registerGlobalReceiver(CellOverlayPayload.TYPE,
                (payload, context) -> {
                    ClientLevel level = Minecraft.getInstance().level;
                    if (payload.isEmpty() || level == null) {
                        LATEST.remove(payload.source());
                    } else {
                        LATEST.put(payload.source(),
                                new Held(payload, level.getGameTime() + payload.ttlTicks()));
                    }
                });
        // Drop everything on disconnect so a stale overlay can't flash on the next world.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> LATEST.clear());
    }

    /** The live frames, keyed by source — the renderer prunes expired entries as it draws. */
    static Map<String, Held> frames() {
        return LATEST;
    }
}
