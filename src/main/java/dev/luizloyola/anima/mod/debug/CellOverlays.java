package dev.luizloyola.anima.mod.debug;

import dev.luizloyola.anima.mod.net.CellOverlayPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * The cell overlay's server half — the extension point a consumer paints through.
 *
 * <p>Anima ships the wire, the expiry and the gizmo drawing; the consumer computes a
 * {@link CellOverlayPayload}, {@link #show}s it to the player who asked, refreshes it faster than
 * its own {@code ttlTicks}, and {@link #clear}s it when they switch it off.
 *
 * <p>No watcher registry here: who is watching what, and when to rescan, is the feature's own
 * state, as {@code KnowledgeViewer} keeps its own.
 */
public final class CellOverlays {
    private CellOverlays() {}

    private static boolean registered;

    /**
     * Call from common mod init — the payload type must be registered on both sides.
     *
     * <p>Idempotent on purpose: Anima registers its own wire so a bare install works, and a
     * consumer that also calls this is harmless rather than a duplicate-registration crash. First
     * caller wins; init order does not matter.
     */
    public static void init() {
        if (registered) {
            return;
        }
        registered = true;
        PayloadTypeRegistry.clientboundPlay().register(
                CellOverlayPayload.TYPE, CellOverlayPayload.CODEC);
    }

    /** Pushes one overlay frame; the client replaces this source's previous frame with it. */
    public static void show(ServerPlayer player, CellOverlayPayload overlay) {
        ServerPlayNetworking.send(player, overlay);
    }

    /** Stops drawing this source for this player now, rather than waiting out the TTL. */
    public static void clear(ServerPlayer player, String source) {
        ServerPlayNetworking.send(player, CellOverlayPayload.clear(source));
    }
}
