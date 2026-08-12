package dev.luizloyola.anima.mod.nav;

import dev.luizloyola.anima.compat.nav.WorldSnapshot;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.brain.sense.Confinement;
import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.core.brain.sense.SetbackField;
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
 * Turns "this AgentBody wants to reach that block" into a {@link Path}. The split is rigid (see the
 * pathfinder design doc): everything reading the live world — snapshot capture, goal grounding —
 * runs on the server thread in {@link #request}/{@link #computeNow}, while the A* sees only the
 * immutable {@link WorldSnapshot}.
 *
 * <p>{@link #request}'s future is polled by the {@link Navigator} from its own tick, so a path is
 * only ever <em>applied</em> on the main thread; {@link #computeNow} is the same pipeline
 * synchronously, the debugging escape hatch.
 */
public final class PathfinderService {
    private PathfinderService() {}

    /** How far past the start∪goal box the snapshot extends, so detours have room to route. */
    private static final int HORIZONTAL_MARGIN = 16;
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
        WorldSnapshot snapshot = sharedSnapshot(level, start, start);
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
        int gx = Mth.clamp(goal.getX(), start.getX() - MAX_REACH, start.getX() + MAX_REACH);
        int gy = goal.getY();
        int gz = Mth.clamp(goal.getZ(), start.getZ() - MAX_REACH, start.getZ() + MAX_REACH);
        BlockPos min = new BlockPos(
                Math.min(start.getX(), gx) - HORIZONTAL_MARGIN,
                Math.min(start.getY(), gy) - DOWN_MARGIN,
                Math.min(start.getZ(), gz) - HORIZONTAL_MARGIN);
        BlockPos max = new BlockPos(
                Math.max(start.getX(), gx) + HORIZONTAL_MARGIN,
                Math.max(start.getY(), gy) + UP_MARGIN,
                Math.max(start.getZ(), gz) + HORIZONTAL_MARGIN);

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
