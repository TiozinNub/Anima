package dev.luizloyola.anima.compat;

import dev.luizloyola.anima.mod.AnimaMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * The chunk tickets an agent holds round itself, the way a player holds view and simulation
 * distance. See {@code docs/superpowers/specs/2026-08-10-agent-chunk-tickets-design.md}.
 *
 * <p>Both types persist, so the level's own save restores them at boot and the bodies inside load
 * again. Nothing removes one: a ticket nobody renews lapses after {@link #TIMEOUT}, which is how a
 * body that walked on or is gone lets go.
 */
public final class ChunkTickets {
    private ChunkTickets() {}

    /**
     * Ticks a ticket lives unrenewed. Long enough for a restored ticket to outlast the boot that
     * loads its body; a lapse that short of it would unload and reload the chunks on a metronome.
     */
    public static final long TIMEOUT = 300;

    /** The smallest radius whose centre chunk ticks entities: at 1 it loads and its agents freeze. */
    static final int SIMULATION_RADIUS = 2;

    /** 64 blocks from anywhere in the centre chunk: a horizon scan's reach and a path's margin. */
    static final int LOADING_RADIUS = 4;

    private static TicketType simulation;
    private static TicketType loading;

    /** Registers both types. Called once, from mod init. */
    public static void register() {
        simulation = register("agent_simulation", TicketType.FLAG_PERSIST | TicketType.FLAG_SIMULATION
                | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
        loading = register("agent_loading", TicketType.FLAG_PERSIST | TicketType.FLAG_LOADING);
    }

    private static TicketType register(String name, int flags) {
        return Registry.register(BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, name), new TicketType(TIMEOUT, flags));
    }

    /** Places or renews both tickets on {@code chunk}; a renewal resets the existing ticket's timer. */
    public static void hold(ServerLevel level, ChunkPos chunk) {
        level.getChunkSource().addTicketWithRadius(simulation, chunk, SIMULATION_RADIUS);
        level.getChunkSource().addTicketWithRadius(loading, chunk, LOADING_RADIUS);
    }
}
