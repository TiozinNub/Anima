package dev.luizloyola.anima.mod.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.PrivateIdentity;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The union {@link AgentDirectory#of} builds out of the registered providers.
 *
 * <p><b>Every fixture here is deliberately inert.</b> {@link AgentDirectory#provide} has no
 * unregister — the list is a permanent, JVM-wide registry — so a provider that enumerated anything
 * from {@code known()} would leak a phantom agent into every test that ran afterwards. These
 * answer for one random id each and list nothing, which nothing else can collide with.
 *
 * <p>The server is never touched, so the fixtures ignore it and the union is built from
 * {@code null}. The day a provider needs it, this stops compiling rather than passing quietly.
 */
class AgentDirectoryTest {

    /** Knows exactly one agent, and refuses to appear in any listing. See the class note. */
    private record Only(AgentId who, String name, String species) implements AgentDirectory {
        @Override
        public Optional<PrivateIdentity> identity(AgentId id) {
            return who.equals(id) ? Optional.of(() -> name) : Optional.empty();
        }

        @Override
        public Optional<String> speciesOf(AgentId id) {
            return who.equals(id) ? Optional.of(species) : Optional.empty();
        }

        @Override
        public Map<AgentId, PrivateIdentity> known() {
            return Map.of();
        }
    }

    private static AgentId register(String name, String species) {
        AgentId id = new AgentId(UUID.randomUUID());
        AgentDirectory.provide(server -> new Only(id, name, species));
        return id;
    }

    @Test
    @DisplayName("the union asks its providers what species an agent is")
    void theUnionDelegatesSpecies() {
        // The whole point of the union overriding it: inheriting the empty default compiles, runs,
        // and silently answers "no species" for every agent in the world.
        AgentId wolf = register("Rex", "wolf");
        assertEquals(Optional.of("wolf"), AgentDirectory.of(null).speciesOf(wolf));
    }

    @Test
    @DisplayName("species stops at the first provider that recognises the id, like the name does")
    void speciesStopsAtTheFirstProviderThatKnows() {
        // A kennel and a settlement both claiming one id is a bug, but it must not be a bug where
        // the name comes from one and the species from the other.
        AgentId id = new AgentId(UUID.randomUUID());
        AgentDirectory.provide(server -> new Only(id, "Rex", "wolf"));
        AgentDirectory.provide(server -> new Only(id, "Otto", "person"));

        AgentDirectory union = AgentDirectory.of(null);
        assertEquals(Optional.of("wolf"), union.speciesOf(id));
        assertEquals(Optional.of("Rex"), union.nameOf(id));
    }

    @Test
    @DisplayName("an id nobody claims has no species — empty, never a guess")
    void anUnclaimedIdHasNoSpecies() {
        assertTrue(AgentDirectory.of(null).speciesOf(new AgentId(UUID.randomUUID())).isEmpty());
    }
}
