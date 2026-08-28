package dev.luizloyola.anima.mod.social;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * The face beside a spoken line — {@link dev.luizloyola.anima.mod.identity.AgentDirectory}'s
 * registration pattern, applied to a portrait.
 *
 * <p><b>Anima draws nothing.</b> It owns the chat choke point and so has to ask; what an agent
 * LOOKS like is the consuming mod's, which builds the glyph out of its own appearance
 * ({@link dev.luizloyola.anima.compat.Chats#head}) and registers a provider during mod init.
 *
 * <p>Providers chain rather than replace, for the reason agent directories do: agent ids are
 * disjoint across mods, so a lookup asks each in turn and the first non-empty answer wins. With
 * nobody registered every lookup is empty and a line renders as plain text — a bare install still
 * talks.
 *
 * <p>A final class rather than an interface with a holder: the holder exists only because an
 * interface cannot own mutable static state, and a portrait has no per-server view to implement.
 */
public final class Portraits {

    private static final List<BiFunction<MinecraftServer, AgentId, Optional<Component>>> REGISTERED =
            new CopyOnWriteArrayList<>();

    private Portraits() {}

    /** Registers a source of portraits. Call during mod initialization. */
    public static void provide(BiFunction<MinecraftServer, AgentId, Optional<Component>> provider) {
        REGISTERED.add(provider);
    }

    /** This agent's portrait, from the first provider that has one. */
    public static Optional<Component> of(MinecraftServer server, AgentId id) {
        for (BiFunction<MinecraftServer, AgentId, Optional<Component>> provider : REGISTERED) {
            Optional<Component> found = provider.apply(server, id);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
