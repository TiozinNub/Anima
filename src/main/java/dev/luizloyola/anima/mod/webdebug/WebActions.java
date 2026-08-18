package dev.luizloyola.anima.mod.webdebug;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.mod.AnimaMod;
import dev.luizloyola.anima.mod.command.AgentSelection;
import dev.luizloyola.anima.mod.debug.DebugLayer;
import dev.luizloyola.anima.mod.debug.DebugView;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * What a dashboard button does. One static verb table, <b>always on the tick thread</b> —
 * {@code WebDebugger} queues these through {@code server.execute}, because an HTTP thread touching
 * an agent is the race {@link WebFeed} exists to prevent.
 *
 * <p>Every verb here is <b>per-player</b>: a pin and a debug layer belong to whoever is watching,
 * not to the world. So each needs the player the panel is acting as, and with nobody picked there
 * is nothing to act on — the readouts still work, the buttons do not.
 *
 * <p><b>There is no glow verb.</b> The selection glow follows the pin: {@code AgentSelection}
 * mirrors every pin change to that player's client, which is the single choke point it is
 * centralised in. A separate toggle would be a second source of truth for the same fact.
 *
 * <p>The movement, brain and world verbs are reserved and not built — see
 * {@code docs/superpowers/specs/2026-08-17-dashboard-design.md}.
 */
final class WebActions {

    private WebActions() {
    }

    /** Runs one verb. Unknown verbs and impossible ones are logged, never thrown at the browser. */
    static void run(MinecraftServer server, WebWatch watch, String verb, Map<String, String> args) {
        // The switch comes first so an unknown verb is NAMED as one. Looking the player up here
        // instead would answer every typo with "nobody is picked", which is the wrong bug.
        switch (verb) {
            case "pin" -> asPlayer(server, watch, verb, player -> pin(player, args.get("id")));
            case "unpin" -> asPlayer(server, watch, verb, AgentSelection::clear);
            case "layer" -> asPlayer(server, watch, verb, player ->
                    layer(server, player, args.get("key"), !"0".equals(args.get("on"))));
            case "layers-clear" -> asPlayer(server, watch, verb, player ->
                    DebugView.clear(server, player.getUUID()));
            default -> AnimaMod.LOGGER.warn("web-debugger: unknown verb \"{}\"", verb);
        }
    }

    /** Runs {@code action} as the player the panel is driving, or says why it could not. */
    private static void asPlayer(MinecraftServer server, WebWatch watch, String verb,
            Consumer<ServerPlayer> action) {
        ServerPlayer player = actor(server, watch);
        if (player == null) {
            AnimaMod.LOGGER.warn(
                    "web-debugger: \"{}\" needs a player to act as, and none is picked", verb);
            return;
        }
        action.accept(player);
    }

    /** The player the panel is driving — its choice, and only that. @see WebSnapshot */
    private static @Nullable ServerPlayer actor(MinecraftServer server, WebWatch watch) {
        UUID acting = watch.actingAs();
        return acting == null ? null : server.getPlayerList().getPlayer(acting);
    }

    private static void pin(ServerPlayer player, @Nullable String id) {
        if (id == null) {
            return;
        }
        try {
            AgentSelection.pin(player, new AgentId(UUID.fromString(id)));
        } catch (IllegalArgumentException e) {
            AnimaMod.LOGGER.warn("web-debugger: \"{}\" is not an agent id", id);
        }
    }

    private static void layer(MinecraftServer server, ServerPlayer player,
            @Nullable String key, boolean on) {
        if (key == null) {
            return;
        }
        DebugLayer.byKey(key).ifPresentOrElse(
                layer -> DebugView.set(server, player.getUUID(), layer, on),
                () -> AnimaMod.LOGGER.warn("web-debugger: no debug layer called \"{}\"", key));
    }
}
