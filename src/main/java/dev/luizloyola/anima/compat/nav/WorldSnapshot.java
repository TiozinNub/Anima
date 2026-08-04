package dev.luizloyola.anima.compat.nav;

import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.NavGrid;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;

/**
 * An immutable box of {@link CellType} classifications baked from real blockstates, and the
 * thread-safety seam the design hangs on: {@link #capture} reads the live {@link Level} and <b>must
 * run on the server thread</b>, but the result is a plain {@code byte[]} that never changes, so a
 * worker thread can search it while the world ticks on.
 *
 * <p>A snapshot, not a live view: block changes after capture are invisible. Paths are short-lived
 * and re-requested on stuck.
 *
 * <p>Baking one is the most expensive thing navigation does, the only part that scales with a
 * trip's <em>volume</em>: a goal at the service's reach limit is a box a quarter of a million cells
 * wide. {@link #bake} resolves a chunk and a section as loop invariants and {@link #verdicts}
 * memoises a blockstate's class, neither changing a verdict.
 */
public final class WorldSnapshot implements NavGrid {
    private static final CellType[] TYPES = CellType.values();

    /** Values in {@link #verdicts} below {@link #VERDICT_BASE}; the rest are {@code ordinal + BASE}. */
    private static final byte UNASKED = 0;
    private static final byte POSITIONAL = 1;
    private static final int VERDICT_BASE = 2;

    /**
     * What each blockstate classifies as, indexed by {@link Block#BLOCK_STATE_REGISTRY} id.
     *
     * <p>Sound because {@code BlockStateBase.initCache} builds the cache {@code getCollisionShape}
     * and {@code isFaceSturdy} read unconditionally, without a level, precisely when
     * {@code !hasDynamicShape()}: every question {@link #classifyLive} asks is then a pure function
     * of the state, and a cell costs one array read instead of five hashed tag lookups. The six
     * dynamic-shape blocks (moving piston, scaffolding, bamboo and its sapling, pointed dripstone,
     * powder snow) are marked {@link #POSITIONAL} and keep asking the world cell by cell.
     *
     * <p>Never invalidated — blockstates are interned once, so this is a table about the
     * <em>vocabulary</em>, not the world. Unsynchronised on purpose: an id always computes the same
     * byte and byte array elements never tear, so a race costs only a recomputation.
     */
    private static byte[] verdicts = new byte[0];

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final byte[] cells;

