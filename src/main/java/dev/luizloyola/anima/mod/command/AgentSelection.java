package dev.luizloyola.anima.mod.command;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.mod.net.DebugGlowPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The transient "who am I commanding" selection behind {@code /anima select}.
 *
 * <p>Every person-scoped subcommand used to target the {@code Person} nearest the command source
 * (a 32-block radius). That is unusable when several Persons cluster, when one is across the map,
 * or on the headless dev server, where the console sits at world origin. A selection fixes that:
 * select a Person once and the terse commands keep flowing to it.
 *
 * <p>Selection is a dev/admin ergonomic, not simulation state, so it lives here in memory and dies
 * with the server process — never in the world save. Each command source gets its own slot: a player
 * is keyed by their UUID; every non-player source (the console, rcon, a command block) shares one
 * {@link #CONSOLE} slot. That single shared console slot is what lets the headless FIFO test loop
 * select once and then fire a run of commands at the same Person.
 *
 * <p>A slot holds only a {@link AgentId} — the stable, entity-independent handle. Turning it back
 * into a live entity, and failing loudly when that entity is gone, is the resolver's job in
 * the command resolver: a stale selection must never silently fall through to whoever is nearest,
 * which would quietly command the wrong Person.
 *
 * <p>A <em>player's</em> slot is the same one the debug wand and the dashboard write to, and every
 * change to it is mirrored to that player's client (via {@link DebugGlowPayload}) so the client can
 * glow the selected Person. That mirroring is centralised here — the single choke point through
 * which a selection change reaches the client — so no caller can forget it. The console slot is
 * never synced (it has no client). A fresh client (login/respawn) is re-primed via {@link #resync}.
 */
public final class AgentSelection {
    private AgentSelection() {}

    /** The shared slot for every non-player source (console / rcon / command block). */
    private static final Object CONSOLE = new Object();

    private static final Map<Object, AgentId> SELECTIONS = new ConcurrentHashMap<>();

    private static Object key(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player.getUUID() : CONSOLE;
    }

    /** Selects {@code id} for this source, replacing any previous selection. */
    public static void select(CommandSourceStack source, AgentId id) {
        SELECTIONS.put(key(source), id);
        if (source.getEntity() instanceof ServerPlayer player) sync(player, id);
    }

    /** Selects {@code id} in {@code player}'s slot — the debug wand's and the dashboard's entry
     *  point, keyed by UUID exactly like this player's {@code /anima select}, so all three share
     *  one selection. */
    public static void select(ServerPlayer player, AgentId id) {
        SELECTIONS.put(player.getUUID(), id);
        sync(player, id);
    }

    /** Drops {@code player}'s selection — {@link #select(ServerPlayer, AgentId)}'s twin, for a
     *  caller that holds a player rather than a command source (the wand, the dashboard). */
    public static boolean clear(ServerPlayer player) {
        boolean had = SELECTIONS.remove(player.getUUID()) != null;
        sync(player, null);
        return had;
    }

    /** Drops this source's selection; returns whether one was actually present. */
    public static boolean clear(CommandSourceStack source) {
        boolean had = SELECTIONS.remove(key(source)) != null;
        if (source.getEntity() instanceof ServerPlayer player) sync(player, null);
        return had;
    }

    public static Optional<AgentId> selected(CommandSourceStack source) {
        return Optional.ofNullable(SELECTIONS.get(key(source)));
    }

    /** This player's current selection, if any — the wand's read path (same slot as
     *  {@link #selected(CommandSourceStack)} for a player source). */
    public static Optional<AgentId> selected(ServerPlayer player) {
        return Optional.ofNullable(SELECTIONS.get(player.getUUID()));
    }

    /** Re-sends this player's current selection (or "none") to their client — used when the entity
     *  is (re)created (login, respawn) and the client shadow is empty. */
    public static void resync(ServerPlayer player) {
        sync(player, SELECTIONS.get(player.getUUID()));
    }

    /** Mirrors a player's selection to their client for the glow. Skips clients that did not
     *  register the channel (vanilla clients, the headless test loop) — nothing to glow there. */
    private static void sync(ServerPlayer player, @Nullable AgentId id) {
        if (ServerPlayNetworking.canSend(player, DebugGlowPayload.TYPE)) {
            ServerPlayNetworking.send(player, DebugGlowPayload.of(id));
        }
    }
}
