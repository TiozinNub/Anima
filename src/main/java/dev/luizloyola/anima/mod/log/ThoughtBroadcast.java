package dev.luizloyola.anima.mod.log;

import dev.luizloyola.anima.core.brain.Arbiter;
import dev.luizloyola.anima.core.log.Entry;
import dev.luizloyola.anima.core.log.JournalService;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.mod.identity.AgentDirectory;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * The thinking-out-loud debug channel (asked for by Luiz, 2026-07-27): the arbiter narrates each
 * change of what a body is doing through {@link Arbiter#EVENT_THINK} journal lines, and this sink
 * forwards an ENABLED person's to every player's chat as a gray-italic aside.
 *
 * <p>Off by default and per-person ({@code /anima think} toggles the resolved person), so a
 * fifty-person settlement doesn't shout fifty monologues into chat. The toggle set is transient
 * debug state, wiped with the server like the rings.
 */
public final class ThoughtBroadcast {
    /** Persons currently thinking out loud. Server-thread writes; a concurrent set out of
     *  plain caution about future off-thread readers. */
    private static final Set<AgentId> ENABLED = ConcurrentHashMap.newKeySet();

    private ThoughtBroadcast() {
    }

    /**
     * Attach the chat sink to a server's journal service. Same enqueue-only contract as the
     * file sink — a chat broadcast is a plain game-thread call, no I/O.
     */
    public static void attach(MinecraftServer server, JournalService service) {
        BiConsumer<AgentId, Entry> sink = (who, entry) -> {
            if (!Arbiter.EVENT_THINK.equals(entry.event()) || !ENABLED.contains(who)) {
                return;
            }
            String name = AgentDirectory.of(server).nameOf(who).orElse("?");
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal(name + " · " + entry.detail())
                            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
        };
        service.subscribe(sink);
        service.subscribeAsides(sink); // a repeated failure, kept out of the ring and the file
    }

    /** Whether this person is narrating — the read behind bare {@code think}. */
    public static boolean isOn(AgentId id) {
        return ENABLED.contains(id);
    }

    /** Sets narration for this person, and answers whether that changed anything. */
    public static boolean set(AgentId id, boolean on) {
        return on ? ENABLED.add(id) : ENABLED.remove(id);
    }
}
