package dev.luizloyola.anima.compat.nav;

import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.NavGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * The {@link NavGrid} over a LIVE level — the brain's terrain sense ({@code Percepts.terrain()}),
 * where {@link WorldSnapshot} is the same classification frozen into a box.
 *
 * <p>Server thread only, and <b>never handed to {@code Pathfinder.find} or
 * {@code Pathfinder.survey}</b>: they search off-thread, and the world would move under them.
 *
 * <p>Every read is guarded by the chunk over the column, because {@link WorldSnapshot#classifyAt}
 * never triggers a load and misreads an unloaded one rather than saying so. Unloaded, or off the
 * ends of the build range, answers {@link CellType#OBSTACLE} with {@link #inBounds} false — the
 * same reading a body gets for the inside of a mountain, which is right: it cannot go there either.
 */
public final class LevelGrid implements NavGrid {

    private final Level level;
    /** Reused for every read: a BlockPos per cell would be real garbage at this call rate. */
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private ChunkAccess chunk;
    private int chunkX;
    private int chunkZ;
    /** Which tick {@link #chunk} was resolved on; why that is enough: {@code LevelProbe.chunkAt}. */
    private long chunkAt = Long.MIN_VALUE;

    public LevelGrid(Level level) {
        this.level = level;
    }

    @Override
    public CellType cell(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return CellType.OBSTACLE;
        }
        this.scratch.set(x, y, z);
        // The LEVEL, not the chunk held above: that lookup is a presence guard and nothing more.
        // Blockstates with a dynamic shape classify positionally and read their neighbours, and a
        // ChunkAccess as the BlockGetter answers air for everything outside itself — so passing it
        // would misclassify every cell on a chunk border.
        return WorldSnapshot.classifyAt(this.level, this.scratch);
    }

    @Override
    public double surface(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return 0.0;
        }
        this.scratch.set(x, y, z);
        return WorldSnapshot.surfaceAt(this.level, this.scratch);
    }

    @Override
    public boolean inBounds(int x, int y, int z) {
        return y >= this.level.getMinY() && y <= this.level.getMaxY() && chunkFor(x, z) != null;
    }

    /** The chunk holding this column, or null if it is not loaded. Never forces a load. */
    private ChunkAccess chunkFor(int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        long now = this.level.getGameTime();
        if (now == this.chunkAt && cx == this.chunkX && cz == this.chunkZ) {
            return this.chunk;
        }
        this.chunk = this.level.getChunk(cx, cz, ChunkStatus.FULL, false);
        this.chunkX = cx;
        this.chunkZ = cz;
        this.chunkAt = now;
        return this.chunk;
    }
}
