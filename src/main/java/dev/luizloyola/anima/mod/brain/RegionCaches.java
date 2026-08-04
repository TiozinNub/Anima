package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.brain.knowledge.RegionCache;
import dev.luizloyola.anima.core.config.Config;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * One {@link RegionCache} per world, invalidated by a block change and by a chunk unload.
 *
 * <p>Per <em>world</em> because the coordinates are: (100, 64, 100) in the Nether is not the tree
 * in the Overworld.
 *
 * <p><b>{@link #onBlockChanged} sits under every {@code setBlock} on the server</b>, so its quiet
 * path is the only performance that matters: a lookup in a map with one entry per world, then an
 * immediate return. That buys exactness over freshness — a felled tree is forgotten in the same
 * tick its first log breaks, not on a timer.
 */
public final class RegionCaches {

    /**
     * Weakly keyed: an unloading world takes its shapes with it and needs no event — Fabric
     * renamed its world-unload event between the targeted versions. Same as {@link RayPools}.
     */
    private static final Map<Level, RegionCache> CACHES = new WeakHashMap<>();

    private RegionCaches() {
    }

    /** Subscribes the caches to shutdown, chunk unload and config reloads. Called from init. */
    public static void install() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (CACHES) {
                CACHES.clear();
            }
        });
        // Nothing cached may outlive the loaded chunk it describes — see RegionCache. Asked in
        // block coordinates and shifted here: ChunkPos became a record at 26.1, so its x is a
        // field on one target and an accessor on another, while getMinBlockX is neither.
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            RegionCache cache = existing(level);
            if (cache != null) {
                cache.invalidateChunk(chunk.getPos().getMinBlockX() >> 4,
                        chunk.getPos().getMinBlockZ() >> 4);
            }
        });
        // A reload can change limits.region_max_blocks, so every remembered shape was grown under
        // rules that no longer apply.
        Config.store().onInstall(RegionCaches::clearAll);
    }

    /** The shape pool this world's agents share, created on first use. */
    public static RegionCache of(ServerLevel level) {
        synchronized (CACHES) {
            return CACHES.computeIfAbsent(level, key -> new RegionCache());
        }
    }

    /**
     * A block changed — forget any remembered shape it could belong to, or newly join. The
     * {@code setBlock} mixin calls this for every world change; client ones are dropped here so
     * the hook stays a single unconditional call.
     */
    public static void onBlockChanged(Level level, BlockPos pos) {
        RegionCache cache = existing(level);
        if (cache != null) {
            cache.invalidate(pos.getX(), pos.getZ());
        }
    }

    /** Forgets every world's shapes. */
    public static void clearAll() {
        synchronized (CACHES) {
            CACHES.values().forEach(RegionCache::clear);
        }
    }

    /** The cache for a world if it has ever had one — never creates, so it is safe to call hot. */
    private static RegionCache existing(Level level) {
        if (level.isClientSide()) {
            return null;
        }
        synchronized (CACHES) {
            return CACHES.get(level);
        }
    }
}
