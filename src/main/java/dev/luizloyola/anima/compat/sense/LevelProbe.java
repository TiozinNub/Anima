package dev.luizloyola.anima.compat.sense;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The {@link BlockProbe} over a live level, seen from one body's eyes — the one compat seam where
 * real blockstates collapse to the tiny {@link BlockKind} vocabulary. Server thread only; unloaded
 * columns answer {@link Integer#MIN_VALUE}/{@link BlockKind#UNKNOWN} without triggering a load.
 *
 * <p>The surface question rides the {@code MOTION_BLOCKING} heightmap — one array lookup, so
 * probing a column is O(1). It counts fluids and leaves, so a lake's surface is its water and a
 * canopy's is its top leaf.
 *
 * <p><b>This is the hottest code in the mod</b>: at fifty settlers crossing a wood, perception
 * spent 2.1 ms of every tick in here, 94% of it in {@link #sightAt}. Two measures keep it affordable,
 * neither changing an answer:
 *
 * <ul>
 *   <li><b>The verdict tables</b> (see {@link #sights}) — a hit is one array index instead of up to
 *       three tag lookups and a collision shape, and {@code state.is(TagKey)} rehashes a record
 *       {@code TagKey} on every call.</li>
 *   <li><b>The chunk under the last read</b> — consecutive reads almost always land in the same
 *       chunk, so holding it removes both chunk lookups per read.</li>
 * </ul>
 */
public final class LevelProbe implements BlockProbe {

    // --- the verdict tables: a question about the vocabulary, not about the world --------------

    /** Table values below {@link #VERDICT_BASE}; the rest are an ordinal plus that base. */
    private static final byte UNASKED = 0;
    private static final byte POSITIONAL = 1;
    private static final int VERDICT_BASE = 2;

    private static final Sight[] SIGHTS = Sight.values();

    /**
     * The only kinds the floor below can answer. A consumer's own kind never reaches the table:
     * classifiers are asked live, every read, because {@code BlockKinds} promises them a level
     * and a position and some of them will want it.
     */
    private static final BlockKind[] FLOOR_KINDS = {
            BlockKind.AIR, BlockKind.LOG, BlockKind.LEAVES, BlockKind.WATER, BlockKind.OTHER};

    /**
     * What an eye makes of each blockstate, indexed by {@link Block#BLOCK_STATE_REGISTRY} id.
     *
     * <p>Sound because every question {@link #sightLive} asks is a pure function of the state except
     * the collision shape, and {@code BlockStateBase.initCache} builds the cache
     * {@code getCollisionShape} returns from unconditionally, without a level, precisely when
     * {@code !hasDynamicShape()}. The handful that do are marked {@link #POSITIONAL}.
     *
     * <p>Never invalidated: blockstates are interned once, so this is a table about the
     * <em>vocabulary</em> — the opposite of {@code RegionCache}.
     */
    private static byte[] sights = new byte[0];

    /** The same, for what Anima's own floor calls a block. See {@link #sights} for the argument. */
    private static byte[] floors = new byte[0];

    private final LivingEntity eyes;
    private final Level level;
    /** Reused for every read: these are hot enough that a BlockPos per read is real garbage. */
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private ChunkAccess chunk;
    private int chunkX;
    private int chunkZ;
    /**
     * Which tick {@link #chunk} was resolved on. A chunk cannot unload while entities are ticking,
     * so within one tick the reference is good and no caller has to clear anything — this class is
     * built in six places, some for a single command.
     */
    private long chunkAt = Long.MIN_VALUE;

    public LevelProbe(LivingEntity eyes) {
        this.eyes = eyes;
        this.level = eyes.level();
    }

    /**
     * A probe with no eyes — for asking what a block is, which does not depend on who is looking.
     * The one question that does ({@link #visibleFromEyes}) refuses to answer. What
     * {@code ProbeDump} uses, so a dump can be taken from the console with nobody selected.
     */
    public LevelProbe(Level level) {
        this.eyes = null;
        this.level = level;
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

    @Override
    public int surfaceY(int x, int z) {
        return highest(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    @Override
    public int topY(int x, int z) {
        // WORLD_SURFACE is "anything that is not air", so it catches everything MOTION_BLOCKING
        // does plus the collision-free things standing on top of it — a cane brake, a berry bush,
        // grass. Same one lookup, a taller answer.
        return highest(Heightmap.Types.WORLD_SURFACE, x, z);
    }

    private int highest(Heightmap.Types heightmap, int x, int z) {
        ChunkAccess loaded = chunkFor(x, z);
        if (loaded == null) {
            return Integer.MIN_VALUE;
        }
        // Level.getHeight masks to chunk-local coordinates and adds one to what the chunk answers,
        // and the caller then took one off again — so asking the chunk directly is the same number,
        // provided the masking that Level was doing happens here.
        int top = loaded.getHeight(heightmap, x & 15, z & 15);
        // Snow layers ride both heightmaps and mask whatever carries them — a snow-capped canopy
        // reads OTHER and its tree is never hypothesized.
        int floor = this.level.getMinY();
        this.scratch.set(x, top, z);
        while (top > floor && loaded.getBlockState(this.scratch).is(Blocks.SNOW)) {
            this.scratch.setY(--top);
        }
        return top;
    }

    /**
     * What stands at a cell, in the one vocabulary a mind has for blocks.
     *
     * <p>The order of the three bands is the contract (see {@link BlockKinds}): out of reach and
     * nothing there, then whatever a consuming mod claims, then Anima's own floor. The floor is last
     * because its collision-free rung swallows every walk-through block into {@link BlockKind#AIR}.
     *
     * <p>Only the floor is memoised; a classifier is asked live on every read, so one claiming a
     * state at some positions and not others still gets asked at all of them.
     */
    @Override
    public BlockKind at(int x, int y, int z) {
        ChunkAccess loaded = chunkFor(x, z);
        if (loaded == null) {
            return BlockKind.UNKNOWN;
        }
        this.scratch.set(x, y, z);
        BlockState state = loaded.getBlockState(this.scratch);
        if (state.isAir()) {
            // Settled here rather than through the registry: air is the commonest read there is,
            // and walking a list to confirm it would be the sense's largest single cost.
            return BlockKind.AIR;
        }
        Optional<BlockKind> claimed = BlockKinds.of(this.level, this.scratch, state);
        if (claimed.isPresent()) {
            return claimed.get();
        }
        return floorKind(state, this.level, this.scratch);
    }

    /**
     * The exact block, by registry id — no memo table, no {@link BlockKinds} band: a single lookup
     * off the state already in hand, asked far too rarely to be worth the table's upkeep.
     */
    @Override
    public String idAt(int x, int y, int z) {
        ChunkAccess loaded = chunkFor(x, z);
        if (loaded == null) {
            return "";
        }
        this.scratch.set(x, y, z);
        BlockState state = loaded.getBlockState(this.scratch);
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** Anima's own ladder, memoised per blockstate — see {@link #sights} for why that is sound. */
    private static BlockKind floorKind(BlockState state, Level level, BlockPos pos) {
        byte[] table = floors();
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0 || id >= table.length) {
            return floorLive(state, level, pos);
        }
        byte memo = table[id];
        if (memo == UNASKED) {
            memo = state.getBlock().hasDynamicShape()
                    ? POSITIONAL
                    : (byte) (floorIndex(floorLive(state, level, pos)) + VERDICT_BASE);
            table[id] = memo;
        }
        return memo == POSITIONAL ? floorLive(state, level, pos) : FLOOR_KINDS[memo - VERDICT_BASE];
    }

    private static BlockKind floorLive(BlockState state, Level level, BlockPos pos) {
        if (state.is(BlockTags.LOGS)) {
            return BlockKind.LOG;
        }
        if (state.is(BlockTags.LEAVES)) {
            // Leaves that will never decay were PLACED, not grown. Only grown leaves tell a tree
            // story, so built ones read as ordinary blocks: nothing hypothesizes a tree from them
            // and a log-and-leaf BUILDING never validates as one. Leaves without the property — a
            // modded canopy — are assumed to decay: only proof demotes. The eye still sees straight
            // through them.
            boolean built = state.getOptionalValue(BlockStateProperties.PERSISTENT).orElse(false);
            // The decay rim (distance 7 — no log within reach) is a canopy DYING, usually one a
            // chop just orphaned. Counting it kept hypothesizing "trees" out of vanishing remnants
            // and fed dying cells into blobs and fishing menus (decision: Luiz, 2026-07-27).
            boolean dying = state.getOptionalValue(BlockStateProperties.DISTANCE).orElse(1) > 6;
            return built || dying ? BlockKind.OTHER : BlockKind.LEAVES;
        }
        // Collision-free cells are not THINGS to a tree story. Open water is water, a waterlogged
        // fence stays a fence; everything walk-through (vines, moss, grass, flowers) is AIR here,
        // never OTHER, because grounded-ness reads OTHER as "real ground" and a vine under a branch
        // log made the mid-air branch read as a grounded stump (split survey; Luiz, 2026-08-02).
        if (state.getCollisionShape(level, pos).isEmpty()) {
            return state.getFluidState().is(FluidTags.WATER) ? BlockKind.WATER : BlockKind.AIR;
        }
        return BlockKind.OTHER;
    }

    private static int floorIndex(BlockKind kind) {
        for (int i = 0; i < FLOOR_KINDS.length; i++) {
            if (FLOOR_KINDS[i] == kind) {
                return i;
            }
        }
        throw new IllegalStateException("the floor answered something not its own: " + kind);
    }

    /**
     * One cell of a marching ray, using the same transparency the sampled sight march below uses —
     * so the horizon sweep and the confirm-ray agree about what can be seen through. Reads exactly
     * one blockstate. That is what lets the sweep charge itself correctly.
     */
    @Override
    public Sight sightAt(int x, int y, int z) {
        ChunkAccess loaded = chunkFor(x, z);
        if (loaded == null) {
            return Sight.OUTSIDE;
        }
        this.scratch.set(x, y, z);
        return sightOf(loaded.getBlockState(this.scratch), this.level, this.scratch);
    }

    /** The memo in front of {@link #sightLive} — see {@link #sights} for why it is sound. */
    private static Sight sightOf(BlockState state, Level level, BlockPos pos) {
        byte[] table = sights();
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0 || id >= table.length) {
            return sightLive(state, level, pos);
        }
        byte memo = table[id];
        if (memo == UNASKED) {
            memo = state.getBlock().hasDynamicShape()
                    ? POSITIONAL
                    : (byte) (sightLive(state, level, pos).ordinal() + VERDICT_BASE);
            table[id] = memo;
        }
        return memo == POSITIONAL ? sightLive(state, level, pos) : SIGHTS[memo - VERDICT_BASE];
    }

    /** Never answers {@link Sight#OUTSIDE}: whether there is a world here is asked before this. */
    private static Sight sightLive(BlockState state, Level level, BlockPos pos) {
        if (!transparentLive(level, pos, state)) {
            return Sight.BLOCKED;
        }
        // The two see-through things that are nonetheless THINGS: a ray that called a canopy or a
        // water surface empty air would march straight through a forest reporting nothing.
        if (state.is(BlockTags.LEAVES) || state.getFluidState().is(FluidTags.WATER)) {
            return Sight.VEILED;
        }
        // Everything left is see-through and not a canopy: literal air, or something too slight to
        // stop a ray — grass, a flower, a cane stalk. Telling those apart costs one cached boolean;
        // the passive fan ignores the answer.
        return state.isAir() ? Sight.CLEAR : Sight.THIN;
    }

    /**
     * The tables, grown on first use rather than in a static initialiser. That is what lets this
     * class be hot-swapped: a redefinition never re-runs {@code <clinit>}, so a static field the
     * running server did not have before the swap arrives null.
     */
    private static byte[] sights() {
        byte[] table = sights;
        if (table == null || table.length < Block.BLOCK_STATE_REGISTRY.size()) {
            table = new byte[Math.max(Block.BLOCK_STATE_REGISTRY.size(), 1)];
            sights = table;
        }
        return table;
    }

    private static byte[] floors() {
        byte[] table = floors;
        if (table == null || table.length < Block.BLOCK_STATE_REGISTRY.size()) {
            table = new byte[Math.max(Block.BLOCK_STATE_REGISTRY.size(), 1)];
            floors = table;
        }
        return table;
    }

    /**
     * Whether the ARM has a clear path from {@code from} to the target — stricter than seeing:
     * anything with a collision shape blocks a swing, INCLUDING leaves (eyes see through a canopy;
     * arms do not — caught live: a log broken through the leaves above it). Plants and water do not
     * impede an arm.
     */
    public static boolean armPathClear(Level level, Vec3 from, BlockPos target) {
        Vec3 to = Vec3.atCenterOf(target);
        int steps = (int) Math.ceil(from.distanceTo(to) * 2.0);
        for (int i = 1; i < steps; i++) {
            BlockPos cell = BlockPos.containing(from.lerp(to, i / (double) steps));
            if (cell.equals(target) || !level.isLoaded(cell)) {
                continue;
            }
            if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * A sampled march from the eyes to the target's center at half-block strides, transparent
     * through air, leaves and water (a tree's own canopy must not hide its trunk). Not exact voxel
     * traversal (a ray squeezing past a corner may miss the corner block), but it gates
     * <em>noticing</em>, not physics, and errs at most one block either way.
     */
    @Override
    public boolean visibleFromEyes(Pos target) {
        if (this.eyes == null) {
            throw new IllegalStateException("this probe has no eyes — see LevelProbe(Level)");
        }
        Vec3 to = new Vec3(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5);
        return sightClear(this.level, this.eyes.getEyePosition(), to,
                new BlockPos(target.x(), target.y(), target.z()));
    }

    @Override
    public boolean sightClearBetween(Pos from, Pos to) {
        // Eye height rather than cell centre at both ends: a wall a body could see over is not
        // cover, and standing in a one-block dip is not hiding.
        Vec3 origin = new Vec3(from.x() + 0.5, from.y() + EYE_HEIGHT, from.z() + 0.5);
        Vec3 target = new Vec3(to.x() + 0.5, to.y() + EYE_HEIGHT, to.z() + 0.5);
        return sightClear(this.level, origin, target, new BlockPos(to.x(), to.y(), to.z()));
    }

    /** Roughly a standing body's eyes above the cell it occupies. */
    private static final double EYE_HEIGHT = 1.62;

    /**
     * Eye-to-BODY sight: rays from {@code from} to sampled points of the target's hitbox —
     * eyes, torso center, feet — cheapest-first with EARLY-OUT, so any visible body part
     * counts as seen (peeking over a wall works; decision: Luiz) and a fully visible person
     * costs a single march. Same eye transparency as {@link #visibleFromEyes}.
     */
    public static boolean bodyVisible(Level level, Vec3 from, LivingEntity target) {
        Vec3 feet = target.position();
        Vec3[] samples = {
                target.getEyePosition(),
                feet.add(0.0, target.getBbHeight() * 0.5, 0.0),
                feet.add(0.0, 0.1, 0.0),
        };
        for (Vec3 to : samples) {
            if (sightClear(level, from, to, BlockPos.containing(to))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The cheap sight check — one ray, eye to body center. Enough for creatures and small bodies,
     * and much faster for farms and herds (decision: Luiz); persons keep the multi-sample
     * {@link #bodyVisible} so peeking over a wall still works on people.
     */
    public static boolean centerVisible(Level level, Vec3 from, LivingEntity target) {
        Vec3 center = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
        return sightClear(level, from, center, BlockPos.containing(center));
    }

    /** The shared sight march — see {@link #visibleFromEyes} for the transparency rationale. */
    private static boolean sightClear(Level level, Vec3 from, Vec3 to, BlockPos targetCell) {
        int steps = (int) Math.ceil(from.distanceTo(to) * 2.0);
        for (int i = 1; i < steps; i++) {
            BlockPos cell = BlockPos.containing(from.lerp(to, i / (double) steps));
            if (cell.equals(targetCell) || !level.isLoaded(cell)) {
                continue;
            }
            if (!transparent(level, cell, level.getBlockState(cell))) {
                return false;
            }
        }
        return true;
    }

    /**
     * What an eye sees through — the same table {@link #sightAt} reads, so the sampled march and
     * the horizon sweep cannot drift apart.
     *
     * <p>FINER than the {@link BlockKind} vocabulary: anything without a collision shape is
     * see-through (a meadow must not blind a body; caught live on real worldgen), as are leaves and
     * water, grown or placed alike.
     */
    private static boolean transparent(Level level, BlockPos cell, BlockState state) {
        return sightOf(state, level, cell) != Sight.BLOCKED;
    }

    /** The definition itself, in front of which {@link #sights} sits. */
    private static boolean transparentLive(Level level, BlockPos cell, BlockState state) {
        return state.isAir()
                || state.is(BlockTags.LEAVES)
                || state.getFluidState().is(FluidTags.WATER)
                || state.getCollisionShape(level, cell).isEmpty();
    }
}
