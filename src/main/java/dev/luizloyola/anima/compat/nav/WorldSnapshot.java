package dev.luizloyola.anima.compat.nav;

import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.Doorway;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.NavGrid;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.Arrays;
import java.util.BitSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

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

    /**
     * A verdict is the packed cell in its low byte and these flags above it, stored plus one so that
     * zero can mean unasked.
     */
    private static final int PACKED = 0xFF;
    /** Its shape depends on where it stands: ask the world every time. */
    private static final int POSITIONAL = 1 << 8;
    /**
     * A trapdoor: over a ladder facing its way it is part of the ladder — a rung open, a hatch shut
     * — which is a question about the cell below, so it is asked of the world.
     */
    private static final int TRAPDOOR = 1 << 9;

    /**
     * The dirt family a hand may cut and put back — vanilla's own tags, which moved between
     * versions, gathered into one of Anima's. See {@link NavGrid#soft}.
     */
    private static final TagKey<Block> SOFT_GROUND =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("anima", "soft_ground"));

    /** Hand-swung trapdoors: the wooden ones and copper. Vanilla has no tag that says it. */
    private static final TagKey<Block> HAND_TRAPDOORS =
            TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("anima", "hand_trapdoors"));

    /**
     * A cell is one byte: the {@link CellType} in the low three bits and four bits of payload
     * above, whose meaning is the type's — how high a {@link CellType#STEP}'s surface sits, which
     * ways a {@link CellType#GROUND} block is a ramp ({@link NavGrid#ramps}), which ways a
     * {@link CellType#DOOR} lets a body through ({@link NavGrid#doorway}). Everything else carries
     * none.
     *
     * <p>A surface is stored as {@code sixteenths - 1}: a partial floor is 1 to 15 sixteenths (0 is
     * no floor, 16 a full block), so fifteen values fit. The widest packed cell comes to 127, a
     * positive {@code byte}; in the verdict table's offset form it wraps negative, which is harmless
     * because only the two lowest values are reserved there.
     */
    private static final int TYPE_BITS = 3;
    private static final int TYPE_MASK = (1 << TYPE_BITS) - 1;
    private static final int PAYLOAD_MASK = 0xF;
    private static final int DOOR_ORDINAL = CellType.DOOR.ordinal();
    /** Sixteenths of a block, the grid every vanilla collision shape is built on. */
    private static final int SIXTEENTHS = 16;

    private static byte pack(CellType type, int payload) {
        return (byte) (type.ordinal() | (payload & PAYLOAD_MASK) << TYPE_BITS);
    }

    /**
     * The eighth bit, on a {@link CellType#PASSABLE} cell: passable, but not replaced by a placement
     * — a torch, a rail, a flower. Set for what is NOT layable, so air and grass stay zero and no
     * other reading of a cell changes. See {@link NavGrid#layable}.
     */
    private static final int FIXED = 1 << 7;
    /** The same bit on a {@link CellType#GROUND} cell: soft ground. See {@link NavGrid#soft}. */
    private static final int SOFT = 1 << 7;

    static boolean layable(int packed) {
        return type(packed) == CellType.PASSABLE && (packed & FIXED) == 0;
    }

    static boolean soft(int packed) {
        return type(packed) == CellType.GROUND && (packed & SOFT) != 0;
    }

    static CellType type(int packed) {
        return TYPES[packed & TYPE_MASK];
    }

    private static int payload(int packed) {
        return packed >> TYPE_BITS & PAYLOAD_MASK;
    }

    /** The ramps a packed cell describes — see {@link NavGrid#ramps}. */
    static int ramps(int packed) {
        return type(packed) == CellType.GROUND ? payload(packed) : 0;
    }

    /** A climbable with a floor on top: scaffolding. See {@link NavGrid#climbFloor}. */
    private static final int CLIMB_FLOOR = 1;

    static boolean climbFloor(int packed) {
        return type(packed) == CellType.CLIMB && (payload(packed) & CLIMB_FLOOR) != 0;
    }

    /** The surface a packed cell describes, as a fraction of a block — see {@link NavGrid#surface}. */
    static double surface(int packed) {
        CellType type = type(packed);
        if (type == CellType.GROUND) return 1.0;
        if (type != CellType.STEP) return 0.0;
        return (payload(packed) + 1) / (double) SIXTEENTHS;
    }

    /**
     * What each blockstate classifies as, indexed by {@link Block#BLOCK_STATE_REGISTRY} id.
     *
     * <p>Sound because every question {@link #classifyLive} asks — tag lookups, its collision
     * shape (and a door's swung twin's) and probes against them — answers the same for every copy
     * of a blockstate unless the
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
    private static short[] verdicts = new short[0];
    /**
     * Each door blockstate's code as its shape reads ({@link #doorGeometry}), plus one, indexed like
     * {@link #verdicts}. Where it stands adds what an iron door's buttons say ({@link #doorCodeAt}).
     */
    private static int[] doorMemo = new int[0];

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
    /** Whether {@link #bake} put a door anywhere in the box — see {@link NavGrid#hasDoors}. */
    private boolean doors;
    /**
     * The {@link NavGrid#doorway} code of every door cell, by cell index: too wide for the cell's
     * byte, and doors are rare, so they live beside it. Null until the first door.
     */
    private Int2IntOpenHashMap doorCodes;
    /** Every hatch ({@link NavGrid#hatch}), by cell index. Null until the first. */
    private IntOpenHashSet hatches;
    /** The level a {@link #lazy} snapshot reads its cells from on first touch; null for a baked one. */
    private @Nullable Level live;
    /** Which cells a {@link #lazy} snapshot has read so far. */
    private @Nullable BitSet read;

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
        WorldSnapshot snapshot = unread(level, min, max);
        snapshot.bake(level, min, max);
        return snapshot;
    }

    /**
     * The same box, read from the world only cell by cell as it is asked about — for a search that
     * touches a few dozen of the thousands of cells it spans. The confinement survey heads straight
     * for the rim, and baking its whole box was 2.5 s of an 83 s crowd profile (2026-09-27).
     *
     * <p><b>Server thread, and this tick only.</b> Every first touch reads the live level, so it is
     * never handed to the pathfinder threads, shared, or kept past the question it was made for.
     */
    public static WorldSnapshot lazy(Level level, BlockPos min, BlockPos max) {
        WorldSnapshot snapshot = unread(level, min, max);
        snapshot.live = level;
        snapshot.read = new BitSet(snapshot.cells.length);
        return snapshot;
    }

    private static WorldSnapshot unread(Level level, BlockPos min, BlockPos max) {
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

        return new WorldSnapshot(min.getX(), minY, min.getZ(),
                sizeX, sizeY, sizeZ, level.getMinY(), level.getMaxY(), cells);
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
        // Most of what is not air comes in runs of one state, and blockstates are interned, so the
        // last state's verdict answers the next cell without the table's id lookup (1.8 s of a
        // 60 s crowd profile, 2026-09-27). A positional verdict is about its cell, so never reused.
        BlockState last = null;
        int lastVerdict = 0;
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
                    // Air is most of any box, and the rest of the loop asks nothing of it.
                    if (state.isAir()) {
                        this.cells[row + x] = pack(CellType.PASSABLE, 0);
                        continue;
                    }
                    pos.set(x, y, z);
                    int verdict;
                    if (state == last) {
                        verdict = lastVerdict;
                    } else {
                        verdict = verdictAt(state, level, pos);
                        last = (verdict & POSITIONAL) != 0 ? null : state;
                        lastVerdict = verdict;
                    }
                    store(row + x, verdict, state, level, pos);
                }
            }
        }
    }

    /** One non-air cell, as {@link #bakeSection} and {@link #readCell} both write it. */
    private void store(int index, int verdict, BlockState state, Level level, BlockPos pos) {
        byte packed = placed(verdict, state, level, pos);
        this.cells[index] = packed;
        if ((packed & TYPE_MASK) == DOOR_ORDINAL) {
            this.doors = true;
            if (this.doorCodes == null) this.doorCodes = new Int2IntOpenHashMap();
            this.doorCodes.put(index, doorCodeAt(state, level, pos));
        } else if ((verdict & TRAPDOOR) != 0 && isHatch(state, level, pos)) {
            if (this.hatches == null) this.hatches = new IntOpenHashSet();
            this.hatches.add(index);
        }
    }

    /** A {@link #lazy} snapshot's first look at one cell — {@link #bake}'s rules, one cell wide. */
    private void readCell(Level level, int x, int y, int z, int index) {
        this.read.set(index);
        ChunkAccess chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk == null) {
            return; // never force a load: the painted OBSTACLE stands
        }
        LevelChunkSection[] sections = chunk.getSections();
        int section = level.getSectionIndex(y);
        if (section < 0 || section >= sections.length) {
            return;
        }
        BlockState state = sections[section].getBlockState(x & 15, y & 15, z & 15);
        if (state.isAir()) {
            this.cells[index] = pack(CellType.PASSABLE, 0);
            return;
        }
        BlockPos pos = new BlockPos(x, y, z);
        store(index, verdictAt(state, level, pos), state, level, pos);
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

    /** {@link NavGrid#climbFloor} of a single live cell, under {@link #classifyAt}'s rules. */
    public static boolean climbFloorAt(Level level, BlockPos pos) {
        return climbFloor(packedAt(level.getBlockState(pos), level, pos));
    }

    /** {@link NavGrid#soft} of a single live cell, under {@link #classifyAt}'s rules. */
    public static boolean softAt(Level level, BlockPos pos) {
        return soft(packedAt(level.getBlockState(pos), level, pos));
    }

    /** {@link NavGrid#layable} of a single live cell, under {@link #classifyAt}'s rules. */
    public static boolean layableAt(Level level, BlockPos pos) {
        return layable(packedAt(level.getBlockState(pos), level, pos));
    }

    /** {@link NavGrid#hatch} of a single live cell. */
    public static boolean hatchAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return (verdictAt(state, level, pos) & TRAPDOOR) != 0 && isHatch(state, level, pos);
    }

    /**
     * How high the standable surface of a single live cell sits — {@link NavGrid#surface} through
     * the same seam as {@link #classifyAt}, and under the same server-thread rule.
     */
    public static double surfaceAt(Level level, BlockPos pos) {
        return surface(packedAt(level.getBlockState(pos), level, pos));
    }

    /** {@link NavGrid#ramps} of a single live cell, under {@link #classifyAt}'s rules. */
    public static int rampsAt(Level level, BlockPos pos) {
        return ramps(packedAt(level.getBlockState(pos), level, pos));
    }

    /** {@link NavGrid#doorway} of a single live cell, under {@link #classifyAt}'s rules. */
    public static int doorwayAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return type(packedAt(state, level, pos)) == CellType.DOOR ? doorCodeAt(state, level, pos) : 0;
    }

    /**
     * The yaw a body holding the climb at {@code pos} faces: into the wall its panel hangs on — a
     * ladder, a trapdoor open over one, a vine on one face. {@code NaN} where there is no one wall
     * to face (scaffolding, a vine on two faces) or no climb at all.
     */
    public static float climbFacingAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return type(packedAt(state, level, pos)) == CellType.CLIMB
                ? panelFacing(state.getShape(level, pos)) : Float.NaN;
    }

    /** The yaw of the face a thin panel lies against; {@code NaN} for any other shape. */
    static float panelFacing(VoxelShape shape) {
        if (shape.isEmpty()) {
            return Float.NaN;
        }
        AABB panel = shape.bounds();
        if (panel.maxX < 0.5) return Direction.WEST.toYRot();
        if (panel.minX > 0.5) return Direction.EAST.toYRot();
        if (panel.maxZ < 0.5) return Direction.NORTH.toYRot();
        if (panel.minZ > 0.5) return Direction.SOUTH.toYRot();
        return Float.NaN;
    }

    /** The packed cell a live blockstate makes where it stands. */
    private static byte packedAt(BlockState state, BlockGetter level, BlockPos pos) {
        return placed(verdictAt(state, level, pos), state, level, pos);
    }

    /**
     * What a verdict means where it stands: a trapdoor open over a ladder facing its way is a rung —
     * vanilla climbs it ({@code LivingEntity.trapdoorUsableAsLadder}) — and anything else is what
     * the blockstate said.
     */
    private static byte placed(int verdict, BlockState state, BlockGetter level, BlockPos pos) {
        if ((verdict & TRAPDOOR) != 0 && state.getValue(TrapDoorBlock.OPEN)
                && overLadder(state, level, pos)) {
            return pack(CellType.CLIMB, 0);
        }
        return (byte) (verdict & PACKED);
    }

    /**
     * The memo in front of {@link #classifyLive} — see {@link #verdicts} for why it is sound. A
     * state the table has no room for (registered after it was sized) goes the long way
     * round; it is answered correctly, just not cheaply.
     */
    private static int verdictAt(BlockState state, BlockGetter level, BlockPos pos) {
        short[] table = verdicts();
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0 || id >= table.length) {
            return (classifyLive(state, level, pos) & PACKED) | flagsOf(state);
        }
        int memo = table[id];
        if (memo == 0) {
            // A dynamic shape is the one thing that makes this a question about the cell rather
            // than about the block; every other input classifyLive reads lives on the state.
            int flags = flagsOf(state);
            memo = 1 + ((flags & POSITIONAL) != 0 ? flags : (classifyLive(state, level, pos) & PACKED) | flags);
            table[id] = (short) memo;
        }
        int verdict = memo - 1;
        return (verdict & POSITIONAL) != 0
                ? (classifyLive(state, level, pos) & PACKED) | (verdict & ~PACKED)
                : verdict;
    }

    private static int flagsOf(BlockState state) {
        return (state.getBlock().hasDynamicShape() ? POSITIONAL : 0)
                | (state.is(BlockTags.TRAPDOORS) ? TRAPDOOR : 0);
    }

    /**
     * The verdict table, sized on first use — blocks are all registered long before a capture.
     *
     * <p>The null check is not paranoia: a hot swap never re-runs a static initialiser, so a field
     * this class did not have before the swap arrives null on the running server.
     */
    private static short[] verdicts() {
        short[] table = verdicts;
        if (table == null || table.length == 0) {
            table = new short[Math.max(Block.BLOCK_STATE_REGISTRY.size(), 1)];
            verdicts = table;
        }
        return table;
    }

    /**
     * Whether a trapdoor sits over a ladder facing its own way — vanilla's rule for a trapdoor that is
     * part of the ladder, block id and all, so it is spelled the way vanilla spells it.
     */
    private static boolean overLadder(BlockState state, BlockGetter level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.is(Blocks.LADDER)
                && below.getValue(LadderBlock.FACING) == state.getValue(TrapDoorBlock.FACING);
    }

    /**
     * A hatch: a shut trapdoor a hand opens, over a ladder facing its way — stood on shut, and a rung
     * once swung.
     */
    private static boolean isHatch(BlockState state, BlockGetter level, BlockPos pos) {
        return !state.getValue(TrapDoorBlock.OPEN) && state.is(HAND_TRAPDOORS)
                && overLadder(state, level, pos);
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
     * The footprint again, over the half of a cell a body walking {@link NavGrid#NORTH},
     * {@link NavGrid#SOUTH}, {@link NavGrid#WEST} and {@link NavGrid#EAST} enters it by — the south
     * half for north, and so on. A stair's low tread is where the leading foot lands first, and
     * where it comes to rest there is the whole of whether the stair is a ramp that way.
     *
     * <p>Kept off the middle line, so a stair's high half never clips a probe meant for its low
     * one.
     */
    private static final AABB[] ENTRY = {
            new AABB(0.5 - BODY_WIDTH / 2, 2.0, 0.55, 0.5 + BODY_WIDTH / 2, 2.1, 0.95),
            new AABB(0.5 - BODY_WIDTH / 2, 2.0, 0.05, 0.5 + BODY_WIDTH / 2, 2.1, 0.45),
            new AABB(0.55, 2.0, 0.5 - BODY_WIDTH / 2, 0.95, 2.1, 0.5 + BODY_WIDTH / 2),
            new AABB(0.05, 2.0, 0.5 - BODY_WIDTH / 2, 0.45, 2.1, 0.5 + BODY_WIDTH / 2)};
    private static final int[] ENTRY_HEADING = {NavGrid.NORTH, NavGrid.SOUTH, NavGrid.WEST, NavGrid.EAST};
    /** Where a stair's low tread sits, and so where an entry probe must rest to find one. */
    private static final double TREAD = 0.5;

    /**
     * Which ways a full-height block is a ramp: entered over a half that stops half a block up, it
     * is two half steps rather than a jump. One way for a straight stair, two for an outer corner,
     * none for an inner corner or any other block — read off the shape, so a modded stair is a
     * stair too.
     */
    static int rampsOf(VoxelShape shape) {
        int ramps = 0;
        for (int i = 0; i < ENTRY.length; i++) {
            double rest = ENTRY[i].minY + shape.collide(Direction.Axis.Y, ENTRY[i], PROBE_DROP);
            if (Math.abs(rest - TREAD) < 1.0E-6) {
                ramps |= ENTRY_HEADING[i];
            }
        }
        return ramps;
    }

    /**
     * Where a body puts itself crossing each face of a cell — a body-wide strip a block tall, from
     * the face to 0.3 in, in {@link NavGrid#heading} order — and standing in its middle. A door's
     * panel lies inside its face's strip and clear of the middle; a shut gate's bar crosses the
     * middle and the two faces it spans.
     */
    private static final int[] FACE_HEADING = {NavGrid.NORTH, NavGrid.SOUTH, NavGrid.WEST, NavGrid.EAST};
    private static final VoxelShape[] FACE = {
            Shapes.box(0.5 - BODY_WIDTH / 2, 0.0, 0.0, 0.5 + BODY_WIDTH / 2, 1.0, 0.3),
            Shapes.box(0.5 - BODY_WIDTH / 2, 0.0, 0.7, 0.5 + BODY_WIDTH / 2, 1.0, 1.0),
            Shapes.box(0.0, 0.0, 0.5 - BODY_WIDTH / 2, 0.3, 1.0, 0.5 + BODY_WIDTH / 2),
            Shapes.box(0.7, 0.0, 0.5 - BODY_WIDTH / 2, 1.0, 1.0, 0.5 + BODY_WIDTH / 2)};
    private static final VoxelShape MIDDLE = Shapes.box(0.5 - BODY_WIDTH / 2, 0.0, 0.5 - BODY_WIDTH / 2,
            0.5 + BODY_WIDTH / 2, 1.0, 0.5 + BODY_WIDTH / 2);
    private static final int EVERY_FACE = NavGrid.NORTH | NavGrid.SOUTH | NavGrid.WEST | NavGrid.EAST;

    private static boolean clear(VoxelShape shape, VoxelShape region) {
        return !Shapes.joinIsNotEmpty(shape, region, BooleanOp.AND);
    }

    private static int blockedFaces(VoxelShape shape) {
        int faces = 0;
        for (int i = 0; i < FACE.length; i++) {
            if (!clear(shape, FACE[i])) faces |= FACE_HEADING[i];
        }
        return faces;
    }

    /**
     * A door's code as its shapes read, standing and swung ({@link Doorway}): the faces and the
     * middle each blocks, and a hand's reach — every face and inside — when a hand swings it.
     */
    static int doorCode(VoxelShape now, VoxelShape swung, boolean byHand) {
        return Doorway.of(blockedFaces(now), !clear(now, MIDDLE), blockedFaces(swung),
                !clear(swung, MIDDLE), byHand ? EVERY_FACE : 0, byHand);
    }

    /**
     * {@link #doorCode} of a blockstate, memoised: a door's swung shape is its {@code OPEN} flipped,
     * which is what a hand does to a wooden one and a button to an iron one. A trapdoor on edge is
     * never swung, so its two readings are the same.
     */
    private static int doorGeometry(BlockState state, VoxelShape shape, BlockGetter level,
            BlockPos pos) {
        int[] memo = doorMemo;
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (memo == null || memo.length == 0) {
            memo = new int[Math.max(Block.BLOCK_STATE_REGISTRY.size(), 1)];
            doorMemo = memo;
        }
        if (id >= 0 && id < memo.length && memo[id] != 0) {
            return memo[id] - 1;
        }
        boolean swings = !state.is(BlockTags.TRAPDOORS) && state.hasProperty(BlockStateProperties.OPEN);
        int code = doorCode(shape, swings
                ? state.cycle(BlockStateProperties.OPEN).getCollisionShape(level, pos)
                : shape, swingable(state));
        if (id >= 0 && id < memo.length) {
            memo[id] = code + 1;
        }
        return code;
    }

    /**
     * A door cell's whole code: its shape's, and for a door no hand swings, the faces it can be
     * opened from by a button, lever or pressure plate beside it ({@link Doors#activatorFaces}).
     */
    private static int doorCodeAt(BlockState state, Level level, BlockPos pos) {
        int code = doorGeometry(state, state.getCollisionShape(level, pos), level, pos);
        if (!Doorway.byHand(code) && state.is(BlockTags.DOORS)) {
            code = Doorway.swingableFrom(code, Doors.activatorFaces(level, pos, state));
        }
        return code;
    }

    /** A door, trapdoor or gate — the blocks whose collision is a panel a body passes beside. */
    private static boolean isDoor(BlockState state) {
        return state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)
                || state.is(BlockTags.FENCE_GATES);
    }

    /**
     * Whether a hand swings it: vanilla's own list of doors a mob may open, which leaves out iron,
     * and every fence gate. Never a trapdoor — shut, one is a floor, and a body has no business
     * turning a wall panel into one.
     */
    private static boolean swingable(BlockState state) {
        return (state.is(BlockTags.MOB_INTERACTABLE_DOORS) || state.is(BlockTags.FENCE_GATES))
                && state.hasProperty(BlockStateProperties.OPEN);
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
        boolean wet = fluid.is(FluidTags.WATER);
        // Climbables ahead of the empty-shape test: vines have no collision at all, and as air a
        // falling body went straight through them. Only where the body has room — scaffolding's
        // top is a floor, and keeps the reading the probe gives it. Waterlogged, a ladder is water
        // to swim up.
        if (!wet && state.is(BlockTags.CLIMBABLE)) {
            if (shape.isEmpty() || surfaceOf(shape) <= 0.0) {
                return pack(CellType.CLIMB, 0);
            }
            // Scaffolding: a floor to whatever stands above it — the shape it shows a body over it,
            // which is what a context with no body in it reads — and a climb to one inside it.
            if (surfaceOf(shape) >= 1.0) {
                return pack(CellType.CLIMB, CLIMB_FLOOR);
            }
        }
        if (shape.isEmpty()) {
            // No collision: air-like plants, an open fence gate — or the inside of a water column
            // (kelp, seagrass, source blocks). Waterlogged solids fall through to the surface probe
            // instead.
            if (wet) {
                return pack(CellType.WATER, 0);
            }
            return (byte) (pack(CellType.PASSABLE, 0) | (state.canBeReplaced() ? 0 : FIXED));
        }
        double surface = surfaceOf(shape);
        if (surface >= 1.0) {
            // Solid to the top of its own cell, or past it. At exactly a cell it is a floor for the
            // cell above, and a stair among them a ramp; beyond one (a fence, a wall) nothing can
            // stand in it or on it at any height this vocabulary can name — unless it is a gate
            // shut across a fence line, which a hand swings out of the way.
            if (surface > 1.0) {
                return swingable(state) && !wet
                        ? pack(CellType.DOOR, 0)
                        : pack(CellType.OBSTACLE, 0);
            }
            return (byte) (pack(CellType.GROUND, rampsOf(shape)) | (state.is(SOFT_GROUND) ? SOFT : 0));
        }
        if (surface <= 0.0) {
            // Collision the body's footprint never meets, because it hugs one face of the cell. A
            // door or a trapdoor stood on edge is a DOOR, crossed along its panel and not through
            // it; anything else of the kind stays a wall, since nothing here knows it can be
            // passed. The probe answers where FEET COME TO REST, which for these is nowhere.
            return isDoor(state) && !wet
                    ? pack(CellType.DOOR, 0)
                    : pack(CellType.OBSTACLE, 0);
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
        return pack(CellType.STEP, surface16 - 1);
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
        int index = slot(x, y, z);
        return index < 0 ? CellType.OBSTACLE : type(this.cells[index]);
    }

    @Override
    public double surface(int x, int y, int z) {
        int index = slot(x, y, z);
        return index < 0 ? 0.0 : surface(this.cells[index]);
    }

    @Override
    public boolean hasDoors() {
        // Unread cells may hold one, and saying no would switch the door rules off for them.
        return this.doors || this.live != null;
    }

    @Override
    public int ramps(int x, int y, int z) {
        int index = slot(x, y, z);
        return index < 0 ? 0 : ramps(this.cells[index]);
    }

    @Override
    public int doorway(int x, int y, int z) {
        int index = slot(x, y, z);
        return index < 0 || this.doorCodes == null ? 0 : this.doorCodes.get(index);
    }

    @Override
    public boolean hatch(int x, int y, int z) {
        int index = slot(x, y, z);
        return index >= 0 && this.hatches != null && this.hatches.contains(index);
    }

    @Override
    public boolean climbFloor(int x, int y, int z) {
        int index = slot(x, y, z);
        return index >= 0 && climbFloor(this.cells[index]);
    }

    @Override
    public boolean layable(int x, int y, int z) {
        int index = slot(x, y, z);
        return index >= 0 && layable(this.cells[index]);
    }

    @Override
    public boolean soft(int x, int y, int z) {
        int index = slot(x, y, z);
        return index >= 0 && soft(this.cells[index]);
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

    /** {@link #index}, with the cell read first when this is a {@link #lazy} snapshot. */
    private int slot(int x, int y, int z) {
        int index = index(x, y, z);
        if (index >= 0 && this.live != null && !this.read.get(index)) {
            readCell(this.live, x, y, z, index);
        }
        return index;
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
