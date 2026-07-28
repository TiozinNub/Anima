package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingEvent;
import dev.luizloyola.anima.core.agent.Pronouns;
import dev.luizloyola.anima.core.agent.AgentId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * The being viewer — {@code KnowledgeViewer}'s sibling: {@code /anima peers view true} chats every
 * narratable {@link BeingEvent} a person perceives, on {@code BeingSense}'s gate. It renders their
 * PERCEPTION, not the world: losing track of someone in plain sight behind their back is the
 * viewer working correctly.
 *
 * <p>A player's toggle whispers to that player; a CONSOLE toggle broadcasts to everyone and thus
 * the server log — what makes the viewer usable from the headless harness. Transient state, one
 * watcher map per server, gone on stop.
 */
public final class BeingViewer {
    private BeingViewer() {}

    /**
     * Sentinel viewer for a console toggle: broadcast instead of whispering to one player. Public
     * because a caller reading {@link #viewer} has to tell "narrating to everyone" from "narrating
     * to that player".
     */
    public static final UUID EVERYONE = new UUID(0L, 0L);

    /** Watched person → who gets the narration (a player, or {@link #EVERYONE}). */
    private static final Map<MinecraftServer, Map<AgentId, UUID>> WATCHERS = new HashMap<>();

    /** Call once from mod init. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(WATCHERS::remove);
    }

    /** Starts narrating this person's being events; a null viewer means console = everyone. */
    public static void watch(MinecraftServer server, AgentId person, @Nullable UUID viewer) {
        WATCHERS.computeIfAbsent(server, s -> new HashMap<>())
                .put(person, viewer == null ? EVERYONE : viewer);
    }

    /**
     * Who this person's narration currently goes to — a player's UUID, {@link #EVERYONE} for a
     * console toggle, or {@code null} when they aren't being narrated at all. The read side the
     * status readout of {@code /anima peers view} prints.
     */
    public static @Nullable UUID viewer(MinecraftServer server, AgentId person) {
        Map<AgentId, UUID> watched = WATCHERS.get(server);
        return watched == null ? null : watched.get(person);
    }

    /** Stops narrating; false when the person wasn't being watched. */
    public static boolean unwatch(MinecraftServer server, AgentId person) {
        Map<AgentId, UUID> watched = WATCHERS.get(server);
        return watched != null && watched.remove(person) != null;
    }

    /**
     * A being event from a possibly-watched person — narrate it to whoever toggled the view. Takes
     * {@link Pronouns} rather than a picked pronoun: the narration needs both cases ("watching
     * him/her", "the something he/she'd heard").
     */
    static void onEvent(MinecraftServer server, AgentId person, String personName,
                        Pronouns gender, BeingEvent event) {
        Map<AgentId, UUID> watched = WATCHERS.get(server);
        UUID viewer = watched == null ? null : watched.get(person);
        if (viewer == null) {
            return;
        }
        Component line = line(personName, gender, event);
        if (EVERYONE.equals(viewer)) {
            server.getPlayerList().broadcastSystemMessage(line, false);
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(viewer);
        if (player != null) {
            player.sendSystemMessage(line);
        }
    }

    private static Component line(String personName, Pronouns gender, BeingEvent event) {
        Being being = event.being();
        String detail = being.tell(gender.object())
                + (being.awareness() == Being.Awareness.SEEN
                        ? "" : " [" + being.awareness().name().toLowerCase(Locale.ROOT) + "]");
        return switch (event.type()) {
            case SPOTTED -> Component.literal(
                            "[" + personName + "] spotted " + being.knownAs() + " — " + detail)
                    .withStyle(ChatFormatting.GREEN);
            case LOST -> Component.literal(
                            "[" + personName + "] lost track of " + being.knownAs())
                    .withStyle(ChatFormatting.RED);
            case READING_CHANGED -> Component.literal(
                            "[" + personName + "] " + being.knownAs() + " now " + detail)
                    .withStyle(ChatFormatting.YELLOW);
            case RECOGNIZED -> Component.literal(
                            "[" + personName + "] recognized " + being.knownAs()
                                    + " — the " + (event.was() == null
                                            || event.was().identified() == Being.Identified.NONE
                                            ? "someone" : event.was().knownAs())
                                    + " " + gender.subject()
                                    + "'d been hearing, now " + detail)
                    .withStyle(ChatFormatting.AQUA);
        };
    }
}
