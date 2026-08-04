package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.brain.knowledge.PlaceIndex;
import dev.luizloyola.anima.core.config.Config;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * One {@link PlaceIndex} per world, kept current by a block changing and a chunk going away — the
 * twin of {@link RegionCaches}, kept apart because that one remembers what a scan COLLECTED and
 * this one what it MEANT. Per <em>world</em> because the coordinates are: (100, 64, 100) in the
 * Nether is not the tree in the Overworld.
 */
public final class PlaceIndexes {

    /**
     * Weakly keyed, so a world that unloads takes its places with it without anything having to
     * notice — the same arrangement, and the same reason, as {@link RegionCaches}.
     */
    private static final Map<Level, PlaceIndex> INDEXES = new WeakHashMap<>();

    private PlaceIndexes() {
    }

    /** Subscribes the indexes to shutdown, chunk unload and config reloads. Called from init. */
    public static void install() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (INDEXES) {
                INDEXES.clear();
            }
        });
        // Nothing indexed may outlive the loaded chunk it describes: worldgen writes through a
        // generating region and never passes the setBlock hook, so a feature bleeding out of a
        // neighbouring chunk would otherwise leave a thing remembered that was never there.
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            PlaceIndex index = existing(level);
            if (index != null) {
                index.invalidateChunk(chunk.getPos().getMinBlockX() >> 4,
                        chunk.getPos().getMinBlockZ() >> 4);
            }
        });
        // A reload can change the growth caps, and with them what a scan would have collected and
        // therefore what it would have recognised.
        Config.store().onInstall(PlaceIndexes::clearAll);
    }

    /** The index of known things this world's agents share, created on first use. */
    public static PlaceIndex of(ServerLevel level) {
        synchronized (INDEXES) {
            return INDEXES.computeIfAbsent(level, key -> new PlaceIndex());
        }
    }

    /**
     * A block changed — forget whatever thing owned that column, and whatever shared a seam with
     * it. Called from the {@code setBlock} mixin for every world change, client ones included;
     * those are dropped here rather than in the mixin, so the hook stays unconditional calls.
     */
    public static void onBlockChanged(Level level, BlockPos pos) {
        PlaceIndex index = existing(level);
        if (index != null) {
            index.invalidate(pos.getX(), pos.getZ());
        }
    }

    public static void clearAll() {
        synchronized (INDEXES) {
            INDEXES.values().forEach(PlaceIndex::clear);
        }
    }

    /** The index for a world if it has ever had one — never creates, so it is safe to call hot. */
    private static PlaceIndex existing(Level level) {
        if (level.isClientSide()) {
            return null;
        }
        synchronized (INDEXES) {
            return INDEXES.get(level);
        }
    }
}
