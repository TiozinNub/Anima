package dev.luizloyola.anima.mod.identity;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.PrivateIdentity;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;

/**
 * Where Anima asks who somebody is — by {@link AgentId}, with no body required.
 *
 * <p>A journal file is named for its owner and a broadcast thought attributed to a speaker, both
 * of which must work for an agent whose chunk is unloaded.
 *
 * <p><b>Anima stores nothing.</b> It defines the question and the tier ({@link PrivateIdentity});
 * the consuming mod owns the store, the persistence and the naming culture — Autarkia answers out
 * of its {@code PersonDirectory}.
 *
 * <p><b>Providers chain rather than replace.</b> Agent ids are disjoint across mods, so
 * {@link #provide} appends and a lookup asks each registered directory until one recognises the
 * id. Register during mod init.
 *
 * <p>With nobody registered every lookup is empty, degrading to an unnamed agent rather than a
 * crash: callers already handle "not known yet".
 */
public interface AgentDirectory {

    /** This directory's record for {@code id}, or empty if it does not know that agent.
     *  Named {@code identity} rather than {@code find} so an implementor may keep its own
     *  {@code find} returning its own concrete record — Java generics are invariant, so
     *  {@code Optional<PersonIdentity>} does not satisfy {@code Optional<PrivateIdentity>}. */
    Optional<PrivateIdentity> identity(AgentId id);

    default Optional<String> nameOf(AgentId id) {
        return identity(id).map(PrivateIdentity::name);
    }

    /**
     * Registers a directory for every server. Call during mod initialization; providers are
     * asked in registration order and the first that recognises an id wins.
     */
    static void provide(Function<MinecraftServer, AgentDirectory> provider) {
        Providers.REGISTERED.add(provider);
    }

    /** Everything registered, asked in turn, as one directory. */
    static AgentDirectory of(MinecraftServer server) {
        List<Function<MinecraftServer, AgentDirectory>> providers = Providers.REGISTERED;
        return id -> {
            for (Function<MinecraftServer, AgentDirectory> provider : providers) {
                Optional<PrivateIdentity> found = provider.apply(server).identity(id);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        };
    }

    /**
     * Holder for the registration list. An interface cannot own mutable static state, and this
     * keeps the list off the public surface — {@link #provide} is the only way in.
     */
    final class Providers {
        private static final List<Function<MinecraftServer, AgentDirectory>> REGISTERED =
                new CopyOnWriteArrayList<>();

        private Providers() {
        }
    }
}
