package dev.luizloyola.anima.mod.body;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.spawn.Anchors;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Mobs spawn round a body as round a player: the bodies the spawner mixins ask after, besides
 * the players vanilla already asks after. Spec: {@code 2026-10-02-spawn-anchors-design.md}.
 *
 * <p>Server thread only. One snapshot per level per server tick, so every spawn attempt and
 * despawn check in a tick sees the same bodies.
 */
public final class SpawnAnchors {
    private SpawnAnchors() {}

    private record Snapshot(int tick, Anchors anchors) {}

    private static final Map<ServerLevel, Snapshot> SNAPSHOTS = new WeakHashMap<>();

    /** This level's anchoring bodies this tick; {@link Anchors#NONE} with the knob off. */
    public static Anchors of(ServerLevel level) {
        int tick = level.getServer().getTickCount();
        Snapshot snapshot = SNAPSHOTS.get(level);
        if (snapshot == null || snapshot.tick() != tick) {
            snapshot = new Snapshot(tick, build(level));
            SNAPSHOTS.put(level, snapshot);
        }
        return snapshot.anchors();
    }

    private static Anchors build(ServerLevel level) {
        if (!Config.get().b(Knob.SPAWNING_ANCHOR_BODIES)) {
            return Anchors.NONE;
        }
        Anchors.Builder anchors = Anchors.builder();
        for (AgentBody body : AgentBodies.loaded(level.getServer())) {
            LivingEntity entity = body.entity();
            if (entity.level() == level && !entity.isRemoved()
                    && body.profile().b(ProfileAspect.BODY_ANCHORS_SPAWNS)) {
                anchors.add(entity.getX(), entity.getY(), entity.getZ());
            }
        }
        return anchors.build();
    }

    /**
     * Appends the bodies' spawn chunks that entity-tick and are not already in {@code out}: a
     * chunk near a player and a body is tried once, as vanilla tries a chunk two players share.
     */
    public static void addSpawnChunks(ServerLevel level, List<LevelChunk> out) {
        Anchors anchors = of(level);
        if (anchors.isEmpty()) {
            return;
        }
        Set<ChunkPos> present = new HashSet<>();
        for (LevelChunk chunk : out) {
            present.add(chunk.getPos());
        }
        for (int[] xz : anchors.spawnChunks()) {
            ChunkPos pos = new ChunkPos(xz[0], xz[1]);
            if (!present.contains(pos) && level.canSpawnEntitiesInChunk(pos)) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(xz[0], xz[1]);
                if (chunk != null) {
                    present.add(pos);
                    out.add(chunk);
                }
            }
        }
    }

    /**
     * How many of the bodies' spawn chunks the global cap gains: those that entity-tick and that
     * vanilla's player count has not already counted.
     */
    public static int extraChunkCount(ServerLevel level, Predicate<ChunkPos> countedForPlayers) {
        Anchors anchors = of(level);
        int extra = 0;
        for (int[] xz : anchors.spawnChunks()) {
            ChunkPos pos = new ChunkPos(xz[0], xz[1]);
            if (!countedForPlayers.test(pos) && level.canSpawnEntitiesInChunk(pos)) {
                extra++;
            }
        }
        return extra;
    }

    /** Squared distance to the nearest anchoring body; infinite if none. */
    public static double nearestSq(ServerLevel level, double x, double y, double z) {
        return of(level).nearestSq(x, y, z);
    }

    /** The chunk's coordinates without naming ChunkPos's fields, which became record accessors. */
    public static int chunkX(ChunkPos pos) {
        return pos.getMinBlockX() >> 4;
    }

    public static int chunkZ(ChunkPos pos) {
        return pos.getMinBlockZ() >> 4;
    }
}
