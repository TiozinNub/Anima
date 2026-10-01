package dev.luizloyola.anima.mod.body;

import dev.luizloyola.anima.compat.ChunkTickets;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;

/**
 * Keeps every loaded body's chunks loaded and ticking, so it goes on living with no player near.
 * Stateless: each beat renews the tickets on every body's current chunk and the rest lapse.
 */
public final class AgentTickets {
    private AgentTickets() {}

    /** Coupled to {@link ChunkTickets#TIMEOUT}: at least three beats to a timeout, or chunks blink. */
    static final int BEAT = 20;

    /** Subscribes the beat. Called once, from mod init. */
    public static void install() {
        ChunkTickets.register();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % BEAT != 0 || !Config.get().b(Knob.TICKETS_ENABLED)) {
                return;
            }
            for (AgentBody body : AgentBodies.loaded(server)) {
                if (body.entity().level() instanceof ServerLevel level) {
                    ChunkTickets.hold(level, body.entity().chunkPosition());
                }
            }
        });
    }
}
