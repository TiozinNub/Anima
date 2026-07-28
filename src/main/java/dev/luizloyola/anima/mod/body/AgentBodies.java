package dev.luizloyola.anima.mod.body;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Turning an {@link AgentId} back into a live body — the one lookup every operator surface needs.
 * The id is the stable handle; the entity behind it is not.
 *
 * <p>The filter is {@code instanceof AgentBody}, not an entity type, so settlers and wolves come
 * out of the same call and this class never learns about a new creature; the price is that
 * vanilla's type-indexed lookup cannot see an interface, hence the scan.
 *
 * <p>Dimension-wide: an agent who walked through a portal is still the selected agent. A dead or
 * dying body never counts — a stale selection must fail loudly rather than resolve to a corpse.
 */
public final class AgentBodies {

    private AgentBodies() {
    }

    /** The live body with this id, searching every dimension, or {@code null} if none is loaded. */
    @Nullable
    public static AgentBody findLoaded(MinecraftServer server, AgentId id) {
        for (ServerLevel level : server.getAllLevels()) {
            for (LivingEntity entity : level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(LivingEntity.class),
                    EntitySelector.LIVING_ENTITY_STILL_ALIVE)) {
                if (entity instanceof AgentBody body && id.equals(body.agentId())) {
                    return body;
                }
            }
        }
        return null;
    }

    /** Every live agent body the server currently has loaded, in no particular order. */
    public static List<AgentBody> loaded(MinecraftServer server) {
        List<AgentBody> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (LivingEntity entity : level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(LivingEntity.class),
                    EntitySelector.LIVING_ENTITY_STILL_ALIVE)) {
                if (entity instanceof AgentBody body) {
                    out.add(body);
                }
            }
        }
        return out;
    }
}
