package dev.luizloyola.anima.core.agent;

import java.util.Objects;
import java.util.UUID;

/**
 * A stable, permanent handle to whoever is thinking — independent of any in-world entity.
 *
 * <p>The id belongs to the <em>agent</em>: it may be referenced while the entity is unloaded, exist
 * before one ever spawns, or be remembered after death, so it is its own value rather than a reuse
 * of the entity's Minecraft UUID.
 *
 * <p>Anima never asks what kind of body an id belongs to; naming and appearance are the consumer's.
 */
public record AgentId(UUID value) {
    public AgentId {
        Objects.requireNonNull(value, "value");
    }

    public static AgentId of(UUID value) {
        return new AgentId(value);
    }

    public static AgentId random() {
        return new AgentId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
