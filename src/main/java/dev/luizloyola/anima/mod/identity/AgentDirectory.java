package dev.luizloyola.anima.mod.identity;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.PrivateIdentity;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * What kind of body this agent is — the {@link dev.luizloyola.anima.core.agent.SpeciesProfile}
     * key, e.g. {@code "person"} or {@code "wolf"}.
     *
     * <p><b>Here rather than on the profile because it must answer with no body loaded.</b>
     * {@code AgentProfile.species()} is reachable only through a body, so a listing that mixes a
     * settlement and a kennel could not say which an entry in an unloaded chunk was — the same gap
     * {@link #known} exists to close for names.
     *
     * <p>Empty by default: a directory written before this existed keeps compiling and simply says
     * nothing, and a reader that gets nothing shows nothing rather than guessing a species.
     */
    default Optional<String> speciesOf(AgentId id) {
        return Optional.empty();
    }

    /**
     * Every agent this directory knows — <b>loaded or not</b>, and <b>living or dead</b>.
     * Implementors answer for everything they hold; the filtering happens one level up, in
     * {@link #of}.
     *
     * <p>Enumerated rather than scanned: a listing built from a body scan omits everyone in an
     * unloaded chunk, which reads as "they are gone" rather than "they are elsewhere".
     *
     * <p>Id-keyed so a caller can label each row without asking again. Named {@code known} rather
     * than {@code all} for the reason {@link #identity} is not {@code find}: generics are
     * invariant.
     */
    Map<AgentId, PrivateIdentity> known();

    /** How many agents this directory knows. */
    default int size() {
        return known().size();
    }

    /**
     * The living. That is what a listing means unless it says otherwise.
     *
     * <p>Identity outlives the body by decision, so {@link #known} answers for the dead forever.
     * Unfiltered, {@code list} shows them as merely "unloaded", and the creative-view contact sync
     * pushes every agent who has ever existed to every joining client, for good.
     */
    default Map<AgentId, PrivateIdentity> living(MinecraftServer server) {
        Graves graves = Graves.get(server);
        Map<AgentId, PrivateIdentity> alive = new LinkedHashMap<>();
        known().forEach((id, identity) -> {
            if (!graves.isDead(id)) {
                alive.put(id, identity);
            }
        });
        return Collections.unmodifiableMap(alive);
    }

    /**
     * Registers a directory for every server. Call during mod initialization; providers are
     * asked in registration order and the first that recognises an id wins.
     */
    static void provide(Function<MinecraftServer, AgentDirectory> provider) {
        Providers.REGISTERED.add(provider);
    }

    /**
     * Everything registered, asked in turn, as one directory: lookups stop at the first provider
     * that recognises an id, and the union sees a settlement and a kennel at once.
     */
    static AgentDirectory of(MinecraftServer server) {
        List<Function<MinecraftServer, AgentDirectory>> providers = Providers.REGISTERED;
        return new AgentDirectory() {
            @Override
            public Optional<PrivateIdentity> identity(AgentId id) {
                for (Function<MinecraftServer, AgentDirectory> provider : providers) {
                    Optional<PrivateIdentity> found = provider.apply(server).identity(id);
                    if (found.isPresent()) {
                        return found;
                    }
                }
                return Optional.empty();
            }

            @Override
            public Optional<String> speciesOf(AgentId id) {
                // Chained exactly like identity() above, and for a sharper reason: inheriting the
                // empty default here compiles and answers "no species" for every agent alive.
                for (Function<MinecraftServer, AgentDirectory> provider : providers) {
                    Optional<String> found = provider.apply(server).speciesOf(id);
                    if (found.isPresent()) {
                        return found;
                    }
                }
                return Optional.empty();
            }

            @Override
            public Map<AgentId, PrivateIdentity> known() {
                // Insertion-ordered and provider-ordered, so a listing is stable between calls.
                Map<AgentId, PrivateIdentity> union = new LinkedHashMap<>();
                for (Function<MinecraftServer, AgentDirectory> provider : providers) {
                    // putIfAbsent: identity() resolves first-provider-wins, and a union that
                    // disagreed with it would be a subtle way to report two different names.
                    provider.apply(server).known().forEach(union::putIfAbsent);
                }
                return Collections.unmodifiableMap(union);
            }
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