    private WorldSnapshot(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, byte[] cells) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = cells;
    }

    /**
     * Bakes the inclusive box {@code [min, max]} of {@code level} into a snapshot. Server thread
     * only (live chunk reads). The y range is clamped to the level's build height; unloaded chunks
     * classify as {@link CellType#OBSTACLE} (checked per chunk, so no chunk loads are triggered).
     */
    public static WorldSnapshot capture(Level level, BlockPos min, BlockPos max) {
        int minY = Math.max(min.getY(), level.getMinY());
        int maxY = Math.min(max.getY(), level.getMaxY());
        int sizeX = max.getX() - min.getX() + 1;
        int sizeY = Math.max(maxY - minY + 1, 1);
        int sizeZ = max.getZ() - min.getZ() + 1;

        byte[] cells = new byte[sizeX * sizeY * sizeZ];
        // OBSTACLE is the floor for every cell the walk below never reaches — an unloaded chunk, a
        // section off the end of the level. It has to be painted: OBSTACLE is not ordinal 0, so a
        // fresh array would read PASSABLE, the one thing unknown space must never be.
        Arrays.fill(cells, (byte) CellType.OBSTACLE.ordinal());

        WorldSnapshot snapshot =
                new WorldSnapshot(min.getX(), minY, min.getZ(), sizeX, sizeY, sizeZ, cells);
        snapshot.bake(level, min, max);
        return snapshot;
    }

    /**
     * Fills {@link #cells} from the live world. Called once, from {@link #capture}, before the
     * snapshot is handed to anybody.
     *
     * <p>Chunk-major, not cell-major: {@code level.getBlockState} resolves a chunk on <em>every</em>
     * call and a nav box is hundreds of thousands of calls, so asking once per 16×16 column and once
     * per section makes that a loop invariant. A section that
     * {@linkplain LevelChunkSection#hasOnlyAir() holds only air} is filled a row at a time without
     * reading a block — most of the sky over most nav boxes.
     */
    private void bake(Level level, BlockPos min, BlockPos max) {
        int maxY = this.minY + this.sizeY - 1;
        int firstSection = level.getSectionIndex(this.minY);
        int lastSection = level.getSectionIndex(maxY);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int chunkX = min.getX() >> 4; chunkX <= max.getX() >> 4; chunkX++) {
            for (int chunkZ = min.getZ() >> 4; chunkZ <= max.getZ() >> 4; chunkZ++) {
                // Never force a load: an absent chunk keeps the OBSTACLE already painted over it.
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                int x0 = Math.max(min.getX(), chunkX << 4);
                int x1 = Math.min(max.getX(), (chunkX << 4) + 15);
                int z0 = Math.max(min.getZ(), chunkZ << 4);
                int z1 = Math.min(max.getZ(), (chunkZ << 4) + 15);

                LevelChunkSection[] sections = chunk.getSections();
                for (int index = firstSection; index <= lastSection; index++) {
                    if (index < 0 || index >= sections.length) {
                        continue; // off the end of the level: the painted OBSTACLE stands
                    }
                    int bottom = level.getSectionYFromSectionIndex(index) << 4;
                    bakeSection(level, sections[index], pos, x0, x1, z0, z1,
                            Math.max(this.minY, bottom), Math.min(maxY, bottom + 15));
                }
            }
        }
    }

    /**
     * Bakes one section's share of one chunk's share of the box. {@code x} is the innermost loop
     * because consecutive x are consecutive cells — the writes run straight down the array.
     */
    private void bakeSection(Level level, LevelChunkSection section, BlockPos.MutableBlockPos pos,
            int x0, int x1, int z0, int z1, int y0, int y1) {
        boolean onlyAir = section.hasOnlyAir();
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                // Index of x = 0 in this row, so a cell is row + x with no per-cell arithmetic.
                int row = ((y - this.minY) * this.sizeZ + (z - this.minZ)) * this.sizeX - this.minX;
                if (onlyAir) {
                    Arrays.fill(this.cells, row + x0, row + x1 + 1,
                            (byte) CellType.PASSABLE.ordinal());
                    continue;
                }
                for (int x = x0; x <= x1; x++) {
                    BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                    pos.set(x, y, z);
                    this.cells[row + x] = (byte) classify(state, level, pos).ordinal();
                }
            }
        }
    }

    /**
     * Classifies a single live cell with the exact rules {@link #capture} bakes into a snapshot —
     * the seam the follower's path-integrity check reads through. Server thread only (a live block
     * read), and the caller must confirm the chunk is loaded: this never triggers a load, so an
     * unchecked call into an unloaded chunk would misread.
     */
    public static CellType classifyAt(Level level, BlockPos pos) {
        return classify(level.getBlockState(pos), level, pos);
    }

    /**
     * The memo in front of {@link #classifyLive} — see {@link #verdicts} for why it is sound. A
     * state the table has no room for (registered after it was sized) goes the long way
     * round; it is answered correctly, just not cheaply.
     */
    private static CellType classify(BlockState state, BlockGetter level, BlockPos pos) {
        byte[] table = verdicts();
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0 || id >= table.length) {
            return classifyLive(state, level, pos);
        }
        byte memo = table[id];
        if (memo == UNASKED) {
            // A dynamic shape is the one thing that makes this a question about the cell rather
            // than about the block; every other input classifyLive reads lives on the state.
            memo = state.getBlock().hasDynamicShape()
                    ? POSITIONAL
                    : (byte) (classifyLive(state, level, pos).ordinal() + VERDICT_BASE);
            table[id] = memo;
        }
        return memo == POSITIONAL ? classifyLive(state, level, pos) : TYPES[memo - VERDICT_BASE];
    }

    /**
     * The verdict table, sized on first use — blocks are all registered long before a capture.
     *
     * <p>The null check is not paranoia: a hot swap never re-runs a static initialiser, so a field
     * this class did not have before the swap arrives null on the running server.
     */
    private static byte[] verdicts() {
        byte[] table = verdicts;
        if (table == null || table.length == 0) {
            table = new byte[Math.max(Block.BLOCK_STATE_REGISTRY.size(), 1)];
            verdicts = table;
        }
        return table;
    }

    /** Collapses one blockstate to the navigation vocabulary. The order matters — see comments. */
    private static CellType classifyLive(BlockState state, BlockGetter level, BlockPos pos) {
        if (state.isAir()) {
            return CellType.PASSABLE;
        }
        // Harmful before everything else: fire has no collision (would read PASSABLE), magma is a
        // full sturdy block (would read GROUND) — both must classify DANGER first.
        if (isHarmful(state)) {
            return CellType.DANGER;
        }
        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.LAVA)) {
            return CellType.DANGER;
        }
        // Leaves need their own rule ahead of the sturdy-top check: their support shape is empty,
        // so they would read OBSTACLE — yet a body stands on them like a player. Stable leaves are
        // footing (persistent, or within a log's decay reach, distance <= 6); distance-7 leaves can
        // vanish on any random tick and keep reading OBSTACLE. That is what lets a chopper walk the
        // canopy to a far branch (Luiz's sixth chop choreography); the cell itself stays impassable.
        if (state.is(BlockTags.LEAVES)) {
            return isStableLeaves(state) ? CellType.GROUND : CellType.OBSTACLE;
        }
        if (state.getCollisionShape(level, pos).isEmpty()) {
            // No collision: air-like plants — or the inside of a water column (kelp, seagrass,
            // source blocks). Waterlogged solids fall through to the sturdy-top check instead.
            return fluid.is(FluidTags.WATER) ? CellType.WATER : CellType.PASSABLE;
        }
        if (state.isFaceSturdy(level, pos, Direction.UP)) {
            return CellType.GROUND;
        }
        // Collides but can't be stood on square: fences, walls, open trapdoors, bottom-half
        // shapes we don't model.
        return CellType.OBSTACLE;
    }

    /**
     * Whether a leaf block is footing that will still be there next tick: persistent (placed, so
     * decay never touches it) or fed by a log within vanilla's decay reach ({@code distance <= 6};
     * 7 is the decaying rim). Modded leaves without the vanilla properties answer {@code false}.
     */
    private static boolean isStableLeaves(BlockState state) {
        if (state.hasProperty(BlockStateProperties.PERSISTENT)
                && state.getValue(BlockStateProperties.PERSISTENT)) {
            return true;
        }
        return state.hasProperty(BlockStateProperties.DISTANCE)
                && state.getValue(BlockStateProperties.DISTANCE) <= 6;
    }

    /** Blocks that hurt to touch or stand on, beyond what fluids cover. */
    private static boolean isHarmful(BlockState state) {
        return state.is(BlockTags.FIRE)
                || state.is(BlockTags.CAMPFIRES)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.POWDER_SNOW);
    }

    @Override
    public CellType cell(int x, int y, int z) {
        int ix = x - this.minX;
        int iy = y - this.minY;
        int iz = z - this.minZ;
        if (ix < 0 || ix >= this.sizeX || iy < 0 || iy >= this.sizeY || iz < 0 || iz >= this.sizeZ) {
            return CellType.OBSTACLE;
        }
        return TYPES[this.cells[(iy * this.sizeZ + iz) * this.sizeX + ix]];
    }

    /** Whether the inclusive box {@code [min, max]} lies fully inside this snapshot. */
    public boolean covers(BlockPos min, BlockPos max) {
        return min.getX() >= this.minX && max.getX() < this.minX + this.sizeX
                && min.getY() >= this.minY && max.getY() < this.minY + this.sizeY
                && min.getZ() >= this.minZ && max.getZ() < this.minZ + this.sizeZ;
    }
}
