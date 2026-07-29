package dev.luizloyola.anima.core.social;

import java.util.Objects;
import java.util.UUID;

/**
 * A stable handle to one party — an array of members with no owner (decision: Luiz, social
 * foundations §6), a loner being a party of one.
 *
 * <p>The id belongs to the <em>party</em>, not to any member: members come and go, boards and
 * (later) sites hang off this value, and a party must be referable while every member's chunk is
 * unloaded. Its own value for the same reason
 * {@link dev.luizloyola.anima.core.agent.AgentId} is.
 */
public record PartyId(UUID value) {
    public PartyId {
        Objects.requireNonNull(value, "value");
    }

    public static PartyId of(UUID value) {
        return new PartyId(value);
    }

    public static PartyId random() {
        return new PartyId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
