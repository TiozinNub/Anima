package dev.luizloyola.anima.mod.body;

import dev.luizloyola.anima.core.agent.AgentId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Every agent body the server currently has loaded, and the way back from an {@link AgentId}.
 *
 * <p>An index, not a search: the full-world scan it replaced ran per call, so printing a listing of
 * three hundred agents cost three hundred sweeps. Membership is by body, not by id, because
 * identity resolves on the first tick (<em>after</em> the entity loads), and an id-keyed index
 * would need a null key and a later fill-in.
 *
 * <p>Dimension-wide; a portal does not change who an agent is. Dead and dying bodies are filtered
 * on read, so a stale selection fails loudly rather than resolving to a corpse. Keyed per server and
 * weakly held, so a restarted integrated server leaks nothing.
 */
public final class AgentBodies {

    private static final Map<MinecraftServer, Set<AgentBody>> LOADED = new WeakHashMap<>();

    private AgentBodies() {
    }

    /** Subscribes the index to entity load/unload. Called once, from mod init. */
    public static void install() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof AgentBody body) {
                synchronized (LOADED) {
                    LOADED.computeIfAbsent(level.getServer(),
                            server -> Collections.newSetFromMap(new WeakHashMap<>())).add(body);
                }
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof AgentBody body) {
                synchronized (LOADED) {
                    Set<AgentBody> bodies = LOADED.get(level.getServer());
                    if (bodies != null) {
                        bodies.remove(body);
                    }
                }
            }
        });
        // A stopped server's bodies are gone whether or not every unload fired.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (LOADED) {
                LOADED.remove(server);
            }
        });
    }

    /** The live body with this id, in any dimension, or {@code null} if none is loaded. */
    @Nullable
    public static AgentBody findLoaded(MinecraftServer server, AgentId id) {
        for (AgentBody body : snapshot(server)) {
            if (id.equals(body.agentId()) && body.entity().isAlive()) {
                return body;
            }
        }
        return null;
    }

    /** Every live agent body the server currently has loaded, in no particular order. */
    public static List<AgentBody> loaded(MinecraftServer server) {
        List<AgentBody> alive = new ArrayList<>();
        for (AgentBody body : snapshot(server)) {
            if (body.entity().isAlive()) {
                alive.add(body);
            }
        }
        return alive;
    }

    /** A stable copy — the index is written from the server thread and read from commands. */
    public static List<AgentBody> snapshot(MinecraftServer server) {
        synchronized (LOADED) {
            Set<AgentBody> bodies = LOADED.get(server);
            return bodies == null ? List.of() : new ArrayList<>(bodies);
        }
    }

    /**
     * Rebuilds the index from the world — the scan this class used to be, kept as the recovery path
     * for a consumer whose bodies arrive by a route the entity events miss. Unused normally.
     */
    public static void reindex(MinecraftServer server) {
        Set<AgentBody> rebuilt = Collections.newSetFromMap(new WeakHashMap<>());
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (LivingEntity entity : level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(LivingEntity.class),
                    e -> e instanceof AgentBody)) {
                rebuilt.add((AgentBody) entity);
            }
        }
        synchronized (LOADED) {
            LOADED.put(server, rebuilt);
        }
    }
}
