package dev.luizloyola.anima.compat.nav;

import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.NavGrid;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

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

    /** Values in {@link #verdicts} below {@link #VERDICT_BASE}; the rest are {@code packed + BASE}. */
    private static final byte UNASKED = 0;
    private static final byte POSITIONAL = 1;
    private static final int VERDICT_BASE = 2;

    /**
     * A cell is one byte: the {@link CellType} in the low three bits and — for a
     * {@link CellType#STEP} and nothing else — how high its surface sits, in the four above.
     *
     * <p>The height is stored as {@code sixteenths - 1}: a partial floor is 1 to 15 sixteenths (0 is
     * no floor, 16 a full block), so fifteen values fit in four bits and the widest packed cell
     * comes to 117 — still a positive {@code byte} once the verdict table's offset is added, so
     * nothing needs masking or a wider array.
     */
    private static final int TYPE_BITS = 3;
    private static final int TYPE_MASK = (1 << TYPE_BITS) - 1;
    /** Sixteenths of a block, the grid every vanilla collision shape is built on. */
    private static final int SIXTEENTHS = 16;

    private static byte pack(CellType type, int surface16) {
        return type == CellType.STEP
                ? (byte) (type.ordinal() | (surface16 - 1) << TYPE_BITS)
                : (byte) type.ordinal();
    }

    static CellType type(int packed) {
        return TYPES[packed & TYPE_MASK];
    }

    /** The surface a packed cell describes, as a fraction of a block — see {@link NavGrid#surface}. */
    static double surface(int packed) {
        CellType type = type(packed);
        if (type == CellType.GROUND) return 1.0;
        if (type != CellType.STEP) return 0.0;
        return ((packed >> TYPE_BITS) + 1) / (double) SIXTEENTHS;
    }

    /**
     * What each blockstate classifies as, indexed by {@link Block#BLOCK_STATE_REGISTRY} id.
     *
     * <p>Sound because every question {@link #classifyLive} asks — five tag lookups, a collision
     * shape and one collision query — answers the same for every copy of a blockstate unless the
     * block's shape depends on where it stands: {@code BlockStateBase.initCache} builds the cache
     * {@code getCollisionShape} reads from unconditionally, without a level, exactly when
     * {@code !hasDynamicShape()}. The six blocks that do (moving piston, scaffolding, bamboo and its
     * sapling, pointed dripstone, powder snow) are marked {@link #POSITIONAL} and keep asking the
     * world.
     *
     * <p>Never invalidated: blockstates are interned once, so this is a table about the
     * <em>vocabulary</em>, not the world. Unsynchronised — capture is server-thread-only, and a race
     * is benign: an id always computes the same byte, and byte arrays never tear.
     */
    private static byte[] verdicts = new byte[0];

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    /**
     * The level's own vertical limits, remembered so {@link #inBounds} can tell the two kinds of
     * "no data" apart — see there. Captured with the box because a snapshot outlives the tick that
     * made it and must not reach back into a level to ask.
     */
    private final int worldMinY;
    private final int worldMaxY;
    private final byte[] cells;

    private WorldSnapshot(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
            int worldMinY, int worldMaxY, byte[] cells) {
        this.worldMinY = worldMinY;
        this.worldMaxY = worldMaxY;
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
        Arrays.fill(cells, pack(CellType.OBSTACLE, 0));

        WorldSnapshot snapshot = new WorldSnapshot(min.getX(), minY, min.getZ(),
                sizeX, sizeY, sizeZ, level.getMinY(), level.getMaxY(), cells);
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
                    Arrays.fill(this.cells, row + x0, row + x1 + 1, pack(CellType.PASSABLE, 0));
                    continue;
                }
                for (int x = x0; x <= x1; x++) {
                    BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                    pos.set(x, y, z);
                    this.cells[row + x] = packedAt(state, level, pos);
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
        return type(packedAt(level.getBlockState(pos), level, pos));
    }

    /**
     * How high the standable surface of a single live cell sits — {@link NavGrid#surface} through
     * the same seam as {@link #classifyAt}, and under the same server-thread rule.
     */
    public static double surfaceAt(Level level, BlockPos pos) {
        return surface(packedAt(level.getBlockState(pos), level, pos));
    }

    /**
     * The memo in front of {@link #classifyLive} — see {@link #verdicts} for why it is sound. A
     * state the table has no room for (registered after it was sized) goes the long way
     * round; it is answered correctly, just not cheaply.
     */
    private static byte packedAt(BlockState state, BlockGetter level, BlockPos pos) {
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
                    : (byte) (classifyLive(state, level, pos) + VERDICT_BASE);
            table[id] = memo;
        }
        return memo == POSITIONAL ? classifyLive(state, level, pos) : (byte) (memo - VERDICT_BASE);
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

    /**
     * A body's footprint, parked just above a cell and dropped into it — how {@link #surfaceOf}
     * asks the block where the feet would come to rest.
     *
     * <p>0.6 wide, a player's, because a Person is player-shaped and drives itself through the same
     * physics. Width matters more than it looks: it is the whole reason a ladder, an open door and
     * an open trapdoor are walked <em>through</em> rather than around — their collision hugs one
     * side of the cell and a body in the middle never touches it — while a fence post, which sits
     * in the middle, is the wall it should be. Asking a shape "is your top face full" could not
     * tell those apart, and answered "no" to every one of them.
     *
     * <p>It starts at y=2, above the tallest thing any block puts in its own cell (a fence reaches
     * 1.5), so the box never begins already overlapping — a collision query started inside a shape
     * has nothing to stop it and would report a clear fall through the block.
     */
    private static final double BODY_WIDTH = 0.6;
    private static final AABB FOOTPRINT = new AABB(
            0.5 - BODY_WIDTH / 2, 2.0, 0.5 - BODY_WIDTH / 2,
            0.5 + BODY_WIDTH / 2, 2.1, 0.5 + BODY_WIDTH / 2);
    private static final double PROBE_DROP = -2.0;

    /**
     * How high a body standing in this cell would come to rest, in blocks above the cell's floor:
     * {@code 0} walking straight through, {@code 0.5} for a bottom slab, {@code 1.0} for a full
     * block, {@code 1.5} for a fence — more than a cell, so nothing can stand in it.
     *
     * <p>Vanilla's own collision resolution, not a table of block types, so the awkward shapes are
     * right for free: a cauldron's bowl, a hopper's funnel, a stair's upper tread, a ladder's cell.
     */
    private static double surfaceOf(VoxelShape shape) {
        return FOOTPRINT.minY + shape.collide(Direction.Axis.Y, FOOTPRINT, PROBE_DROP);
    }

    /**
     * Collapses one blockstate to the navigation vocabulary, as a packed cell byte. The order
     * matters — see comments.
     */
    static byte classifyLive(BlockState state, BlockGetter level, BlockPos pos) {
        if (state.isAir()) {
            return pack(CellType.PASSABLE, 0);
        }
        // Harmful before everything else: fire has no collision (would read PASSABLE), magma is a
        // full sturdy block (would read GROUND) — both must classify DANGER first.
        if (isHarmful(state)) {
            return pack(CellType.DANGER, 0);
        }
        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.LAVA)) {
            return pack(CellType.DANGER, 0);
        }
        // Leaves need their own rule ahead of the sturdy-top check: their support shape is empty,
        // so they would read OBSTACLE — yet a body stands on them like a player. Stable leaves are
        // footing (persistent, or within a log's decay reach, distance <= 6); distance-7 leaves can
        // vanish on any random tick and keep reading OBSTACLE. That is what lets a chopper walk the
        // canopy to a far branch (Luiz's sixth chop choreography); the cell itself stays impassable.
        if (state.is(BlockTags.LEAVES)) {
            return pack(isStableLeaves(state) ? CellType.GROUND : CellType.OBSTACLE, 0);
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            // No collision: air-like plants — or the inside of a water column (kelp, seagrass,
            // source blocks). Waterlogged solids fall through to the surface probe instead.
            return pack(fluid.is(FluidTags.WATER) ? CellType.WATER : CellType.PASSABLE, 0);
        }
        double surface = surfaceOf(shape);
        if (surface >= 1.0) {
            // Solid to the top of its own cell, or past it. At exactly a cell it is a floor for the
            // cell above; beyond one (a fence, a wall) nothing can stand in it or on it at any
            // height this vocabulary can name.
            return pack(surface > 1.0 ? CellType.OBSTACLE : CellType.GROUND, 0);
        }
        if (surface <= 0.0) {
            // Collision the body's footprint never meets, because it hugs one wall of the cell: a
            // ladder, a door, an open trapdoor. Not passable, though a player really does walk into
            // a ladder's cell — the probe answers where FEET COME TO REST, not whether a body can
            // cross. A closed door and a ladder have the very same shape, and telling them apart
            // needs a per-direction reading this vocabulary does not have.
            return pack(CellType.OBSTACLE, 0);
        }
        // Finding a floor is not the same as being able to reach it: the probe drops down the
        // middle of the cell, so in a bowl-shaped block it lands on the BOTTOM and knows nothing
        // about the rim. So a sunken floor only counts when nothing else in the cell stands more
        // than a step above it — a hopper's rim is 0.31 over its bowl and is walked into, a
        // cauldron's is a full block and is not. Found in-world (gauntlet I1.22/I1.23); the
        // headless tier cannot see wedging against geometry.
        if (shape.max(Direction.Axis.Y) - surface > MoveCapabilities.STEP_UP) {
            return pack(CellType.OBSTACLE, 0);
        }
        // A floor that stops inside its own cell. This is the case that used to be OBSTACLE and
        // made a village street a wall — see CellType.STEP. Rounding to sixteenths is lossless for
        // vanilla shapes; the clamp only guards a modded shape thinner than one sixteenth.
        int surface16 = Math.max(1, Math.min(SIXTEENTHS - 1, (int) Math.round(surface * SIXTEENTHS)));
        return pack(CellType.STEP, surface16);
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
        int index = index(x, y, z);
        return index < 0 ? CellType.OBSTACLE : type(this.cells[index]);
    }

    @Override
    public double surface(int x, int y, int z) {
        int index = index(x, y, z);
        return index < 0 ? 0.0 : surface(this.cells[index]);
    }

    /**
     * A snapshot is a WINDOW, so this is the one grid where "outside" and "walled" differ — see
     * {@link dev.luizloyola.anima.core.nav.NavGrid#inBounds}. Past the captured box {@link #cell}
     * reads OBSTACLE, and a search that ran out of room there was stopped by the capture, not the
     * terrain.
     *
     * <p><b>The bottom of the world is not the edge of the capture.</b> {@link #capture} clamps its
     * box to the level's limits, so reading below a body near bedrock as "outside" would put every
     * such body's region against an edge and no confinement could be proved — a settler sealed in a
     * stone box on a superflat world had one reachable cell and a verdict that would not fire. The
     * probe is clamped before it is asked.
     */
    @Override
    public boolean inBounds(int x, int y, int z) {
        return index(x, Mth.clamp(y, this.worldMinY, this.worldMaxY), z) >= 0;
    }

    /** The cell's slot in {@link #cells}, or {@code -1} for anything outside the box. */
    private int index(int x, int y, int z) {
        int ix = x - this.minX;
        int iy = y - this.minY;
        int iz = z - this.minZ;
        if (ix < 0 || ix >= this.sizeX || iy < 0 || iy >= this.sizeY || iz < 0 || iz >= this.sizeZ) {
            return -1;
        }
        return (iy * this.sizeZ + iz) * this.sizeX + ix;
    }

    /** Whether the inclusive box {@code [min, max]} lies fully inside this snapshot. */
    public boolean covers(BlockPos min, BlockPos max) {
        return min.getX() >= this.minX && max.getX() < this.minX + this.sizeX
                && min.getY() >= this.minY && max.getY() < this.minY + this.sizeY
                && min.getZ() >= this.minZ && max.getZ() < this.minZ + this.sizeZ;
    }
}
