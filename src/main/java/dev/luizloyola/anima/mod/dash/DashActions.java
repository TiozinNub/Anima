package dev.luizloyola.anima.mod.dash;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.mod.AnimaMod;
import dev.luizloyola.anima.mod.command.AgentSelection;
import dev.luizloyola.anima.mod.debug.DebugLayer;
import dev.luizloyola.anima.mod.debug.DebugView;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * What a dashboard button does. One static verb table, <b>always on the tick thread</b> —
 * {@code DashServer} queues these through {@code server.execute}, because an HTTP thread touching
 * an agent is the race {@link DashFeed} exists to prevent.
 *
 * <p>Every verb here is <b>per-player</b>: a pin and a debug layer belong to whoever is watching,
 * not to the world. So each needs the player the panel is acting as, and with nobody online there
 * is nothing to act on — the readouts still work, the buttons do not.
 *
 * <p><b>There is no glow verb.</b> The selection glow follows the pin: {@code AgentSelection}
 * mirrors every pin change to that player's client, which is the single choke point it is
 * centralised in. A separate toggle would be a second source of truth for the same fact.
 *
 * <p>The movement, brain and world verbs are reserved and not built — see
 * {@code docs/superpowers/specs/2026-08-17-dashboard-design.md}.
 */
final class DashActions {

    private DashActions() {
    }

    /** Runs one verb. Unknown verbs and impossible ones are logged, never thrown at the browser. */
    static void run(MinecraftServer server, DashWatch watch, String verb, Map<String, String> args) {
        ServerPlayer player = actor(server, watch);
        if (player == null) {
            AnimaMod.LOGGER.warn("dash: \"{}\" needs a player to act as, and none is online", verb);
            return;
        }
        switch (verb) {
            case "pin" -> pin(player, args.get("id"));
            case "unpin" -> AgentSelection.clear(player);
            case "layer" -> layer(server, player, args.get("key"), !"0".equals(args.get("on")));
            case "layers-clear" -> DebugView.clear(server, player.getUUID());
            default -> AnimaMod.LOGGER.warn("dash: unknown verb \"{}\"", verb);
        }
    }

    /** The player the panel is driving — its choice, or the only one online. */
    private static @Nullable ServerPlayer actor(MinecraftServer server, DashWatch watch) {
        UUID acting = watch.actingAs();
        if (acting != null) {
            return server.getPlayerList().getPlayer(acting);
        }
        var online = server.getPlayerList().getPlayers();
        return online.size() == 1 ? online.get(0) : null;
    }

    private static void pin(ServerPlayer player, @Nullable String id) {
        if (id == null) {
            return;
        }
        try {
            AgentSelection.pin(player, new AgentId(UUID.fromString(id)));
        } catch (IllegalArgumentException e) {
            AnimaMod.LOGGER.warn("dash: \"{}\" is not an agent id", id);
        }
    }

    private static void layer(MinecraftServer server, ServerPlayer player,
            @Nullable String key, boolean on) {
        if (key == null) {
            return;
        }
        DebugLayer.byKey(key).ifPresentOrElse(
                layer -> DebugView.set(server, player.getUUID(), layer, on),
                () -> AnimaMod.LOGGER.warn("dash: no debug layer called \"{}\"", key));
    }
}
