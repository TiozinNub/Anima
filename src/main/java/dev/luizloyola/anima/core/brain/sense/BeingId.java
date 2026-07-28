package dev.luizloyola.anima.core.brain.sense;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.Objects;
import java.util.UUID;

/**
 * A stable handle to one perceived body — the being sense's key, not an
 * {@link AgentId}: a cow has spatial continuity but no personhood. A PERSON being's id is minted
 * from their person identity, so {@link #asPerson()} converts 1:1 and the social layer keeps
 * speaking AgentId; a creature's from the entity UUID; a HERD's fresh at forming, living while
 * the cluster keeps one member.
 */
public record BeingId(UUID value) {
    public BeingId {
        Objects.requireNonNull(value, "value");
    }

    public static BeingId of(UUID value) {
        return new BeingId(value);
    }

    /** The person this id names — only meaningful for a {@link Being.Kind#PERSON} being. */
    public AgentId asPerson() {
        return AgentId.of(value);
    }

    public static BeingId of(AgentId person) {
        return new BeingId(person.value());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
