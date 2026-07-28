package dev.luizloyola.anima.mod.net;

import dev.luizloyola.anima.mod.command.AgentSelection;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Server-side wiring for the debug selection glow: registers the {@link DebugGlowPayload} type and
 * re-pushes a player's pin whenever their entity is (re)created. {@code AgentSelection} sends the
 * incremental updates itself (every pin/clear); this covers the moments the client shadow is fresh
 * and would otherwise miss a still-live pin — login, and respawn/end-return (a new {@code
 * ServerPlayer} instance, same UUID, so the in-memory pin survives). Call {@link #install()} from
 * common mod init (the payload type must be registered on both sides).
 */
public final class DebugGlowSync {
    private DebugGlowSync() {}

    public static void install() {
        PayloadTypeRegistry.clientboundPlay().register(DebugGlowPayload.TYPE, DebugGlowPayload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                AgentSelection.resync(handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                AgentSelection.resync(newPlayer));
    }
}
