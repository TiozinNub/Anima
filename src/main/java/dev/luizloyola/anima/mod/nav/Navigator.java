package dev.luizloyola.anima.mod.nav;

import dev.luizloyola.anima.compat.nav.WorldSnapshot;
import dev.luizloyola.anima.core.agent.AgentId;
import dev.luizloyola.anima.core.agent.need.Gauge;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.brain.act.MoveFailure;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.mod.brain.DangerFields;
import dev.luizloyola.anima.core.nav.CellNeed;
import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.CrowdSteering;
import dev.luizloyola.anima.core.nav.Gait;
import dev.luizloyola.anima.core.nav.MoveType;
import dev.luizloyola.anima.core.nav.NavGrid;
import dev.luizloyola.anima.core.nav.NavGrids;
import dev.luizloyola.anima.core.nav.Path;
import dev.luizloyola.anima.core.nav.PathIntegrity;
import dev.luizloyola.anima.core.nav.Waypoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Per-{@link AgentBody} navigation state machine: asks {@link PathfinderService} for a
 * {@link Path} and walks it, one steering decision per server tick.
 * {@code IDLE → PATHING → FOLLOWING → ARRIVED}, {@code FAILED} when unreachable; a stall or a
 * short partial path re-paths, spending one of a small retry budget.
 *
 * <p>The search runs off-thread; its result is read only from {@link #tick()}, inside
 * {@code serverAiStep}, so paths apply on the main thread.
 */
public final class Navigator {
    public enum State { IDLE, PATHING, FOLLOWING, ARRIVED, FAILED }

    /**
     * Horizontal arrival radius for the final waypoint: under a block, so the body settles on the
     * goal cell rather than the next, and over one tick's ~0.22-block step so it never orbits.
     */
    private static final double FINAL_RADIUS = 0.55;
    /** Intermediate waypoints are corners, not destinations — cut them tighter so turns hug the path. */
    private static final double WAYPOINT_RADIUS = 0.4;
    /**
     * Careful mode, active while the ground being crossed borders a deep drop / lava / water
     * ({@link NavGrids#isNearDeepDrop}): forward input throttled to about half walk speed and
     * waypoints hit tighter. JUMP and LEAP are exempt — a leap's takeoff is edge-adjacent by
     * definition and needs full speed.
     */
    private static final float CAREFUL_THROTTLE = 0.45F;
    /**
     * Forward input for a {@link Gait#STROLL} on open ground: ~55% walk speed (≈2.4 b/s).
     * Above the careful throttle — careful is a SAFETY slowdown and keeps precedence
     * via its earlier branch. LEAP legs are exempt (the run-up needs full speed).
     */
    private static final float STROLL_THROTTLE = 0.55F;
    /**
     * Forward (air-control) input while airborne in a 2-block-gap leap. Full forward walks the arc
     * onto the FAR rim of a 1-wide landing pillar, and in a chain each leap overshoots more until
     * one misses; easing it lands them mid-pillar without touching takeoff speed. Leaps hold their
     * heading in flight, so this costs no steering.
     */
    private static final float LEAP_AIR_THROTTLE = 0.4F;
    private static final double CAREFUL_RADIUS = 0.25;
    /** Final-waypoint radius when the goal cell borders a drop — see the radius selection. */
    private static final double CAREFUL_FINAL_RADIUS = 0.35;
    /**
     * Residual slide (blocks/tick, squared) below which a landing counts as settled: 0.02/tick.
     * Friction leaves ~1.2× the current speed as drift, so creep after arrival is ~0.025 blocks;
     * the first cut, 0.1/tick, let ~0.13 through and the body glided onto the lip.
     */
    private static final double SETTLED_SPEED_SQ = 0.0004;
    /**
     * How close (horizontally) a JUMP waypoint must be before the jump is pressed. It becomes
     * current up to ~1.4 away; 1.0 waits until we are inside the takeoff cell (adjacent centres are
     * 1.0 apart), so the body doesn't leap early and land short.
     */
    private static final double JUMP_RANGE = 1.0;
    /**
     * How many waypoints ahead the passed-node check may claim. Overshoots (a jump carries
     * ~1.5 blocks, knockback a couple more) land at most a few cells down the path, so a short
     * window catches every real case in O(1) instead of scanning the whole path each tick.
     */
    private static final int SKIP_LOOKAHEAD = 8;
    /**
     * Grounded distance from the current waypoint beyond which we are off the path and re-path from
     * where we stand. Must stay ABOVE the engine's longest stride — a (3,3) step puts the next
     * waypoint 3√2 ≈ 4.25 away, plus the 0.4 advance radius.
     */
    /**
     * How far below the feet {@link #bearsWeight} looks for the block holding them up. A hair: the
     * body already rests on it, and deeper would find the storey below through a gap.
     */
    private static final double SUPPORT_PROBE = 1.0E-3;
    /**
     * Shrink applied to a footprint before its columns are read off, so a box whose edge lands
     * exactly on a cell boundary does not claim the cell beyond it. Smaller than any position the
     * engine produces is meaningful to, and larger than the float noise that puts an edge at
     * {@code x.9999999} instead of {@code y.0}.
     */
    private static final double FOOTPRINT_SLACK = 1.0E-4;
    private static final double STRAY_HORIZONTAL = 5.0;
    private static final double STRAY_VERTICAL = 1.5;
    /**
     * Vertical slack for matching a body to a {@link MoveType#SWIM} waypoint. A floating body bobs
     * around the surface cell, so a footed move's tight half-block band drops the waypoint out of
     * range on the down-beat and stalls the advance. This band spans the whole bob.
     */
    private static final double SWIM_BAND = 1.5;
    /** Ticks without reaching the next waypoint (one cell away!) before declaring ourselves stuck. */
    private static final int STUCK_LIMIT = 60;
    /**
     * The fast stuck path: consecutive grounded ticks driven forward but barely moving
     * (&lt; {@link #NO_MOVE_EPSILON} vs ~0.22 expected at walk) — wedged on something the snapshot
     * didn't know. Re-paths in ~0.75s instead of the 60-tick timer, yet outlasts the 1–2 ticks
     * pressed against a ledge before a jump.
     */
    private static final int NO_MOVE_LIMIT = 15;
    private static final double NO_MOVE_EPSILON = 0.01;
    /**
     * Ticks of air one submerged cell costs: the breath gauge's ticks converted into the cells the
     * search counts. A stroke is appreciably slower than a walking step.
     */
    private static final double TICKS_PER_SUBMERGED_CELL = 5.0;
    /** Fraction of a lungful a route leaves unspent — see {@link #submergedBudget()}. */
    private static final double BREATH_RESERVE = 0.4;
    /** The budget for a body with no breath gauge: one that does not drown, so nothing to ration. */
    private static final int NO_BREATH_LIMIT = 4096;
    /** Re-path budget per {@link #pathTo} request: stuck or short-of-goal retries, then FAILED. */
    private static final int MAX_REPATHS = 3;
    /**
     * How many waypoints past the one just reached the integrity check re-validates when the
     * follower steps onto a new node, catching terrain edited out from under the plan before the
     * body reaches it. Must exceed the farthest single-tick node skip ({@link #SKIP_LOOKAHEAD}) so
     * a skip never vaults the body past unvalidated cells.
     */
    private static final int INTEGRITY_LOOKAHEAD = 5;
    /**
     * Minimum FOLLOWING ticks between two <em>proactive</em> (terrain-changed) re-paths, so a
     * flickering block (or a fresh plan that is itself invalid) can't re-plan every tick and peg
     * a worker. A spacing dial, not a budget: proactive re-paths never run out.
     */
    private static final int PROACTIVE_REPATH_COOLDOWN = 20;

    /**
     * Debug escape hatch: {@code false} runs the search synchronously on the server thread
     * (identical pipeline, no executor), taking threading out of the picture when chasing a
     * pathfinding bug.
     */
    private static final boolean OFF_THREAD = true;

    private final AgentBody person;
    private State state = State.IDLE;
    private @Nullable BlockPos goal;
    private @Nullable Path path;
    /** Why the last order died; see {@link #failure()}. Cleared by a new order and by a good path. */
    private MoveFailure failure = MoveFailure.NONE;
    /** What the last search said about being shut in; see {@link #sealed()}. */
    private boolean sealed;
    private int reachableCells;
    /** The snapshot the current path was planned over; the follower reads it for edge awareness. */
    private @Nullable NavGrid grid;
    private int index;
    private int stuckTicks;
    private int noMoveTicks;
    /** Consecutive grounded FOLLOWING ticks — bounds the landing-brake window (see tickFollowing). */
    private int groundedTicks;
    /**
     * The waypoint index a leap jump-press last fired for: exactly one press per takeoff. A chained
     * span-3 leap grounds on the far rim of a 1-wide pillar for a tick or two, so the press must
     * fire on the first grounded tick in the band; this guard stops it re-pressing in place on a
     * wide landing. Reset on every new path.
     */
    private int lastLeapPressIndex = -1;
    /** Set by {@link #tickSwim}, read by the {@link Swimmer} — see {@link #waterIntent()}. */
    private WaterIntent waterIntent = WaterIntent.NONE;
    /** Feet height the current water leg is aiming at — see {@link #waterTargetY()}. */
    private double waterTargetY;
    /** Whether the last footed steering tick was in careful mode — see {@link #careful()}. */
    private boolean careful;
    /** The arrival radius the last footed steering tick used — see {@link #arrivalRadius()}. */
    private double arrivalRadius;
    private double lastTickX;
    private double lastTickY;
    private double lastTickZ;
    private int repathsLeft;
    /**
     * Highest waypoint index whose look-ahead window the integrity check has already validated; it
     * re-checks only when the follower advances onto a new node ({@code index} climbs past this).
     * Reset to -1 on every new path so the first FOLLOWING tick validates the opening window.
     */
    private int integrityCheckedIndex = -1;
    /** FOLLOWING ticks left before another proactive re-path may fire (see {@link #PROACTIVE_REPATH_COOLDOWN}). */
    private int proactiveRepathCooldown;
    /**
     * Requested pace for the current order, set by {@link #pathTo(BlockPos, Gait)}: SPRINT (flee)
     * on open, safe stretches, STROLL (wander) at {@link #STROLL_THROTTLE}. Terrain overrides mood
     * both ways — careful mode still wins over SPRINT, so a fleeing body slows at cliff edges, and
     * a LEAP leg ignores STROLL. Two SPRINT consequences are intended: the food&le;6 gate in
     * {@link AgentBody#driveSprint} degrades an exhausted flee to a walk, and sprinting banks
     * exhaustion in {@code tickNeeds}. Reset to WALK in {@link #stop()}.
     */
    private Gait gait = Gait.WALK;
    private @Nullable CompletableFuture<Path> pending;

    public Navigator(AgentBody person) {
        this.person = person;
    }

    /** Begin navigating toward {@code goal}, replacing any navigation already in progress. */
    public void pathTo(BlockPos goal) {
        pathTo(goal, Gait.WALK);
    }

    /**
     * As {@link #pathTo(BlockPos)}, with the requested pace (see {@link #gait}). Pathing and
     * following are otherwise identical — on flat, safe ground the only per-tick difference is the
     * sprint flag or the forward throttle.
     */
    public void pathTo(BlockPos goal, Gait gait) {
        stop(); // resets this.gait, so set it after
        this.gait = gait;
        this.goal = goal;
        this.repathsLeft = MAX_REPATHS;
        requestPath();
    }

    /** Abandon the current goal and hold position. */
    public void stop() {
        if (this.pending != null) {
            this.pending.cancel(false);
            this.pending = null;
        }
        this.goal = null;
        this.path = null;
        this.grid = null;
        this.index = 0;
        this.integrityCheckedIndex = -1;
        this.proactiveRepathCooldown = 0;
        this.gait = Gait.WALK;
        this.state = State.IDLE;
        this.failure = MoveFailure.NONE;
        this.person.stopMoving();
    }

    public State state() {
        return this.state;
    }

    /**
     * Why the last order died — {@link MoveFailure#NONE} unless {@link #state()} is FAILED. Set
     * where the cause is known, not inferred from the counters afterwards: the retries in between
     * have reset them by the time the machine reaches FAILED.
     */
    public MoveFailure failure() {
        return this.failure;
    }

    /**
     * Whether the last search <em>proved</em> this body cannot leave the region it stands in: it
     * ran out of anywhere to go, and only the world stopped it (see {@code Pathfinder.sealedIn}).
     * Stronger than {@link MoveFailure#STRANDED}. That is what the legs observed about one order.
     *
     * <p>Not persisted: the next search retakes it within a tick or two of a reload. That changes
     * the moment anything ACTS on it rather than printing it.
     */
    public boolean sealed() {
        return this.sealed;
    }

    /**
     * How many cells the body could reach. A statement about the whole of its world only while
     * {@link #sealed()} — otherwise merely how far the last search got.
     */
    public int reachableCells() {
        return this.reachableCells;
    }

    /** The current goal cell, or {@code null} when idle. Survives ARRIVED/FAILED for inspection. */
    public @Nullable BlockPos goal() {
        return this.goal;
    }

    /**
     * The path being followed, or {@code null} — read-only, for the debug view. {@link Path} and
     * its waypoints are immutable, but the reference is swapped wholesale on every re-path:
     * callers must re-read it, not hold it.
     *
     * <p>Server-thread only, writer included (see {@link PathfinderService}).
     */
    public @Nullable Path path() {
        return this.path;
    }

    /** Which waypoint of {@link #path()} is being walked toward. */
    public int pathIndex() {
        return this.index;
    }

    /** What the follower is asking of the water this tick — see {@link #waterIntent()}. */
    public enum WaterIntent {
        NONE,
        /** Travelling across the surface of water, head in the air. */
        CROSS,
        /**
         * Travelling through water with the head under it. Separate from {@link #CROSS} because
         * here the body must not float: buoyancy would undo the depth the route chose.
         */
        CROSS_UNDER,
        /** Going down a water column, which is the one water move a body must be pushed into. */
        DIVE,
        /** Going up one, toward the air. */
        SURFACE,
        /** Heading for solid ground — the leg that has to gain height to finish. */
        EXIT
    }

    /**
     * What the last {@link #tick()} asked of the water — the follower's own verdict, so one place
     * decides what a water leg is ({@link #tickSwim} sets it) and no copy of the condition drifts.
     * Read by {@link Swimmer}: this is about the ROUTE and is the same for a pet as for a person;
     * what a body presses is not.
     *
     * <p>{@link WaterIntent#EXIT} is separate because getting out is the only water move that must
     * gain height — while nothing said so, that lift came by accident and broke when its source was
     * fixed. Cleared at the top of every tick, so no branch leaves a stale intent.
     */
    public WaterIntent waterIntent() {
        return this.waterIntent;
    }

    /**
     * The feet height the current water leg is aiming at — meaningless unless
     * {@link #waterIntent()} is saying something. Depth is the one thing a swimming body cannot
     * work out for itself: its reflexes answer "up", which in a flooded tunnel is the roof. The
     * route holds the vertical while it steers, the reflex takes it back when it stops.
     */
    public double waterTargetY() {
        return this.waterTargetY;
    }

    /**
     * Whether the last footed steering tick was in careful mode: the ground crossed borders a deep
     * drop, so forward input is throttled and the waypoint radius tightened.
     *
     * <p>Reported rather than re-derived, like {@link #waterIntent()} — {@link #isCareful} folds
     * several conditions together, a leap landing among them. Cleared at the top of every
     * {@link #tick()}, so a tick that never reached the footed branch reports {@code false} and a
     * zero {@link #arrivalRadius()}.
     */
    public boolean careful() {
        return this.careful;
    }

    /**
     * How close the last footed steering tick had to get to count as arrived — one of the four
     * radii, by whether this is the final waypoint and whether {@link #careful()} is on; zero when
     * the last tick decided nothing. Explains both stopping short of a goal (a wide radius on an
     * intermediate corner) and refusing to stop at all (a careful 0.25 next to a drop).
     */
    public double arrivalRadius() {
        return this.arrivalRadius;
    }

    /** Ticks spent on the current waypoint, against {@link #stuckLimit()} — the slow stuck path. */
    public int stuckTicks() {
        return this.stuckTicks;
    }

    /** Ticks on one waypoint before the follower declares itself stuck and re-paths. */
    public int stuckLimit() {
        return STUCK_LIMIT;
    }

    /** Consecutive driven-but-motionless ticks, against {@link #noMoveLimit()} — the fast one. */
    public int noMoveTicks() {
        return this.noMoveTicks;
    }

    /** Driven-but-motionless ticks before the follower gives up on the current plan. */
    public int noMoveLimit() {
        return NO_MOVE_LIMIT;
    }

    /**
     * Re-paths this order has left before it FAILS, out of {@link #maxRepaths()}. The retry budget
     * only — proactive (terrain-changed) re-paths do not spend it, so a body re-planning around
     * somebody's building work shows a full budget while one fighting the same ledge drains.
     */
    public int repathsLeft() {
        return this.repathsLeft;
    }

    /** The retry budget one {@link #pathTo} order starts with. */
    public int maxRepaths() {
        return MAX_REPATHS;
    }

    /** One-line progress summary for the debug command. */
    public String describe() {
        StringBuilder text = new StringBuilder(this.state.toString());
        if (this.goal != null) {
            text.append(" -> ").append(this.goal.toShortString());
        }
        if (this.state == State.FOLLOWING && this.path != null) {
            text.append(" (waypoint ").append(this.index + 1).append('/')
                    .append(this.path.waypoints().size())
                    .append(this.path.reachedGoal() ? ")" : ", partial)");
        }
        // FAILED alone sent people to the journal for a line already knowable here.
        if (this.state == State.FAILED && this.failure != MoveFailure.NONE) {
            text.append(" (").append(this.failure.describe()).append(')');
        }
        // Printed whatever the state: a fact about the terrain, not about the order — a body that
        // just walked to the far corner of its cell is still shut in.
        if (this.sealed) {
            text.append(" [sealed in ").append(this.reachableCells).append(" cells]");
        }
        return text.toString();
    }

    /**
     * One tick of navigation, driven from {@link AgentBody#serverAiStep()}. Exactly one movement
     * decision leaves here per tick; in every state but FOLLOWING that decision is "stand still",
     * so a stopped AgentBody never coasts on stale input.
     */
    public void tick() {
        this.waterIntent = WaterIntent.NONE; // set again only by the branch that swims, below
        // A tick that never reaches the footed branch must report deciding nothing, not the last
        // tick's decision. See careful() / arrivalRadius().
        this.careful = false;
        this.arrivalRadius = 0.0;
        switch (this.state) {
            case PATHING -> tickPathing();
            case FOLLOWING -> tickFollowing();
            default -> this.person.stopMoving();
        }
    }

    /** Fires the (off-thread) path computation for the current goal. */
    private void requestPath() {
        this.path = null;
        this.index = 0;
        this.integrityCheckedIndex = -1;
        this.stuckTicks = 0;
        this.noMoveTicks = 0;
        this.lastLeapPressIndex = -1;
        this.state = State.PATHING;
        // Read on the server thread: the search seeds and logs from an id and must never reach back
        // into a body from a worker. Null until roughly the body's first tick, inside which a path
        // can still be requested.
        AgentId who = this.person.agentId();
        BlockPos start = startCell();
        PathfinderService.Dispatched dispatched = OFF_THREAD
                ? PathfinderService.request(level(), who, start, this.goal,
                        capabilities(), DangerFields.of(this.person))
                : PathfinderService.computeNow(level(), who, start, this.goal,
                        capabilities(), DangerFields.of(this.person));
        this.grid = dispatched.snapshot();
        this.pending = dispatched.result();
    }

    /**
     * The cell to plan from: the column actually holding this body up.
     *
     * <p>{@code blockPosition()} is the column the CENTRE is over, and a 0.6-wide body straddles a
     * boundary within 0.3 of one, so at a rim or a lip its weight rests on a column the centre
     * never touches. That cell is an <b>orphan</b> with no node of its own: over the test pool the
     * route from the rim to the water below planned <i>walk east onto the deck, then swim back
     * west</i>.
     *
     * <p>So of the columns the footprint overlaps, take the first with something solid immediately
     * under that part of the box; the centre column is tried first. Airborne or afloat it is the
     * right answer anyway, and {@code PathfinderService.surfaceStart} still applies on top.
     */
    private BlockPos startCell() {
        BlockPos feet = this.person.blockPosition();
        LivingEntity entity = this.person.entity();
        if (!this.person.onGround()) {
            return feet;
        }
        AABB box = entity.getBoundingBox();
        for (int[] column : columnsUnder(box)) {
            if (bearsWeight(entity, box, column[0], column[2])) {
                return column[0] == feet.getX() && column[2] == feet.getZ()
                        ? feet // the centre column really is bearing it: the usual answer, unchanged
                        : new BlockPos(column[0], feet.getY(), column[2]);
            }
        }
        return feet; // grounded on nothing this can name (an entity, a shape we mis-clip): leave it
    }

    /**
     * The columns a standing body's footprint overlaps, nearest its centre first — one, two or
     * four, since a 0.6-wide box spans at most two cells per axis. Entries are
     * {@code {x, unused, z}}, readable with position indices.
     *
     * <p>Deflated first, so a box whose edge lands on a cell boundary claims the cell it is IN, not
     * the one it touches — the opposite of {@code AgentPercepts.cellsTouchedBy}, because this list
     * decides where the body <em>is</em>.
     */
    static List<int[]> columnsUnder(AABB box) {
        AABB inner = box.deflate(FOOTPRINT_SLACK, 0.0, FOOTPRINT_SLACK);
        double centerX = (box.minX + box.maxX) / 2.0;
        double centerZ = (box.minZ + box.maxZ) / 2.0;
        List<int[]> columns = new ArrayList<>(4);
        for (int x = Mth.floor(inner.minX); x <= Mth.floor(inner.maxX); x++) {
            for (int z = Mth.floor(inner.minZ); z <= Mth.floor(inner.maxZ); z++) {
                columns.add(new int[]{x, 0, z});
            }
        }
        columns.sort(Comparator.comparingDouble(
                c -> square(c[0] + 0.5 - centerX) + square(c[2] + 0.5 - centerZ)));
        return columns;
    }

    private static double square(double v) {
        return v * v;
    }

    /**
     * Whether the part of {@code box} inside column {@code (x, z)} has something solid directly
     * beneath it — the physical question, asked of the engine, so it cannot drift from what the
     * body is actually resting on the way a second opinion about block shapes would.
     */
    private static boolean bearsWeight(LivingEntity entity, AABB box, int x, int z) {
        AABB under = new AABB(
                Math.max(box.minX, x), box.minY - SUPPORT_PROBE, Math.max(box.minZ, z),
                Math.min(box.maxX, x + 1.0), box.minY, Math.min(box.maxZ, z + 1.0));
        return !entity.level().noCollision(entity, under);
    }

    /** Polls the in-flight request; the future completes on a worker, so only ever read it here. */
    private void tickPathing() {
        this.person.stopMoving();
        if (this.pending == null || !this.pending.isDone()) {
            return;
        }
        CompletableFuture<Path> done = this.pending;
        this.pending = null;
        try {
            acceptPath(done.join());
        } catch (CompletionException | CancellationException e) {
            // Worker died or the server is stopping mid-request; either way there is no path.
            this.state = State.FAILED;
            this.failure = MoveFailure.INTERRUPTED;
            log("failed", "search aborted");
        }
    }

    private void acceptPath(Path result) {
        // Taken from every result, before the branches: a route found is "not sealed" by
        // construction, so assigning unconditionally is how the verdict clears.
        this.sealed = result.sealed();
        this.reachableCells = result.reachableCells();
        if (result.reachedGoal() && result.isEmpty()) {
            this.state = State.ARRIVED; // already standing on the goal
            log("arrived", "already at " + this.goal.toShortString());
            return;
        }
        // A partial path that ends about where we stand is the search saying "no way through":
        // retrying from the same spot would just repeat it, so fail rather than loop.
        if (result.isEmpty() || (!result.reachedGoal() && endsWhereWeStand(result))) {
            this.state = State.FAILED;
            this.failure = MoveFailure.STRANDED;
            log("failed", "no path to " + this.goal.toShortString() + " — "
                    + MoveFailure.STRANDED.describe()
                    + (result.sealed() ? ", sealed in " + result.reachableCells() + " cells" : ""));
            return;
        }
        this.path = result;
        this.index = 0;
        this.stuckTicks = 0;
        this.state = State.FOLLOWING;
        // This field answers "why is it FAILED right now", not "what has gone wrong lately": the
        // retry that produced this path set it on the way through, and leaving it would have a
        // walk in progress carrying a stale reason.
        this.failure = MoveFailure.NONE;
        // PATHFIND log: the "target(10,10,10) - success 10 nodes" line — waypoint count, marked
        // partial when the route only reaches the nearest cell to an otherwise unreachable goal.
        log("target(" + this.goal.toShortString() + ")", "success " + result.waypoints().size()
                + " nodes" + (result.reachedGoal() ? "" : " (partial)"));
    }

    private boolean endsWhereWeStand(Path result) {
        Waypoint last = result.last();
        return this.person.blockPosition().distSqr(new BlockPos(last.x(), last.y(), last.z())) <= 2.0;
    }

    private void tickFollowing() {
        this.groundedTicks = this.person.onGround() ? this.groundedTicks + 1 : 0;
        skipPassedWaypoints();
        advancePassedPlanes(this.person.position());
        // Stepped onto a new node: re-read the completion-critical cells of the next few nodes and
        // re-plan if the world no longer matches. Once per node, not per tick. The cooldown gates
        // the fire rate, not the check.
        if (this.proactiveRepathCooldown > 0) {
            this.proactiveRepathCooldown--;
        } else if (this.index > this.integrityCheckedIndex) {
            this.integrityCheckedIndex = this.index;
            CellNeed changed = pathChangedAhead();
            if (changed != null) {
                proactiveRepath(changed);
                return;
            }
        }
        Waypoint waypoint = this.path.waypoints().get(this.index);
        boolean isLast = this.index == this.path.waypoints().size() - 1;
        Vec3 pos = this.person.position();
        double dx = waypoint.x() + 0.5 - pos.x;
        double dz = waypoint.z() + 0.5 - pos.z;
        double horizontalSq = dx * dx + dz * dz;
        double dy = aboveWaypoint(pos, waypoint);

        // In the water (or about to step in — the last on-shore tick of an entry waypoint), swim
        // physics takes over: none of the grounded edge logic below runs, which is also the
        // careful-mode exemption for entering water.
        //
        // Every water leg, not only the entry one: a DIVE or SURFACE held while still dry means the
        // body is on the lip of the bank, and the answer is to keep steering at the column — the
        // grounded branch called the water below a stray and re-planned the identical route.
        if (isWet() || waypoint.move().inWater()) {
            tickSwim(waypoint, isLast, pos, dx, dz, horizontalSq, dy);
            return;
        }

        // Landing beat: the first grounded ticks after any airborne phase, on ground bordering a
        // drop, brake — input cut, friction kills the arrival momentum so a sprint landing can't
        // skid over the far lip of a 1-wide pillar. Continuous walking near an edge never trips it.
        //
        // EXCEPTION — a leap chain: braking on a takeoff pillar leaves a 1-block runway, far short
        // of the sprint-jump speed a span-3 (2-block-gap) leap needs, so the next leap falls short,
        // strays and re-paths forever. The brake stands down whenever a leap leaves this cell: a
        // leap already past its landing phase (the 1.44 mirrors leapLanding below), or one whose
        // next waypoint leaps.
        boolean leapTakeoff = waypoint.move() == MoveType.LEAP
                && (horizontalSq > 1.44
                        || (this.index + 1 < this.path.waypoints().size()
                                && this.path.waypoints().get(this.index + 1).move() == MoveType.LEAP));
        if (this.groundedTicks >= 1 && this.groundedTicks <= 3 && this.grid != null && !leapTakeoff) {
            BlockPos feet = this.person.blockPosition();
            if (NavGrids.isNearDeepDrop(this.grid, capabilities().maxDrop(),
                    feet.getX(), feet.getY(), feet.getZ())) {
                this.person.stopMoving();
                return;
            }
        }

        // Standing far from the current waypoint means we've left the path (see STRAY_HORIZONTAL),
        // and steering back would be walking blind, so plan fresh from here. Grounded-only —
        // airtime is never a stray. The above-tolerance is asymmetric for DROP waypoints: at the
        // brink the body is legitimately up to maxDrop above its landing, and without that any
        // 2–3-block drop burned every retry.
        double aboveTolerance = waypoint.move() == MoveType.DROP
                ? capabilities().maxDrop() + 0.5
                : STRAY_VERTICAL;
        if (this.person.onGround()
                && (horizontalSq > STRAY_HORIZONTAL * STRAY_HORIZONTAL
                        || dy > aboveTolerance || dy < -STRAY_VERTICAL)) {
            log("stray", "at " + this.person.blockPosition().toShortString());
            retryOrFail(MoveFailure.STRAYED);
            return;
        }

        // Leap phases: full speed through approach and flight; grounded near the landing ("landing
        // phase") the sprint cuts, and if that cell borders another drop careful mode takes the
        // settling ticks so the landing momentum can't carry us over the far edge.
        double leapSpan = waypoint.move() == MoveType.LEAP ? leapSpan(waypoint) : 0.0;
        boolean leapLanding = waypoint.move() == MoveType.LEAP && this.person.onGround()
                && horizontalSq <= 1.44;
        boolean careful = isCareful(waypoint, leapLanding);
        // A careful FINAL waypoint also shrinks the arrival radius: 0.55 from the center of a
        // 1-wide block is its lip — "arrived" next to a drop must mean standing well inside.
        double radius = isLast ? (careful ? CAREFUL_FINAL_RADIUS : FINAL_RADIUS)
                : careful ? CAREFUL_RADIUS : WAYPOINT_RADIUS;
        // Publish both for the debug view. Assigned where they are decided, not recomputed by the
        // reader — see careful().
        this.careful = careful;
        this.arrivalRadius = radius;
        // Arrival is one 3-D distance, horizontal offset and vertical gap against the radius, so
        // "close enough" can't be a block above or below the waypoint. The final waypoint also
        // needs ground under the feet — a leap can sail over the goal, and cutting input airborne
        // lets the landing skid pick the endpoint.
        double vertical = verticalGap(dy);
        if (horizontalSq + vertical * vertical <= radius * radius
                && (!isLast || this.person.onGround())) {
            if (isLast && !isSettled()) {
                // Inside the radius but still sliding: cut input and let friction bleed it off, or
                // the machine freezes while momentum picks the real endpoint. If the slide leaves
                // the radius, careful steering walks us back.
                this.person.stopMoving();
                return;
            }
            advance(isLast);
            return;
        }

        if (++this.stuckTicks > STUCK_LIMIT) {
            // One cell should never take this long: something the snapshot didn't know is in the
            // way (or we fell somewhere unplanned). Re-path from wherever we actually are.
            log("stuck", "waypoint timeout at " + this.person.blockPosition().toShortString());
            retryOrFail(MoveFailure.STALLED);
            return;
        }

        // The fast stuck path (see NO_MOVE_LIMIT): input has been driving but the feet aren't
        // going anywhere. Positions are tick-start values, so the delta measures what the last
        // tick's input actually achieved.
        double movedSq = (pos.x - this.lastTickX) * (pos.x - this.lastTickX)
                + (pos.z - this.lastTickZ) * (pos.z - this.lastTickZ);
        this.lastTickX = pos.x;
        this.lastTickZ = pos.z;
        if (this.person.onGround() && movedSq < NO_MOVE_EPSILON * NO_MOVE_EPSILON) {
            if (++this.noMoveTicks > NO_MOVE_LIMIT) {
                log("stuck", "not moving at " + this.person.blockPosition().toShortString());
                retryOrFail(MoveFailure.WEDGED);
                return;
            }
        } else {
            this.noMoveTicks = 0;
        }

        // Steering aim: the current waypoint, except airborne with it under or BEHIND our motion (a
        // drop drifts a block-plus past the landing cell; a jump arc crosses its centre), where
        // aiming at it would flip the heading 180° and air-control us backwards. Aim at the next
        // waypoint instead; over the final one, hold the heading we have.
        // The crowd is the one place navigation admits other bodies exist (see CrowdSteering),
        // gathered before the aim because it changes both aim and heading. Footed WALK legs only: a
        // JUMP or LEAP is aimed at one ledge, a swerve at a careful cliff edge is worse than a
        // shove, and an airborne body cannot steer.
        List<CrowdSteering.Neighbour> crowd =
                this.person.onGround() && waypoint.move() == MoveType.WALK && !careful
                        ? crowd()
                        : List.of();

        double aimX = dx;
        double aimZ = dz;
        Vec3 velocity = this.person.entity().getDeltaMovement();
        boolean overTarget = !this.person.onGround()
                && (horizontalSq < 0.25 || dx * velocity.x + dz * velocity.z < 0.0);
        boolean hasNext = this.index + 1 < this.path.waypoints().size();
        // A leap in flight is COMMITTED: it aims at its landing and nothing else — aim-next here
        // air-curved chained leaps around corners, scraping (or missing) the landing block.
        boolean committedFlight = waypoint.move() == MoveType.LEAP && !this.person.onGround();
        // Somebody is standing ON the cell we are walking to. Steering around them while still
        // aiming at that cell is an orbit, not a detour: their body holds us a radius outside both
        // the arrival radius and the plane-advance. Aiming at the next waypoint makes the swerve a
        // pass; with no next waypoint, walking up to them (shove and all) is the right answer.
        boolean blockedTarget = hasNext && isOccupied(crowd, waypoint);
        if ((overTarget || blockedTarget) && hasNext && !committedFlight) {
            Waypoint next = this.path.waypoints().get(this.index + 1);
            aimX = next.x() + 0.5 - pos.x;
            aimZ = next.z() + 0.5 - pos.z;
        }
        // Airborne over the FINAL waypoint there is nothing to aim past it — hold the facing and
        // COAST. Driving forward air-accelerated us beyond the goal, forcing a landing past it and
        // a walk back.
        boolean coastToLanding = overTarget && !hasNext;
        // Minecraft yaw: 0 faces +Z (south) and increases clockwise, so facing a point is
        // atan2(dz, dx) in degrees, offset by -90. A committed leap flight past its landing's
        // center holds the takeoff heading (straight flight) instead of swinging around.
        float heading = coastToLanding || (committedFlight && overTarget)
                ? this.person.entity().getYRot()
                : (float) (Mth.atan2(aimZ, aimX) * Mth.RAD_TO_DEG) - 90.0F;
        // Yaw grows clockwise, a turn to the body's right, so CrowdSteering's signed answer adds
        // straight on. No guard is needed against the held-facing branches above: the crowd list is
        // empty unless grounded, and those are all airborne.
        if (!crowd.isEmpty()) {
            heading += (float) Math.toDegrees(CrowdSteering.deflection(
                    pos.x, pos.z, aimX, aimZ, this.person.entity().getBbWidth() / 2.0, crowd));
        }
        // Gait before forward input: driveForward reads the speed attribute, which sprinting
        // modifies. A span-3+ (2-block gap) leap approach and flight sprint — a walking jump falls
        // ~0.4 short into the gap — though sprint slightly overshoots a 1-wide landing pillar, which
        // is why chaining across them is marginal. A DROP into the final waypoint takes the careful
        // speed even on safe ground: at full walk the glide through a 3-block fall is ~1.5 blocks
        // and overshoots, at 0.45 it is ~0.6.
        boolean precisionFinal = isLast && waypoint.move() == MoveType.DROP;
        // Sprint when a 2+ gap leap needs the takeoff speed, or (fleeing) on any open safe
        // stretch. careful and precisionFinal still veto it, so a flee never sprints off a ledge.
        // See the gait field.
        this.person.driveSprint(
                (this.gait == Gait.SPRINT && !careful && !precisionFinal)
                        || (leapSpan >= 3.0 && !leapLanding));
        // Air control eased mid-flight on a 2-gap leap (span 3) so the sprint arc lands mid-pillar
        // instead of skidding onto the far rim (see LEAP_AIR_THROTTLE). A 3-gap leap (span 4) has
        // no distance to spare and keeps full air control; ground ticks always drive at full 1.0.
        boolean leapFlightTrim = committedFlight && leapSpan > 2.5 && leapSpan < 3.5;
        // STROLL eases the open-ground walk to its amble, but never a LEAP leg, and the careful
        // throttle (0.45, below the stroll's 0.55) still wins via its earlier branch.
        boolean stroll = this.gait == Gait.STROLL && waypoint.move() != MoveType.LEAP;
        this.person.driveForward(heading,
                coastToLanding ? 0.0F
                        : careful || precisionFinal ? CAREFUL_THROTTLE
                        : leapFlightTrim ? LEAP_AIR_THROTTLE
                        : stroll ? STROLL_THROTTLE
                        : 1.0F);
        if (waypoint.move() == MoveType.JUMP && dy < -0.5
                && horizontalSq < JUMP_RANGE * JUMP_RANGE && this.person.onGround()) {
            // A full block up needs a real jump (step height covers only 0.6); aiStep's ground check
            // fires it the moment we are grounded and close. The dy guard keeps the press to while
            // we are still BELOW the ledge — a re-press from on top would launch us off the far side.
            this.person.driveJump();
        } else if (waypoint.move() == MoveType.LEAP && this.person.onGround()) {
            // Leap: run at the gap and press jump right at the edge. The takeoff cell's far rim is
            // span−0.5 from the landing centre, so pressing inside span−0.2 launches within a step
            // of it; the FLOOR (span−1.2) confines the press to the takeoff side, since a plain <=
            // stayed true after touchdown and hopped them in place. The once-per-index guard
            // replaces a "wait ≥3 grounded ticks" gate — a chained span-3 leap is grounded on the
            // far rim for barely a tick, so a settle-first gate never fired and the body walked in.
            double press = leapSpan - 0.2;
            double pressFloor = leapSpan - 1.2;
            if (horizontalSq <= press * press && horizontalSq > pressFloor * pressFloor
                    && this.index != this.lastLeapPressIndex) {
                this.person.driveJump();
                this.lastLeapPressIndex = this.index;
            }
        }
    }

    /**
     * One tick of swimming — entering water, crossing at the surface, or climbing out. This steers,
     * and on the climb-out it also <em>lifts</em>; floating in between is the body's own reflex
     * ({@code floatInWater}).
     *
     * <p>The lift is not optional and used to arrive by accident: a float reflex pressing swim-up
     * on every wet tick was also pushing the body over the lip of every bank, and narrowing it to
     * "only when the head is under" left plunge stations E4 and H6 unable to get out of their own
     * pools. Pressed for the whole approach — standable ground is no edge to time against.
     *
     * <p>A SWIM waypoint's arrival is horizontal, a climb-out wants the feet planted and settled.
     * No grounded edge logic applies while floating; {@link #STUCK_LIMIT} and the no-move check
     * (grounded-gated elsewhere, applied here) bound progress.
     */
    private void tickSwim(Waypoint waypoint, boolean isLast, Vec3 pos,
                          double dx, double dz, double horizontalSq, double dy) {
        // The one place that decides what a water leg is — see waterIntent(). A vertical pair
        // reads off the waypoint's own move: they are the two legs whose whole content is a change
        // of depth, and the body cannot infer either from a horizontal heading.
        boolean landTarget = !waypoint.move().inWater(); // else a climb-out step onto solid ground
        this.waterTargetY = waypoint.feetY();
        this.waterIntent = switch (waypoint.move()) {
            case DIVE -> WaterIntent.DIVE;
            case SURFACE -> WaterIntent.SURFACE;
            case SWIM -> isUnderWater(waypoint) ? WaterIntent.CROSS_UNDER : WaterIntent.CROSS;
            default -> WaterIntent.EXIT;
        };
        double radius = isLast ? FINAL_RADIUS : WAYPOINT_RADIUS;
        // A DIVE or a SURFACE is the same column one cell along, so a horizontal arrival marks it
        // reached the instant it becomes current — which it did, and the body swam the rest of a
        // tunnel route at the surface, into the roof. Their arrival is the VERTICAL distance alone;
        // a floating waypoint keeps the opposite rule, and a climb-out wants the feet down.
        boolean verticalLeg = waypoint.move() == MoveType.DIVE
                || waypoint.move() == MoveType.SURFACE;
        double vertical = landTarget ? verticalGap(dy) : verticalLeg ? Math.abs(dy) : 0.0;
        // A water waypoint is somewhere the body has to BE: standing dry on the bank above the
        // right column is not being there — see atWaypointHeight, where the same omission stranded
        // a Person on a pool rim. It bites hardest on the SWIM leg, whose arrival is horizontal
        // alone.
        if (horizontalSq + vertical * vertical <= radius * radius
                && (landTarget ? this.person.onGround() : isWet())) {
            if (isLast && landTarget && !isSettled()) {
                this.person.stopMoving();
                return;
            }
            advance(isLast);
            return;
        }

        if (++this.stuckTicks > STUCK_LIMIT) {
            retryOrFail(MoveFailure.STALLED);
            return;
        }
        // In water, DEPTH is progress: the grounded path's horizontal-only measure calls a body
        // stuck the moment it starts down a column, and fifteen such ticks re-path — which is how a
        // good route through a flooded tunnel came back FAILED.
        double movedSq = (pos.x - this.lastTickX) * (pos.x - this.lastTickX)
                + (pos.y - this.lastTickY) * (pos.y - this.lastTickY)
                + (pos.z - this.lastTickZ) * (pos.z - this.lastTickZ);
        this.lastTickX = pos.x;
        this.lastTickY = pos.y;
        this.lastTickZ = pos.z;
        if (movedSq < NO_MOVE_EPSILON * NO_MOVE_EPSILON) {
            if (++this.noMoveTicks > NO_MOVE_LIMIT) {
                retryOrFail(MoveFailure.WEDGED);
                return;
            }
        } else {
            this.noMoveTicks = 0;
        }

        // Plain walking input: travel's water branch turns it into (slow) horizontal swimming.
        //
        // A leg with no horizontal distance is ZEROED, not skipped: skipping latches the last leg's
        // forward input, and vanilla's fluid travel normalises the whole (x, y, z) vector before
        // scaling, so a body told to swim straight down still pushed forward and sank at a third of
        // the rate it should have.
        if (horizontalSq > NO_MOVE_EPSILON) {
            float heading = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            this.person.driveForward(heading);
        } else {
            this.person.driveForward(this.person.entity().getYRot(), 0.0F);
        }
        // The climb-out's lift is not pressed here: every vertical press while wet belongs to the
        // Swimmer (ticked after this), so narrowing one press cannot silently remove another.
    }

    /**
     * Whether a waypoint's own cell is one where the body's head would be under water.
     *
     * <p>Asked of the GRID the route was planned on: the body cannot tell, because every measure it
     * has of itself moves with the swimming pose, and the pose is downstream of this. Trying anyway
     * pinned a surface swimmer half a block under its own lake and let buoyancy undo a dive.
     */
    private boolean isUnderWater(Waypoint waypoint) {
        if (this.grid == null) {
            return false;
        }
        int head = waypoint.y() + capabilities().topCell(0.0);
        return this.grid.cell(waypoint.x(), head, waypoint.z()) == CellType.WATER;
    }

    /**
     * Claims every waypoint we are already past: standing in the cell of a <em>later</em> one
     * (overshot jump, knockback) continues from there. Safe by construction — we only skip to a
     * node we are standing on, so no skipped stretch can hide an obstacle, and A* never revisits a
     * cell, so a match is unambiguous. The final waypoint is made current rather than claimed, so
     * the arrival radius still settles us on the goal.
     */
    private void skipPassedWaypoints() {
        BlockPos feet = this.person.blockPosition();
        double y = this.person.position().y;
        int last = this.path.waypoints().size() - 1;
        int limit = Math.min(this.index + SKIP_LOOKAHEAD, last);
        for (int j = limit; j > this.index; j--) {
            Waypoint w = this.path.waypoints().get(j);
            // A LEAP landing can't be "passed through" mid-air: claiming it while flying over
            // rewires the steering to the next waypoint and turns chained leaps into one long
            // curved flight. It counts only once the feet are actually planted.
            if (w.move() == MoveType.LEAP && !this.person.onGround()) {
                continue;
            }
            if (feet.getX() == w.x() && feet.getZ() == w.z()
                    && atWaypointHeight(y - w.feetY(), w.move(), isWet())) {
                this.index = Math.min(j + 1, last);
                this.stuckTicks = 0;
                return;
            }
        }
    }

    /**
     * Plane-crossing advance: a waypoint is passed once we are beyond the perpendicular plane
     * through it along the segment to the next one — at its height and within a 2-block lane. A
     * drop's glide lands between waypoints, and without this the steering aims back at the one
     * behind for a tick. Never touches the final waypoint, and the vertical gate keeps a JUMP
     * unclaimable from below its ledge.
     */
    private void advancePassedPlanes(Vec3 pos) {
        while (this.index < this.path.waypoints().size() - 1) {
            Waypoint current = this.path.waypoints().get(this.index);
            if (current.move() == MoveType.LEAP && !this.person.onGround()) {
                return; // a leap landing is claimed on touchdown, never mid-flight (see skip)
            }
            Waypoint next = this.path.waypoints().get(this.index + 1);
            double offX = pos.x - (current.x() + 0.5);
            double offZ = pos.z - (current.z() + 0.5);
            double segX = next.x() - current.x();
            double segZ = next.z() - current.z();
            double segLen = Math.sqrt(segX * segX + segZ * segZ);
            if (segLen <= 0.0) {
                return; // the next leg is straight up or down: there is no plane to be past
            }
            // Decompose the offset against the outgoing segment: FORWARD overshoot is the glide this
            // advance exists for (up to 2.5); LATERAL drift means we are BESIDE the waypoint, and
            // claiming it skips the sidestep that aligns for a cardinal jump. A single round radius
            // conflated the two.
            double forward = (offX * segX + offZ * segZ) / segLen;
            double lateral = Math.abs(offX * segZ - offZ * segX) / segLen;
            if (!atWaypointHeight(aboveWaypoint(pos, current), current.move(), isWet())
                    || forward <= 0.0 || forward > 2.5 || lateral > 0.6) {
                return;
            }
            this.index++;
            this.stuckTicks = 0;
        }
    }

    /**
     * The bodies close enough to be in the way this tick, as the discs {@link CrowdSteering} steers
     * around. Pushable living things only — what cannot be pushed cannot be pushed <em>around</em>.
     *
     * <p>Asked live rather than off the {@code BeingSense}: a shove is decided at sub-block
     * precision, so a position one sense-beat old is already a body-width wrong, and identity has
     * no bearing on walking through somebody. The box is barely wider than the one vanilla sweeps
     * every tick to push with.
     */
    private List<CrowdSteering.Neighbour> crowd() {
        LivingEntity self = this.person.entity();
        // REACH is surface to surface, so the box has to carry a radius at each end: our own comes
        // with the bounding box, and 1.0 covers anything up to two blocks wide on the other side.
        double margin = CrowdSteering.REACH + 1.0;
        List<Entity> nearby = this.person.level().getEntities(self,
                self.getBoundingBox().inflate(margin, 0.0, margin),
                other -> other instanceof LivingEntity living && living.isPushable());
        if (nearby.isEmpty()) {
            return List.of();
        }
        List<CrowdSteering.Neighbour> crowd = new ArrayList<>(nearby.size());
        for (Entity other : nearby) {
            crowd.add(new CrowdSteering.Neighbour(
                    other.getX(), other.getZ(), other.getBbWidth() / 2.0));
        }
        return crowd;
    }

    /**
     * Whether one of {@code crowd} is standing on the cell we are walking to — see
     * {@code blockedTarget}, its only caller. Judged from the cell's middle by a body's own width,
     * so "on it" is not merely overlapping from the neighbouring cell.
     */
    private static boolean isOccupied(List<CrowdSteering.Neighbour> crowd, Waypoint waypoint) {
        for (CrowdSteering.Neighbour neighbour : crowd) {
            double offX = neighbour.x() - (waypoint.x() + 0.5);
            double offZ = neighbour.z() - (waypoint.z() + 0.5);
            double reach = neighbour.radius() + WAYPOINT_RADIUS;
            if (offX * offX + offZ * offZ < reach * reach) {
                return true;
            }
        }
        return false;
    }

    /** Whether residual horizontal momentum has bled off (see {@link #SETTLED_SPEED_SQ}). */
    private boolean isSettled() {
        Vec3 velocity = this.person.entity().getDeltaMovement();
        return velocity.x * velocity.x + velocity.z * velocity.z < SETTLED_SPEED_SQ;
    }

    /**
     * Vertical distance from a waypoint's <em>standing band</em>: feet legitimately rest from the
     * cell floor to half a block above it (slabs, stair bottoms), so that range counts as zero.
     * Keeps 3-D arrival checks strict without making every slab cell unreachable.
     *
     * <p>Measured from {@link Waypoint#feetY()}, so the band is centred on the actual standing
     * height rather than spread to cover wherever a partial floor put the feet.
     */
    private static double verticalGap(double dy) {
        if (dy < 0.0) return -dy;
        return Math.max(0.0, dy - 0.5);
    }

    /** How far above its own standing height the body is, for the waypoint it is walking toward. */
    private static double aboveWaypoint(Vec3 pos, Waypoint waypoint) {
        return pos.y - waypoint.feetY();
    }

    /**
     * Whether a body {@code dy} above a waypoint counts as "at its height" for claiming/advancing
     * it: a footed move uses the tight standing band ({@link #verticalGap} &lt; 0.5), a {@link
     * MoveType#SWIM} waypoint the wider {@link #SWIM_BAND}, so the surface bob doesn't flicker it
     * in and out of range.
     *
     * <p><b>A water waypoint additionally wants the body to be in the water.</b> Without that, a
     * Person dry on a pool rim — reckoned into the water column beside her, and so inside
     * {@link #SWIM_BAND} of it — claimed the swim waypoint, was handed a DIVE two blocks under
     * solid ground, and failed the walk after four retries. The band is only true of a body already
     * floating, and being wet is the same question {@link #tickFollowing} asks to steer a leg as a
     * swim.
     */
    static boolean atWaypointHeight(double dy, MoveType move, boolean wet) {
        if (move.inWater() && !wet) {
            return false;
        }
        if (move == MoveType.SWIM) {
            return dy >= -SWIM_BAND && dy <= SWIM_BAND;
        }
        // A DIVE or a SURFACE keeps the tight band: the depth hold parks the body well inside its
        // target cell, and widening it here would let a descent claim a cell it is still a block
        // short of — one more way down a column a body already ratchets down too easily.
        return verticalGap(dy) < 0.5;
    }

    /**
     * Whether the body is in the water — the one fact separating being IN a water cell from standing
     * on the bank above one, which every measure taken from the cells alone will confuse.
     */
    private boolean isWet() {
        return this.person.entity().isInWater();
    }

    /**
     * Whether this moment warrants careful mode (see {@link #CAREFUL_THROTTLE}). Walk and drop
     * stretches are careful when the waypoint or our own feet cell borders a deep drop; a LEAP only
     * in its landing phase, and only when the landing cell itself borders another drop. Checked
     * against the grid the path was planned on.
     */
    private boolean isCareful(Waypoint waypoint, boolean leapLanding) {
        if (this.grid == null) {
            return false;
        }
        int maxDrop = capabilities().maxDrop();
        MoveType move = waypoint.move();
        if (move == MoveType.LEAP) {
            return leapLanding
                    && NavGrids.isNearDeepDrop(this.grid, maxDrop, waypoint.x(), waypoint.y(), waypoint.z());
        }
        if (move != MoveType.WALK && move != MoveType.DROP) {
            return false;
        }
        if (NavGrids.isNearDeepDrop(this.grid, maxDrop, waypoint.x(), waypoint.y(), waypoint.z())) {
            return true;
        }
        BlockPos feet = this.person.blockPosition();
        return NavGrids.isNearDeepDrop(this.grid, maxDrop, feet.getX(), feet.getY(), feet.getZ());
    }

    /**
     * Center-to-center span of a {@link MoveType#LEAP} into {@code waypoint}: gap width + 1
     * (2..4). Read off the previous waypoint (leaps are cardinal, so the Chebyshev distance is
     * exact); when the leap is the path's first move the takeoff is our own start cell.
     */
    private double leapSpan(Waypoint waypoint) {
        int fromX;
        int fromZ;
        if (this.index > 0) {
            Waypoint previous = this.path.waypoints().get(this.index - 1);
            fromX = previous.x();
            fromZ = previous.z();
        } else {
            BlockPos feet = this.person.blockPosition();
            fromX = feet.getX();
            fromZ = feet.getZ();
        }
        return Math.max(Math.abs(waypoint.x() - fromX), Math.abs(waypoint.z() - fromZ));
    }

    private void advance(boolean wasLast) {
        this.stuckTicks = 0;
        if (!wasLast) {
            this.index++;
            return;
        }
        this.person.stopMoving();
        if (this.path.reachedGoal()) {
            log("arrived", "at " + this.person.blockPosition().toShortString());
            this.path = null;
            this.state = State.ARRIVED;
        } else {
            // Finished a partial path: the goal was beyond the snapshot (or momentarily walled).
            // From here a fresh snapshot may reach further — long trips work as successive legs of
            // this, so UNREACHABLE is only the verdict once the budget is spent.
            retryOrFail(MoveFailure.UNREACHABLE);
        }
    }

    /**
     * Re-plan from where we stand, or give up if this order has spent its budget. {@code why} is
     * recorded on every call, so the cause reported is the one that <em>last</em> beat this order:
     * strayed twice and then wedged fails wedged.
     */
    private void retryOrFail(MoveFailure why) {
        this.failure = why;
        if (this.repathsLeft-- > 0) {
            requestPath();
        } else {
            log("failed", "gave up after retries — " + why.describe());
            this.path = null;
            this.state = State.FAILED;
            this.person.stopMoving();
        }
    }

    /**
     * The first completion-critical cell of the next {@link #INTEGRITY_LOOKAHEAD} waypoints that no
     * longer classifies the way the plan needs — a floor mined away, a corridor walled off, a swim
     * lane drained — or {@code null} while the path holds. The {@link CellNeed} comes back rather
     * than a flag so the re-path can log <em>why</em> ({@link #changeReason}).
     *
     * <p>Reads the live world, legal inside {@code serverAiStep}; unloaded cells are skipped and
     * the read must never force-load a chunk. Watches the whole deck the feet cross on each level
     * edge; drops and leaps stay destination-only. See {@link PathIntegrity}.
     */
    private @Nullable CellNeed pathChangedAhead() {
        if (this.path == null) {
            return null;
        }
        ServerLevel level = level();
        int last = this.path.waypoints().size() - 1;
        int limit = Math.min(this.index + INTEGRITY_LOOKAHEAD, last);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        MoveCapabilities body = capabilities(); // hoisted: this loop runs every tick
        for (int i = this.index + 1; i <= limit; i++) {
            Waypoint from = this.path.waypoints().get(i - 1);
            Waypoint to = this.path.waypoints().get(i);
            for (CellNeed need : PathIntegrity.edgeNeeds(from, to, body)) {
                pos.set(need.x(), need.y(), need.z());
                if (level.isLoaded(pos) && !stillHolds(level, pos, need.need())) {
                    return need;
                }
            }
        }
        return null;
    }

    /**
     * Re-plan because the world changed under the current path, not because we are stuck. Unlike
     * {@link #retryOrFail} this does <em>not</em> spend the {@link #MAX_REPATHS} budget: it would
     * let anyone editing a few blocks near the path drive a reachable goal to FAILED.
     * {@link #PROACTIVE_REPATH_COOLDOWN} still bounds the rate.
     */
    private void proactiveRepath(CellNeed changed) {
        // PATHFIND log: the "recalculate - missing floor 5, 10, 10" line — which cell stopped matching
        // the plan, so a route that keeps re-planning has a visible cause.
        log("recalculate", changeReason(changed));
        this.proactiveRepathCooldown = PROACTIVE_REPATH_COOLDOWN;
        requestPath();
    }

    /**
     * Whether the live world still satisfies one {@link CellNeed} — the mod-side half, since only
     * here is there a level to read. {@link CellNeed.Need#FOOTING} alone reads two cells: footing
     * can be a partial floor in the cell or a full block under it, so a slab laid over the route
     * reads as the route still holding.
     */
    private static boolean stillHolds(ServerLevel level, BlockPos.MutableBlockPos pos,
                                      CellNeed.Need need) {
        CellType here = WorldSnapshot.classifyAt(level, pos);
        return switch (need) {
            case CLEAR -> here == CellType.PASSABLE;
            case WATER -> here == CellType.WATER;
            case ROOM -> here == CellType.PASSABLE || here == CellType.WATER;
            case FOOTING -> here == CellType.STEP
                    || (here == CellType.PASSABLE
                            && WorldSnapshot.classifyAt(level, pos.move(Direction.DOWN)) == CellType.GROUND);
        };
    }

    /**
     * A human phrase for a completion-critical cell that no longer meets what the plan needs there:
     * footing gone from under the feet, a cell now blocked, a swim lane drained.
     */
    private static String changeReason(CellNeed need) {
        String phrase = switch (need.need()) {
            case FOOTING -> "missing floor";
            case CLEAR -> "blocked";
            case WATER -> "drained";
            case ROOM -> "filled in";
        };
        return phrase + " at " + need.x() + ", " + need.y() + ", " + need.z();
    }

    /** Record a PATHFIND line to this person's debug journal (see {@link AgentBody#journal()}). */
    private void log(String event, String detail) {
        this.person.journal().record(Category.PATHFIND, event, detail);
    }

    private ServerLevel level() {
        return (ServerLevel) this.person.level(); // only ever ticked server-side (serverAiStep)
    }

    /**
     * What this body can physically do, read fresh. Not cached in a field: the profile
     * behind it is a live view, so a {@code config reload} — or a skill that raises a jump —
     * retunes an agent already walking. Cheap enough to ask per tick; hoisted out of the one loop
     * that would otherwise ask per waypoint.
     */
    private MoveCapabilities capabilities() {
        return MoveCapabilities.of(this.person.profile(), submergedBudget());
    }

    /**
     * How many cells this body may swim with its head under, on the breath it has RIGHT NOW.
     *
     * <p>Read live rather than from the species: plan a tunnel on a full lungful and it is a
     * tunnel, plan it having just come up from another and it is a drowning. A body with no breath
     * gauge dives freely — its consumer has declared it does not drown.
     *
     * <p>The reserve is not politeness: a plan claims cells, not tick counts, and the body can be
     * shoved or re-routed on the way.
     */
    private int submergedBudget() {
        Gauge breath = this.person.needs().gauge(NeedKind.BREATH).orElse(null);
        if (breath == null) {
            return NO_BREATH_LIMIT;
        }
        return (int) (breath.value() * (1.0 - BREATH_RESERVE) / TICKS_PER_SUBMERGED_CELL);
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    /**
     * A walk in progress, as data. Named for the walk rather than the state machine, whose
     * {@code State} enum already owns that word.
     *
     * <p>The path is carried rather than recomputed: it is chosen among many of similar cost, so
     * re-pathing gives <em>a</em> route rather than <em>the</em> route, and the index and stuck
     * counters only mean anything against the path they were counted on. The nav grid is
     * absent — it is a captured read of a world that is itself restored.
     *
     * <p>The failure rides along like the state, so a reloaded body does not report FAILED with no
     * reason — "an agent must not be able to tell a reboot happened".
     */
    public record Walk(String state, @Nullable BlockPos goal, List<Waypoint> waypoints,
                        boolean reachedGoal, int index, String gait, int stuckTicks,
                        int noMoveTicks, int groundedTicks, int lastLeapPressIndex,
                        int repathsLeft, int integrityCheckedIndex, int proactiveRepathCooldown,
                        String failure) {
    }

    /** What this navigator would need to carry on the same walk. */
    public Walk snapshot() {
        return new Walk(this.state.name(), this.goal,
                this.path == null ? List.of() : List.copyOf(this.path.waypoints()),
                this.path != null && this.path.reachedGoal(),
                this.index, this.gait.name(), this.stuckTicks, this.noMoveTicks,
                this.groundedTicks, this.lastLeapPressIndex, this.repathsLeft,
                this.integrityCheckedIndex, this.proactiveRepathCooldown, this.failure.name());
    }

    /**
     * Puts a walk back. The grid is left null on purpose: the follower asks for one when it needs
     * it, and rebuilding it from the restored world gives back the same grid.
     */
    public void restore(Walk saved) {
        this.state = Navigator.State.valueOf(saved.state());
        this.goal = saved.goal();
        this.path = saved.waypoints().isEmpty() ? null
                : new Path(List.copyOf(saved.waypoints()), saved.reachedGoal());
        this.index = saved.index();
        this.gait = Gait.valueOf(saved.gait());
        this.stuckTicks = saved.stuckTicks();
        this.noMoveTicks = saved.noMoveTicks();
        this.groundedTicks = saved.groundedTicks();
        this.lastLeapPressIndex = saved.lastLeapPressIndex();
        this.repathsLeft = saved.repathsLeft();
        this.integrityCheckedIndex = saved.integrityCheckedIndex();
        this.proactiveRepathCooldown = saved.proactiveRepathCooldown();
        this.failure = MoveFailure.valueOf(saved.failure());
    }
}
