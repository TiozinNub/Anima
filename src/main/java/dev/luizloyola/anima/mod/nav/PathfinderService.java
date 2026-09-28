package dev.luizloyola.anima.mod.nav;

import dev.luizloyola.anima.compat.nav.LiveDoors;
import dev.luizloyola.anima.compat.nav.TerrainProfile;
import dev.luizloyola.anima.compat.nav.WorldSnapshot;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Confinement;
import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.SetbackField;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.nav.HandsOff;
import dev.luizloyola.anima.core.nav.NavDomain;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.EnclosureCheck;
import dev.luizloyola.anima.core.nav.GoalCell;
import dev.luizloyola.anima.core.nav.NavGrid;
import dev.luizloyola.anima.core.nav.Path;
import dev.luizloyola.anima.core.nav.PathRequest;
import dev.luizloyola.anima.core.nav.Pathfinder;
import dev.luizloyola.anima.mod.AnimaMod;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Turns "this AgentBody wants to reach that block" into a {@link Path}. Live-world reads —
 * snapshot capture, goal grounding — happen on the server thread inside
 * {@link #request}/{@link #computeNow}; the A* sees only the immutable {@link WorldSnapshot}, so
 * it can run anywhere (see the pathfinder design doc).
 *
 * <p>{@link #request} searches on the shared executor, returning a future the {@link Navigator}
 * polls from its tick, so a path is only ever <em>applied</em> on the main thread;
 * {@link #computeNow} is the same pipeline synchronously ({@link #inThread()}). Both return a
 * {@link Dispatched}, so nothing downstream branches on which ran — the choice is a knob, not a
 * code path.
 */
public final class PathfinderService {
    private PathfinderService() {}

    /** How far past the start∪goal box the snapshot extends, so detours have room to route. */
    private static final int HORIZONTAL_MARGIN = 16;
    /**
     * The confinement survey's own, smaller box — the routing margin is sized for detours, and a
     * survey is not routing anywhere.
     *
     * <p>It sets what can still be PROVED a prison: the verdict is void once the reached region
     * comes within a body's own reach of the rim (~5 cells for a Person), so this half-extent
     * proves an enclosure up to about 11×11 and calls anything larger open. The box is read only
     * where the search touches it, so widening it costs a free body nothing and a shut-in one the
     * cells of its prison.
     */
    private static final int SURVEY_MARGIN = 10;
    private static final int UP_MARGIN = 6;
    private static final int DOWN_MARGIN = 10;
    /**
     * Snapshot half-extent cap: a goal further than this gets a snapshot clamped around the start
     * and a partial path toward it (the navigator re-paths from the partial end, so long trips
     * happen leg by leg instead of baking enormous boxes).
     */
    private static final int MAX_REACH = 96;
    /** How far above a submerged start to look for its own waterline — see {@link #surfaceStart}. */
    private static final int START_RISE_SCAN = 8;

    private static final int WORKER_THREADS = 2;

    private static @Nullable ExecutorService executor;

    /**
     * Same-tick snapshot reuse: bodies dispatched in one tick share one capture instead of N of
     * the same terrain. Older than the current tick is stale by definition — conservative
     * freshness, per the design doc's open question.
     */
    private static final Map<ServerLevel, CachedSnapshot> snapshots = new HashMap<>();

    private record CachedSnapshot(WorldSnapshot snapshot, long gameTime) {}

    /** Call once from mod init: ties the worker pool and snapshot cache to the server lifecycle. */
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (executor != null) {
                executor.shutdownNow(); // in-flight searches read only private snapshots; safe to kill
                executor = null;
            }
            snapshots.clear();
        });
    }

    /**
     * The result plus the snapshot it searched. The navigator holds that snapshot for the life
     * of the path: the follower reads it for edge awareness (slow-down near deep drops),
     * guaranteed consistent with what the path was planned against.
     */
    public record Dispatched(CompletableFuture<Path> result, WorldSnapshot snapshot) {}

    /**
     * Asynchronous pathfinding: snapshot on this (the server) thread, search on a worker. Poll
     * the future from a tick, never from a callback that touches the world.
     *
     * @param who the agent this search is for — {@code null} while its identity is resolving (see
     *     {@link #variety}). Reduced to a handle and a seed on the calling thread: the worker must
     *     not reach back into a body to ask who it is.
     */
    public static Dispatched request(ServerLevel level, @Nullable AgentId who, BlockPos start,
            BlockPos goal, MoveCapabilities body, DangerField danger, SetbackField setbacks) {
        return request(level, who, start, goal, body, danger, setbacks, NavDomain.EVERYWHERE);
    }

    /** As above, fenced: the route may stand only inside {@code fence}. */
    public static Dispatched request(ServerLevel level, @Nullable AgentId who, BlockPos start,
            BlockPos goal, MoveCapabilities body, DangerField danger, SetbackField setbacks,
            NavDomain fence) {
        return request(level, who, start, goal, body, danger, setbacks, fence, java.util.Set.of(),
                HandsOff.NONE);
    }

    /**
     * As above, knowing of the recorded pillar blocks around the route and the columns it may not
     * lay or cut in — see {@code PathRequest}.
     */
    public static Dispatched request(ServerLevel level, @Nullable AgentId who, BlockPos start,
            BlockPos goal, MoveCapabilities body, DangerField danger, SetbackField setbacks,
            NavDomain fence, java.util.Set<Long> pillars, HandsOff handsOff) {
        WorldSnapshot snapshot = sharedSnapshot(level, start, goal);
        PathRequest pathRequest = buildRequest(snapshot, start, goal, body, danger, who, setbacks)
                .within(fence).near(pillars).keepingOff(handsOff);
        String handle = who == null ? "?" : who.shortText();
        CompletableFuture<Path> result = CompletableFuture.supplyAsync(() -> {
            Path path = Pathfinder.find(snapshot, pathRequest);
            trace(handle, start, goal, path);
            return path;
        }, executor());
        return new Dispatched(result, snapshot);
    }

    /**
     * Whether a search runs on the server thread rather than a worker — see
     * {@link Knob#PATHFINDER_IN_THREAD} for what that trades away.
     *
     * <p>Read through the store per request, never cached, so
     * {@code /anima config set limits.pathfinder_in_thread} retunes a running world: the knob
     * exists to A/B against tick rate, and a restart would compare a cold world with a warm one.
     */
    public static boolean inThread() {
        return Config.get().b(Knob.PATHFINDER_IN_THREAD);
    }

    /** The same pipeline as {@link #request}, entirely on the calling (server) thread. */
    public static Dispatched computeNow(ServerLevel level, @Nullable AgentId who, BlockPos start,
            BlockPos goal, MoveCapabilities body, DangerField danger, SetbackField setbacks) {
        return computeNow(level, who, start, goal, body, danger, setbacks, NavDomain.EVERYWHERE);
    }

    /** As above, fenced: the route may stand only inside {@code fence}. */
    public static Dispatched computeNow(ServerLevel level, @Nullable AgentId who, BlockPos start,
            BlockPos goal, MoveCapabilities body, DangerField danger, SetbackField setbacks,
            NavDomain fence) {
        return computeNow(level, who, start, goal, body, danger, setbacks, fence, java.util.Set.of(),
                HandsOff.NONE);
    }

    /** As above, knowing of recorded pillars and the columns it may not lay or cut in. */
    public static Dispatched computeNow(ServerLevel level, @Nullable AgentId who, BlockPos start,
            BlockPos goal, MoveCapabilities body, DangerField danger, SetbackField setbacks,
            NavDomain fence, java.util.Set<Long> pillars, HandsOff handsOff) {
        WorldSnapshot snapshot = sharedSnapshot(level, start, goal);
        Path path = Pathfinder.find(snapshot, buildRequest(snapshot, start, goal, body, danger, who,
                setbacks).within(fence).near(pillars).keepingOff(handsOff));
        return new Dispatched(CompletableFuture.completedFuture(path), snapshot);
    }

    /**
     * Whether this body can leave where it stands. Snapshot and search on the SERVER thread: the
     * asker is a drive reading a percept mid-tick, with nowhere to put a future.
     *
     * <p><b>There is no stranded-report gate</b>, whatever this comment said until 2026-08-18: it
     * was dropped because a body can sit for minutes without attempting a walk, and the
     * affordability argument was left behind describing it. What actually bounds the cost is
     * {@link #SURVEY_MARGIN} — a free body expands only until it touches the rim of that box, and
     * a shut-in one has a handful of cells by definition — and the slot
     * {@code ConfinementCadence} puts each body on.
     */
    public static Confinement surveyFrom(ServerLevel level, BlockPos start, MoveCapabilities body) {
        BlockPos[] box = boxAround(level, start, start, SURVEY_MARGIN);
        WorldSnapshot snapshot = WorldSnapshot.lazy(level, box[0], box[1]);
        BlockPos afloat = surfaceStart(snapshot, start, body);
        return Pathfinder.survey(snapshot, PathRequest.of(afloat.getX(), afloat.getY(),
                afloat.getZ(), afloat.getX(), afloat.getY(), afloat.getZ(), body));
    }

    /**
     * How far the survey reaches when stranded walks say the body may be shut in: far enough to
     * see a crevice or a cave pocket whole, which {@link #SURVEY_MARGIN} does not. A one-wide
     * crevice and the cave under it, 258 cells reaching 35 blocks from where the body stood and 13
     * below it, read as open at 32 and sealed from 40 (2026-09-26). No wider than one long walk's
     * capture: {@link #MAX_REACH} plus {@link #HORIZONTAL_MARGIN} each side.
     */
    private static final int WIDE_SURVEY_MARGIN = 64;
    /**
     * Its reach up and down. Not the routing band: that sits at most 14 under the body and the
     * crevice's cave went 13 down, inside the five cells of the rim that void a proof.
     */
    private static final int WIDE_SURVEY_DEPTH = 32;
    /**
     * How far, in walk cost, {@link #surveyWide} counts room to stand — about a long walk on flat
     * ground; a stroke costs two and a half.
     */
    private static final double ROOM_WALK = 32.0;
    /**
     * How many cells to stand in within {@link #ROOM_WALK} are room enough. Measured on the forest
     * (2026-09-28): the ledge over the channel had 3, the ground above it 631, open forest 1,390.
     */
    private static final int ROOM_ENOUGH = 64;

    /**
     * {@link #surveyFrom} over the wide box, and then whether there is room to stand within a walk —
     * only on evidence. Shut in by either: no way out at all, or none short of a long swim.
     */
    public static Confinement surveyWide(ServerLevel level, BlockPos start, MoveCapabilities body) {
        WorldSnapshot snapshot = WorldSnapshot.lazy(level,
                start.offset(-WIDE_SURVEY_MARGIN, -WIDE_SURVEY_DEPTH, -WIDE_SURVEY_MARGIN),
                start.offset(WIDE_SURVEY_MARGIN, WIDE_SURVEY_DEPTH, WIDE_SURVEY_MARGIN));
        BlockPos afloat = surfaceStart(snapshot, start, body);
        PathRequest request = PathRequest.of(afloat.getX(), afloat.getY(), afloat.getZ(),
                afloat.getX(), afloat.getY(), afloat.getZ(), body);
        Confinement sealed = Pathfinder.survey(snapshot, request);
        return sealed.sealed() ? sealed : Pathfinder.room(snapshot, request, ROOM_WALK, ROOM_ENOUGH);
    }

    /**
     * How near the rim of a walk's capture a body may stand and still be judged on it: the
     * survey's own reach from the rim (~5 for a Person) plus a few cells of room to be proved in.
     */
    private static final int ENCLOSURE_RIM = 8;
    /** The budget for each of the enclosure check's floods — a room is a few hundred cells. */
    private static final int ENCLOSURE_NODES = 8192;

    /** An enclosure check under way, and the grid it searches: its doors are what go stale. */
    public record EnclosureDispatch(CompletableFuture<Enclosure> result, NavGrid grid) {}

    /**
     * How the space around {@code feet} opens (shelter spec), searched on a worker like a route.
     *
     * <p>{@code walked} is the capture of the walk that just ended, if there was one. It is
     * searched when it covers the body with {@link #ENCLOSURE_RIM} to spare, its doors read again
     * first, which spares the tick a capture; otherwise a fresh box of
     * {@link Knob#ENCLOSURE_REACH} is baked here.
     *
     * @param inTick answer before returning, on this thread — for a body just loaded, whose answer
     *               must be there on its first tick as it was on its last
     */
    public static EnclosureDispatch enclosure(ServerLevel level, BlockPos feet,
            MoveCapabilities body, @Nullable NavGrid walked, boolean inTick) {
        WorldSnapshot snapshot;
        NavGrid grid;
        if (walked instanceof WorldSnapshot planned
                && planned.covers(feet.offset(-ENCLOSURE_RIM, -UP_MARGIN, -ENCLOSURE_RIM),
                        feet.offset(ENCLOSURE_RIM, UP_MARGIN, ENCLOSURE_RIM))) {
            snapshot = planned;
            grid = LiveDoors.over(planned, level);
        } else {
            int reach = Config.get().i(Knob.ENCLOSURE_REACH);
            BlockPos[] box = boxAround(level, feet, feet, reach);
            snapshot = WorldSnapshot.capture(level, box[0], box[1]);
            grid = snapshot;
        }
        BlockPos from = surfaceStart(snapshot, feet, body);
        long now = level.getGameTime();
        if (inTick || inThread()) {
            return new EnclosureDispatch(CompletableFuture.completedFuture(EnclosureCheck.run(
                    grid, from.getX(), from.getY(), from.getZ(), body, ENCLOSURE_NODES, now)), grid);
        }
        NavGrid searched = grid;
        return new EnclosureDispatch(CompletableFuture.supplyAsync(() -> EnclosureCheck.run(
                searched, from.getX(), from.getY(), from.getZ(), body, ENCLOSURE_NODES, now),
                executor()), grid);
    }

    /**
     * Dev-phase trace; the log prefix doubles as proof the search left the server thread.
     *
     * <p>Stamped with {@code who} to tell one agent re-asking a doomed question from many asking
     * once. The handle is the 8-character short id the commands print <em>and accept</em>, so a
     * hot line pastes straight into {@code select}.
     */
    private static void trace(String who, BlockPos start, BlockPos goal, Path path) {
        AnimaMod.LOGGER.info("path [{}] {} -> {}: {} waypoints{} [reached {} cells{}]",
                who, start.toShortString(), goal.toShortString(),
                path.waypoints().size(), path.reachedGoal() ? "" : " (partial)",
                path.reachableCells(), path.sealed() ? ", SEALED" : "");
    }

    private static PathRequest buildRequest(WorldSnapshot snapshot, BlockPos start, BlockPos goal,
            MoveCapabilities body, DangerField danger, @Nullable AgentId who,
            SetbackField setbacks) {
        BlockPos afloat = surfaceStart(snapshot, start, body);
        BlockPos grounded = new BlockPos(goal.getX(),
                GoalCell.groundY(snapshot, goal.getX(), goal.getY(), goal.getZ(), body), goal.getZ());
        return PathRequest.of(afloat.getX(), afloat.getY(), afloat.getZ(),
                        grounded.getX(), grounded.getY(), grounded.getZ(), body, danger)
                .varying(variety(who))
                .avoiding(setbacks);
    }

    /**
     * Which line this agent walks when several will do — see {@link PathRequest#varying}. Half of
     * an {@link AgentId}'s random bits: a fine seed and a <em>permanent</em> one, so re-planning
     * mid-trip never sends a body back the way it came, even across a restart. An identity that
     * has not resolved yet (an entity's first tick or two) gets seed 0; no route to keep yet.
     */
    private static long variety(@Nullable AgentId who) {
        return who == null ? 0L : who.value().getLeastSignificantBits();
    }

    /**
     * Lifts a start cell that is under the water to the surface of its own column — but
     * <b>only when the body has no node where it is</b>.
     *
     * <p>A submerged body the search cannot expand from stalls silently and for good: every
     * request returns "0 waypoints (partial)". Not a corner case — a body treading deep water
     * floats with its eyes near the waterline, so its feet are a block and a half down and the
     * cell it occupies is submerged.
     *
     * <p>Lifting a body that can plan from where it is is wrong: a settler mid-dive re-pathed from
     * the top of the water and swam back up, surfacing a block short of the floor. One under a
     * ceiling of water with no surface above keeps its cell and stays unable to plan — that is the
     * missing capability (submerged routing), not something to paper over.
     */
    private static BlockPos surfaceStart(WorldSnapshot snapshot, BlockPos start,
            MoveCapabilities body) {
        if (!body.canSwim() || snapshot.cell(start.getX(), start.getY(), start.getZ()) != CellType.WATER) {
            return start;
        }
        if (fitsSubmerged(snapshot, start, body)) {
            return start; // a real node down here: plan from where the body is
        }
        int x = start.getX();
        int z = start.getZ();
        for (int y = start.getY(); y < start.getY() + START_RISE_SCAN; y++) {
            if (snapshot.cell(x, y, z) == CellType.WATER
                    && snapshot.cell(x, y + 1, z) == CellType.PASSABLE) {
                return new BlockPos(x, y, z);
            }
        }
        return start;
    }

    /** The capture a search from {@code start} to {@code goal} plans on — for a restored walk. */
    public static WorldSnapshot snapshotFor(ServerLevel level, BlockPos start, BlockPos goal) {
        return sharedSnapshot(level, start, goal);
    }

    private static WorldSnapshot sharedSnapshot(ServerLevel level, BlockPos start, BlockPos goal) {
        return snapshotAround(level, start, goal, HORIZONTAL_MARGIN);
    }

    /**
     * The box a search from {@code start} towards {@code goal} captures, as its min and max corners.
     *
     * @param margin how far past the endpoints to capture — routing wants room for a detour, a
     *               survey wants only enough to tell a prison from a field.
     */
    private static BlockPos[] boxAround(ServerLevel level, BlockPos start, BlockPos goal,
            int margin) {
        int gx = Mth.clamp(goal.getX(), start.getX() - MAX_REACH, start.getX() + MAX_REACH);
        int gy = goal.getY();
        int gz = Mth.clamp(goal.getZ(), start.getZ() - MAX_REACH, start.getZ() + MAX_REACH);
        int minX = Math.min(start.getX(), gx) - margin;
        int minZ = Math.min(start.getZ(), gz) - margin;
        int maxX = Math.max(start.getX(), gx) + margin;
        int maxZ = Math.max(start.getZ(), gz) + margin;
        // The vertical extent is a question about the GROUND, not about the two cells being
        // joined: sized off the endpoints alone, a saddle above both read as sky the search could
        // not enter — a route peaking at 97 between ends at 86 and 90, against a ceiling of 96.
        // See TerrainProfile: heightmaps only, no block reads and no chunk loads, and the cells it
        // adds are mostly sky that bake() fills without reading anything.
        TerrainProfile.Band band = TerrainProfile.widen(
                new TerrainProfile.Band(
                        Math.min(start.getY(), gy) - DOWN_MARGIN,
                        Math.max(start.getY(), gy) + UP_MARGIN),
                TerrainProfile.terrain(level, minX, minZ, maxX, maxZ));
        return new BlockPos[] {new BlockPos(minX, band.low(), minZ),
                new BlockPos(maxX, band.high(), maxZ)};
    }

    /**
     * A full capture of {@link #boxAround}, left in the one-deep cache for the next request this
     * tick. The survey keeps out of it, reading its own small box {@link WorldSnapshot#lazy lazily}.
     */
    private static WorldSnapshot snapshotAround(ServerLevel level, BlockPos start, BlockPos goal,
            int margin) {
        BlockPos[] box = boxAround(level, start, goal, margin);
        BlockPos min = box[0];
        BlockPos max = box[1];

        CachedSnapshot cached = snapshots.get(level);
        if (cached != null && cached.gameTime() == level.getGameTime() && cached.snapshot().covers(min, max)) {
            return cached.snapshot();
        }
        WorldSnapshot fresh = WorldSnapshot.capture(level, min, max);
        snapshots.put(level, new CachedSnapshot(fresh, level.getGameTime()));
        return fresh;
    }

    /**
     * Clicks and commands rarely name a standable cell (a block face, a spot mid-air): walks the
     * goal down to the first cell with ground under it and room to stand. Finding none, the goal
     * stands and the search yields its best partial toward it.
     *
     * <p>For a swimmer a goal over open water settles at the <em>surface</em> (first water cell
     * with air above), not the lakebed: surface crossing cannot reach the bed.
     */
    /** Whether this body fits in the water at {@code cell} — the search's own submerged node test. */
    private static boolean fitsSubmerged(WorldSnapshot snapshot, BlockPos cell, MoveCapabilities body) {
        for (int i = 0; i < body.clearCells(); i++) {
            CellType at = snapshot.cell(cell.getX(), cell.getY() + i, cell.getZ());
            if (at != CellType.WATER && at != CellType.PASSABLE) {
                return false;
            }
        }
        return true;
    }

    private static ExecutorService executor() {
        if (executor == null) {
            ThreadFactory factory = new ThreadFactory() {
                private final AtomicInteger id = new AtomicInteger();

                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "anima-pathfinder-" + this.id.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                }
            };
            executor = Executors.newFixedThreadPool(WORKER_THREADS, factory);
        }
        return executor;
    }
}
