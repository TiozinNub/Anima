package dev.luizloyola.anima.mod.nav;

import dev.luizloyola.anima.compat.nav.TerrainProfile;
import dev.luizloyola.anima.compat.nav.WorldSnapshot;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Confinement;
import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.core.brain.sense.SetbackField;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.CellType;
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
     * proves an enclosure up to about 11×11 and calls anything larger open. That is the trade —
     * the box is also what the survey costs, since a free body expands until it touches the rim.
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
    /** How far below a clicked goal to look for actual ground (clicks land on faces, not floors). */
    private static final int GOAL_DROP_SCAN = 12;
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
        WorldSnapshot snapshot = sharedSnapshot(level, start, goal);
        PathRequest pathRequest = buildRequest(snapshot, start, goal, body, danger, who, setbacks);
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
        WorldSnapshot snapshot = sharedSnapshot(level, start, goal);
        Path path = Pathfinder.find(snapshot,
                buildRequest(snapshot, start, goal, body, danger, who, setbacks));
        return new Dispatched(CompletableFuture.completedFuture(path), snapshot);
    }

    /**
     * Whether this body can leave where it stands. Snapshot and search on the SERVER thread: the
     * asker is a drive reading a percept mid-tick, with nowhere to put a future.
     *
     * <p>Affordable only because {@code AgentPercepts.confinement} gates it behind a recent
     * stranded report and caches the answer; a shut-in body's region is a handful of cells, so the
     * expansion ends at once and the capture reuses the tick's shared snapshot.
     */
    public static Confinement surveyFrom(ServerLevel level, BlockPos start, MoveCapabilities body) {
        WorldSnapshot snapshot = snapshotAround(level, start, start, SURVEY_MARGIN, false);
        BlockPos afloat = surfaceStart(snapshot, start, body);
        return Pathfinder.survey(snapshot, PathRequest.of(afloat.getX(), afloat.getY(),
                afloat.getZ(), afloat.getX(), afloat.getY(), afloat.getZ(), body));
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
        BlockPos grounded = groundGoal(snapshot, goal, body.canSwim());
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

    private static WorldSnapshot sharedSnapshot(ServerLevel level, BlockPos start, BlockPos goal) {
        return snapshotAround(level, start, goal, HORIZONTAL_MARGIN, true);
    }

    /**
     * @param margin how far past the endpoints to capture — routing wants room for a detour, a
     *               survey wants only enough to tell a prison from a field.
     * @param share  whether to leave the capture in the one-deep cache for the next caller. A
     *               survey passes {@code false}: its box is small and its position is its own, so
     *               storing it only evicts the wider capture a routing request would have reused.
     */
    private static WorldSnapshot snapshotAround(ServerLevel level, BlockPos start, BlockPos goal,
            int margin, boolean share) {
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
        BlockPos min = new BlockPos(minX, band.low(), minZ);
        BlockPos max = new BlockPos(maxX, band.high(), maxZ);

        CachedSnapshot cached = snapshots.get(level);
        if (cached != null && cached.gameTime() == level.getGameTime() && cached.snapshot().covers(min, max)) {
            return cached.snapshot();
        }
        WorldSnapshot fresh = WorldSnapshot.capture(level, min, max);
        if (share) {
            snapshots.put(level, new CachedSnapshot(fresh, level.getGameTime()));
        }
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

    private static BlockPos groundGoal(WorldSnapshot snapshot, BlockPos goal, boolean canSwim) {
        int x = goal.getX();
        int z = goal.getZ();
        for (int y = goal.getY(); y > goal.getY() - GOAL_DROP_SCAN; y--) {
            if (canSwim && snapshot.cell(x, y, z) == CellType.WATER
                    && snapshot.cell(x, y + 1, z) == CellType.PASSABLE) {
                return new BlockPos(x, y, z); // the water surface — a swimmer floats here
            }
            CellType below = snapshot.cell(x, y - 1, z);
            if (below == CellType.GROUND
                    && snapshot.cell(x, y, z) == CellType.PASSABLE
                    && snapshot.cell(x, y + 1, z) == CellType.PASSABLE) {
                return new BlockPos(x, y, z);
            }
            if (below == CellType.OBSTACLE || below == CellType.DANGER) {
                break; // solid-but-unstandable or harmful floor: nothing walkable further down
            }
        }
        return goal;
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
