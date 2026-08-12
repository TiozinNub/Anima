package dev.luizloyola.anima.core.nav;

import dev.luizloyola.anima.core.brain.sense.Confinement;
import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.core.brain.sense.SetbackField;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A* over a {@link NavGrid}, answering a {@link PathRequest} with a {@link Path}.
 *
 * <p>Pure and stateless per call ({@link #find} builds a private search instance), so it is safe
 * to run on a worker thread: the search never sees the live world, only an immutable, thread-safe
 * classification of it.
 *
 * <p>The neighbour model, parameterized by {@link MoveCapabilities}: cardinal walk, jump-up-1,
 * drop-up-to-{@code maxDrop}, level diagonals that refuse to cut corners, plus {@link #STRIDES}.
 * Deep holes and {@link CellType#DANGER} cells never produce a neighbour, so the search routes
 * around them for free.
 *
 * <p>When the goal cannot be reached — walled off, outside the grid, or the {@code maxNodes}
 * budget runs out — the result is the path to the expanded cell closest to the goal (smallest
 * heuristic, ties to the cheaper one), flagged {@code reachedGoal=false}.
 *
 * <p>Fully deterministic: ties in f break toward the most recently pushed node and neighbour
 * generation order is fixed. Given {@link PathRequest#variety()} it is still not the same for
 * everybody — see {@link #roughness}.
 */
public final class Pathfinder {
    private static final double SQRT2 = Math.sqrt(2.0);
    /** Cost of one cardinal walk step; the unit every other cost is relative to. */
    private static final double WALK_COST = 1.0;
    private static final double DIAGONAL_COST = SQRT2;
    /** Jumping is slow (stall, arc) — twice a plain step, so ramps beat hop-scotch when both exist. */
    private static final double JUMP_COST = 2.0;
    /**
     * Leap cost by gap width (index = gap). Each exceeds the covered distance (gap+1 — the
     * admissibility floor) by a risk premium that grows STEEPLY: per block covered these are 1.2,
     * 1.8 and 2.4, against a plain walk's 1.0 and the careful factor's 2.2. The old, nearly flat
     * 2.4 / 3.6 / 5.0 priced a 3-gap under careful walking, making the riskiest move in the model
     * the cheapest way past an obstacle.
     *
     * <p>The 1-gap price is untouched: leaping a shallow trench must keep beating
     * dipping through it (2.4 &lt; drop 1.0 + jump 2.0), and hopping a one-wide stream must keep
     * beating wading it.
     *
     * <p>Not multiplied by {@link #terrainFactor} like every other move: that factor is the
     * follower's throttle expressed as time, and the follower does not throttle a leap — approach
     * and flight need the full run-up. Charging it priced a one-wide water hop above swimming.
     */
    private static final double[] LEAP_COSTS = {0.0, 2.4, 5.4, 9.6};
    /**
     * Cost multiplier for unit moves whose either endpoint is careful ground (bordering a chasm,
     * lava, or water — {@link NavGrids#isNearDeepDrop}): the follower walks such steps at the
     * careful throttle (0.45 → this is 1/0.45), so this is the real time cost, and it doubles as
     * risk aversion — a moderately longer safe detour now wins over a 1-wide bridge. Costs only
     * grow, so the Euclidean heuristic stays admissible.
     */
    private static final double CAREFUL_COST_FACTOR = 2.2;
    /**
     * Cost of one cardinal cell of swimming — the swim-vs-detour dial. Well above a walk (1.0) and
     * around the careful factor (2.2), so the search takes a dry route of comparable length and
     * only crosses water when swimming genuinely saves distance. Narrow water still gets
     * <em>leapt</em> (a one-wide gap for 2.4) rather than swum. Must stay ≥ {@link #WALK_COST} so
     * the horizontal Euclidean heuristic stays admissible — every swim move covers at most its
     * cost in horizontal distance (cardinal 1, diagonal √2 against a 2.5·√2 price, enter/exit 1).
     */
    private static final double SWIM_COST = 2.5;
    /**
     * Cost of one cardinal cell swum with the head under water.
     *
     * <p>Over {@link #SWIM_COST}: the budget in {@link #relaxWater} is what makes a long dive
     * impossible, and this is what makes a short one a last resort, so a body that could go round
     * on the surface does.
     */
    private static final double SUBMERGED_COST = 3.2;
    /**
     * Cost multiplier for a step taken through water shallow enough to stand in — wading.
     *
     * <p>Between a walk and a swim on purpose: under {@link #SWIM_COST} because a body that keeps
     * its feet is quicker, and well over a dry walk because pushing through water is real work.
     * Roughly, four cells of puddle beat a detour of eight and lose to a detour of six.
     */
    private static final double WADE_COST_FACTOR = 1.8;
    /**
     * How far below a bank one probe will look for water to plunge into.
     *
     * <p><b>Not a survivability limit</b> like {@link MoveCapabilities#maxDrop}. Entering water
     * cancels the fall outright, at any height and into any depth, so there is no per-body number
     * to read: this is a <em>search</em> bound, and without one every probe over a ledge would
     * read the whole grid column beneath it.
     *
     * <p>What stops a body diving off everything it passes is the price — {@link #dropCost} of the
     * depth, which grows per block, so a walk down beats a dive whenever the walk is short. 32 is
     * past any drop a settlement sits on while keeping one probe's worst case small; raising it
     * makes deep dives <em>representable</em>, never cheap.
     */
    private static final int MAX_PLUNGE = 32;

    /** Neighbour probe order — fixed so the search is deterministic: N, S, W, E, then diagonals. */
    private static final int[][] CARDINALS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
    private static final int[][] DIAGONALS = {{1, -1}, {-1, -1}, {1, 1}, {-1, 1}};

    /** Longest stride radius (Chebyshev). Raising it widens the heading fan and lengthens steps,
     *  at more probe cost per expansion — and the follower's stray radius must stay above it. */
    private static final int MAX_STRIDE = 3;
    /**
     * Strides: longer flat steps — every offset with Chebyshev radius 2..{@link #MAX_STRIDE}. They
     * replace the 45°-then-90° zigzag with headings every ~12° <em>and</em> cover up to 3 cells per
     * node. Each costs its true Euclidean length, so it undercuts the unit-move decomposition
     * (√5 ≈ 2.24 < 1+√2) and A* prefers it without special casing. Fixed nested-loop order —
     * determinism again.
     */
    private static final int[][] STRIDES;
    private static final double[] STRIDE_COSTS;

    /**
     * How far a single stroke may reach, per axis, for a body already in the water.
     *
     * <p>Water is the one place in this model with no gravity to obey and no floor to follow, so a
     * body in it can go anywhere its own straight line is clear — where a model of axis-aligned
     * steps only makes it spell a slope out as a staircase.
     */
    private static final int WATER_REACH = 2;
    /**
     * How finely a stroke's line is sampled when looking for what it passes through. Eight to the
     * block is well past the point where a cell could hide between two samples, and costs almost
     * nothing: the samples are arithmetic, and only the cells they land in are actually tested.
     */
    private static final int LINE_SAMPLES_PER_BLOCK = 8;
    /** Every offset a swimmer may take in one stroke, and its true length. See {@link #WATER_REACH}. */
    private static final int[][] WATER_STROKES;
    private static final double[] WATER_STROKE_LENGTHS;

    static {
        List<int[]> strides = new ArrayList<>();
        for (int r = 2; r <= MAX_STRIDE; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == r) {
                        strides.add(new int[]{dx, dz});
                    }
                }
            }
        }
        STRIDES = strides.toArray(new int[0][]);
        STRIDE_COSTS = new double[STRIDES.length];
        for (int i = 0; i < STRIDES.length; i++) {
            STRIDE_COSTS[i] = Math.sqrt(STRIDES[i][0] * STRIDES[i][0] + STRIDES[i][1] * STRIDES[i][1]);
        }

        // Every offset within reach on all three axes, nearest first so the cheap common strokes
        // are probed before the long ones. Fixed order: determinism again.
        List<int[]> strokes = new ArrayList<>();
        for (int dx = -WATER_REACH; dx <= WATER_REACH; dx++) {
            for (int dy = -WATER_REACH; dy <= WATER_REACH; dy++) {
                for (int dz = -WATER_REACH; dz <= WATER_REACH; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        strokes.add(new int[] {dx, dy, dz});
                    }
                }
            }
        }
        strokes.sort((a, b) -> Integer.compare(
                a[0] * a[0] + a[1] * a[1] + a[2] * a[2],
                b[0] * b[0] + b[1] * b[1] + b[2] * b[2]));
        WATER_STROKES = strokes.toArray(new int[0][]);
        WATER_STROKE_LENGTHS = new double[WATER_STROKES.length];
        for (int i = 0; i < WATER_STROKES.length; i++) {
            int[] o = WATER_STROKES[i];
            WATER_STROKE_LENGTHS[i] = Math.sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]);
        }
    }

    private static final long NO_PARENT = Long.MIN_VALUE;

    /** Vanilla's step height — see {@link MoveCapabilities#STEP_UP}, which the classifier shares. */
    private static final double STEP_UP = MoveCapabilities.STEP_UP;
    /**
     * How far up a jump reaches: vanilla's ~1.25 blocks. Enough for a full block (1.0), and for a
     * full block with a carpet on it (1.0625); not enough for a block topped with a slab (1.5),
     * which is correctly a wall to be walked around.
     */
    private static final double JUMP_UP = 1.25;
    /** {@link #footing} for a cell the body cannot stand in at all. */
    private static final double NO_FOOTING = Double.NEGATIVE_INFINITY;
    /**
     * The footing handed to a swim move: none. A floating body's feet hang around the surface cell
     * and bob, so there is no standing height to record — the follower knows this and matches a
     * {@link MoveType#SWIM} waypoint by a wide band instead of a height.
     */
    private static final double FLOATING = NO_FOOTING;

    private final NavGrid grid;
    private final MoveCapabilities profile;
    private final int goalX;
    private final int goalY;
    private final int goalZ;

    /** Per-cell search record, keyed by packed position in {@link #nodes}. */
    private static final class Node {
        double g;
        long parent = NO_PARENT;
        MoveType move = MoveType.WALK;
        /** Feet height above this cell's floor, in sixteenths — see {@link Waypoint#surface16}. */
        int surface16;
        /** Cells travelled with the head under water to reach here — see {@link #relaxWater}. */
        int submergedRun;
        boolean closed;
    }

    private final Map<Long, Node> nodes = new HashMap<>();
    private final OpenHeap open = new OpenHeap();
    /** Careful-ground memo: each cell is probed by every incident edge, and one probe costs ~20
     *  grid reads — cache it per search. */
    private final Map<Long, Boolean> carefulCache = new HashMap<>();
    /**
     * Water-node memo, same reasoning as {@link #carefulCache}: the test is not cheap (a
     * standability check plus a clearance loop) and every cell is re-tested by each of its own
     * neighbours. It matters more here — a swimmer has a hundred neighbours where a walker has
     * forty.
     */
    private final Map<Long, Boolean> waterNodeCache = new HashMap<>();
    /**
     * What this body would rather not walk past. A snapshot taken before the search left the
     * server thread — see {@link PathRequest#of(int, int, int, int, int, int, MoveCapabilities,
     * DangerField)}.
     */
    private final DangerField danger;

    /**
     * Where this body has lately been beaten — {@link PathRequest#setbacks()}. A snapshot taken
     * before the search left the server thread, exactly like the danger field beside it.
     */
    private final SetbackField setbacks;

    /**
     * Where the body may stand at all — {@link PathRequest#domain()}. Enforced here at the one
     * funnel every move generator feeds, on the FEET cell of the offered node: a fenced search
     * cannot so much as consider a cell outside it. (A leap still arcs over cells it never
     * stands in, exactly as a player clears a gap.)
     */
    private final NavDomain domain;

    /** Whose taste in ground this search uses — {@link PathRequest#variety()}, 0 for nobody's. */
    private final long variety;

    /**
     * Set when a plunge probe walked off the bottom of the grid still looking for water — the one
     * move whose reach (up to {@link #MAX_PLUNGE}) is far past any sane margin, so it reports its
     * own encounter with the edge rather than forcing {@link #sealedIn} to allow for it everywhere.
     */
    private boolean boundsRefused;
    /** Set when a submerged move was refused for want of breath — see {@link #sealedIn}. */
    private boolean breathRefused;

    private Pathfinder(NavGrid grid, PathRequest request) {
        this.grid = grid;
        this.profile = request.profile();
        this.danger = request.danger();
        this.setbacks = request.setbacks();
        this.domain = request.domain();
        this.variety = request.variety();
        this.goalX = request.goalX();
        this.goalY = request.goalY();
        this.goalZ = request.goalZ();
    }

    /** Never returns {@code null}. */
    public static Path find(NavGrid grid, PathRequest request) {
        return new Pathfinder(grid, request).search(request);
    }

    /**
     * Asks, of nowhere in particular: can this body leave where it is standing?
     *
     * <p>The same expansion as {@link #find} with the goal taken away, so it never stops early — it
     * runs until there is nothing left to reach or the budget is spent, then applies the same four
     * guards. The request's goal is ignored; only its start, body and budget matter.
     *
     * <p><b>Asked, never overheard.</b> As a by-product of whatever walk the body last
     * attempted it is wrong exactly where it is needed: a body cutting its way out asks for one
     * cell at a time inside its own prison, and every one of those searches reaches its goal in a
     * step or two without ever trying to leave.
     *
     * <p>Cheap and rare: a body that is shut in has a tiny region by definition, so the expansion
     * ends almost immediately, and a body that is not is not asking. See
     * {@code AgentPercepts.confinement} for the gate and the cache.
     */
    public static Confinement survey(NavGrid grid, PathRequest request) {
        return new Pathfinder(grid, request).surveyFrom(request);
    }

    private Confinement surveyFrom(PathRequest request) {
        long start = pack(request.startX(), request.startY(), request.startZ());
        Node origin = new Node();
        origin.surface16 = surface16At(request.startX(), request.startY(), request.startZ());
        this.nodes.put(start, origin);
        this.open.push(start, 0.0);

        int expanded = 0;
        boolean exhausted = true;
        while (!this.open.isEmpty()) {
            long current = this.open.pop();
            Node node = this.nodes.get(current);
            if (node.closed) continue;
            node.closed = true;
            if (++expanded >= request.maxNodes()) {
                exhausted = false;
                break;
            }
            expandNeighbors(current, node);
        }
        return new Confinement(exhausted && sealedIn(request), expanded);
    }

    private Path search(PathRequest request) {
        long start = pack(request.startX(), request.startY(), request.startZ());
        long goal = pack(request.goalX(), request.goalY(), request.goalZ());
        if (start == goal) {
            return new Path(List.of(), true);
        }

        // The start node carries its footing like any other: a first move measured from the cell
        // floor would read the rise wrong in both directions. Read from the grid rather than from
        // footing(), because where the body actually stands is not this search's to second-guess.
        Node origin = new Node();
        origin.surface16 = surface16At(request.startX(), request.startY(), request.startZ());
        this.nodes.put(start, origin);
        this.open.push(start, heuristic(request.startX(), request.startY(), request.startZ()));

        long best = start;
        double bestScore = partialScore(request.startX(), request.startY(), request.startZ());
        double bestG = 0.0;

        int expanded = 0;
        // Set explicitly rather than derived from the loop condition afterwards: a last pop that
        // empties the heap on the very tick the budget runs out leaves the open set empty without
        // the region having been enumerated, and reading `open.isEmpty()` at the end would call
        // that a proof. Budget-limited is the conservative answer, so the break says so.
        boolean exhausted = true;
        while (!this.open.isEmpty()) {
            long current = this.open.pop();
            Node node = this.nodes.get(current);
            // The heap holds stale duplicates instead of supporting decrease-key; the first pop of
            // a position carries its best f, later pops of it are no-ops.
            if (node.closed) continue;
            node.closed = true;

            if (current == goal) {
                // +1: this node was closed above but the counter is only bumped further down, and
                // the count means "cells closed".
                return reconstruct(current, true, false, expanded + 1);
            }
            double score = partialScore(unpackX(current), unpackY(current), unpackZ(current));
            if (score < bestScore || (score == bestScore && node.g < bestG)) {
                best = current;
                bestScore = score;
                bestG = node.g;
            }
            if (++expanded >= request.maxNodes()) {
                exhausted = false;
                break;
            }
            expandNeighbors(current, node);
        }
        return reconstruct(best, false, exhausted && sealedIn(request), expanded);
    }

    /**
     * Whether an exhausted search proved the body is shut in — <b>the world was the only thing
     * that stopped it</b>. Four things can fence a search, and only one is a wall:
     *
     * <ul>
     *   <li><b>The request</b> — a {@link NavDomain} is a fence the CALLER put up; exhausting
     *       inside it says nothing about the world outside it.
     *   <li><b>The capture</b> — a {@link WorldSnapshot} is a window, everything past its edge
     *       reads OBSTACLE, and a big enough search walls a body into the box. Hence the margin
     *       below, and hence {@link NavGrid#inBounds}.
     *   <li><b>The body's air</b> — a route refused for want of breath is a limit of the lungs,
     *       not of the rock: that body is drowning, and telling it to dig is the wrong rescue.
     *   <li><b>The world</b> — walls, deep drops, lava, water it cannot swim. This one only.
     * </ul>
     *
     * <p>The margin is what a single move can CARRY the body, not how far a probe READS, and every
     * move but one is bounded by these numbers. The exception is the plunge, which looks up to
     * {@link #MAX_PLUNGE} down for water and says so itself — see {@link #boundsRefused}.
     */
    private boolean sealedIn(PathRequest request) {
        if (!this.domain.isEverywhere() || this.boundsRefused || this.breathRefused) {
            return false;
        }
        int margin = Math.max(Math.max(MAX_STRIDE + 1, this.profile.maxLeap() + 2),
                Math.max(this.profile.maxDrop() + 2,
                        this.profile.clearCells() + this.profile.jumpHeight() + 1));
        for (Map.Entry<Long, Node> entry : this.nodes.entrySet()) {
            if (!entry.getValue().closed) {
                continue; // opened but never reached: it was never anywhere the body could stand
            }
            long cell = entry.getKey();
            int x = unpackX(cell);
            int y = unpackY(cell);
            int z = unpackZ(cell);
            if (!this.grid.inBounds(x - margin, y, z) || !this.grid.inBounds(x + margin, y, z)
                    || !this.grid.inBounds(x, y - margin, z) || !this.grid.inBounds(x, y + margin, z)
                    || !this.grid.inBounds(x, y, z - margin) || !this.grid.inBounds(x, y, z + margin)) {
                return false; // this region reaches the edge of what we captured; no claim to make
            }
        }
        return true;
    }

    /** Probes every move the agent could make out of {@code current} and relaxes the reached cells. */
    private void expandNeighbors(long current, Node node) {
        int x = unpackX(current);
        int y = unpackY(current);
        int z = unpackZ(current);
        // Where this node's feet are, read once: every land move below is a rise measured from it,
        // and re-deriving it per probe would be twenty reads of the same two cells.
        double from = y + node.surface16 / 16.0;
        for (int[] d : CARDINALS) {
            cardinalNeighbor(current, node, x, y, z, from, d[0], d[1]);
        }
        for (int[] d : DIAGONALS) {
            diagonalNeighbor(current, node, x, y, z, from, d[0], d[1]);
        }
        for (int i = 0; i < STRIDES.length; i++) {
            strideNeighbor(current, node, x, y, z, from, STRIDES[i][0], STRIDES[i][1], STRIDE_COSTS[i]);
        }
        for (int[] d : CARDINALS) {
            leapNeighbor(current, node, x, y, z, from, d[0], d[1]);
        }
        swimNeighbors(current, node, x, y, z, from);
    }

    /**
     * Water moves, generated only for a swimmer ({@link MoveCapabilities#canSwim()}): a body in the
     * water strokes to anywhere within reach or climbs out onto a bank, and a body on land steps
     * off the shore into water. All of it lives in this one method so the water moves have a
     * single home.
     */
    private void swimNeighbors(long current, Node node, int x, int y, int z, double from) {
        if (!this.profile.canSwim()) return;
        if (!isWaterNode(x, y, z)) {
            for (int[] d : CARDINALS) swimEnter(current, node, x, y, z, from, d[0], d[1]);
            return;
        }
        // In the water, at any depth: one stroke to anywhere within reach whose line is clear.
        // Only climbing OUT needs the surface, because a body reaches a bank by coming up to it
        // rather than by rising through it.
        for (int i = 0; i < WATER_STROKES.length; i++) {
            int[] o = WATER_STROKES[i];
            swimStroke(current, node, x, y, z, o[0], o[1], o[2], WATER_STROKE_LENGTHS[i]);
        }
        if (!isSubmerged(x, y, z)) {
            for (int[] d : CARDINALS) swimExit(current, node, x, y, z, d[0], d[1]);
        }
    }


    /**
     * One stroke: a straight line to any cell within reach whose whole path is open water. The
     * water twin of {@link #strideNeighbor}, in the full three dimensions where a stride is two —
     * a stride's {@code y} is dictated by the ground, and a stroke's is free.
     *
     * <p><b>Legality is the LINE the body swims, not the bounding box around it.</b> The land
     * stride checks a box because a walker is dragged along the ground and can be carried wide; a
     * swimmer goes where it is pointed. Checking the box here was measured at four times the
     * search cost in open water — 124 offsets, none of them rejected, each sweeping up to 27 cells
     * — for an answer no better. Sampled fine enough that a cell clipped at a corner is still
     * seen, and only the DISTINCT cells are tested, since the test is what costs. Bails on the
     * first failure, so the table is sorted nearest-first.
     *
     * <p>Priced at its true length, so a stroke genuinely undercuts the steps it replaces
     * (√3 &lt; 3) and A* prefers it with no special case. Tagged by where it is going rather than
     * how far: down is a {@link MoveType#DIVE}, up is a {@link MoveType#SURFACE}, level is a
     * {@link MoveType#SWIM}.
     */
    private void swimStroke(long current, Node node, int x, int y, int z,
                            int dx, int dy, int dz, double length) {
        int nx = x + dx;
        int ny = y + dy;
        int nz = z + dz;
        if (!isWaterNode(nx, ny, nz)) {
            return;
        }
        int steps = (int) Math.ceil(length * LINE_SAMPLES_PER_BLOCK);
        int lastX = x;
        int lastY = y;
        int lastZ = z;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            int cx = (int) Math.floor(x + 0.5 + dx * t);
            int cy = (int) Math.floor(y + 0.5 + dy * t);
            int cz = (int) Math.floor(z + 0.5 + dz * t);
            if (cx == lastX && cy == lastY && cz == lastZ) {
                continue; // same cell as the last sample: already tested
            }
            lastX = cx;
            lastY = cy;
            lastZ = cz;
            if (!isWaterNode(cx, cy, cz)) {
                return;
            }
        }
        MoveType move = dy < 0 ? MoveType.DIVE : dy > 0 ? MoveType.SURFACE : MoveType.SWIM;
        relaxWater(current, node, pack(nx, ny, nz), move,
                strokeCost(nx, ny, nz) * length, nx, ny, nz);
    }

    /**
     * Whether feet-cell {@code (x,y,z)} is somewhere a swimmer can BE — water it fits inside and
     * cannot stand up in. Says nothing about how deep: the surface of a lake and the middle of a
     * flooded tunnel are both water nodes, and every horizontal move treats them alike.
     */
    private boolean isWaterNode(int x, int y, int z) {
        Boolean known = this.waterNodeCache.get(pack(x, y, z));
        return known != null ? known : computeWaterNode(x, y, z);
    }

    private boolean computeWaterNode(int x, int y, int z) {
        boolean answer = uncachedWaterNode(x, y, z);
        this.waterNodeCache.put(pack(x, y, z), answer);
        return answer;
    }

    private boolean uncachedWaterNode(int x, int y, int z) {
        if (this.grid.cell(x, y, z) != CellType.WATER) return false;
        if (isStandable(x, y, z)) return false; // wadeable: it is ground to this model, not water
        for (int i = 1; i <= this.profile.topCell(0.0); i++) {
            CellType above = this.grid.cell(x, y + i, z);
            if (above != CellType.PASSABLE && above != CellType.WATER) return false;
        }
        return true;
    }

    /**
     * Whether a body with its feet here has its head under — one read, of the cell the head is in.
     *
     * <p>About the HEAD and not about the depth of the pool: this is the whole of
     * what makes a stretch of water cost breath rather than merely time.
     */
    private boolean isSubmerged(int x, int y, int z) {
        return this.grid.cell(x, y + this.profile.topCell(0.0), z) == CellType.WATER;
    }

    /**
     * Whether feet-cell {@code (x,y,z)} is a floating spot at the water surface: the feet cell is
     * water and the rest of the body ({@code height-1} cells above) is air, so the head is above
     * the waterline — occupiable, and no drowning. The topmost water block of a column.
     *
     * <p><b>And nowhere a body could stand instead.</b> You float where you cannot wade, or a
     * shallow pool answers to both and one cell wears two nodes' worth of cost and parentage — the
     * same failure {@link #footing} refuses for a {@link CellType#STEP}.
     */
    private boolean isSurfaceSwim(int x, int y, int z) {
        return isWaterNode(x, y, z) && !isSubmerged(x, y, z);
    }

    /**
     * Whether a body falling into {@code (x,y,z)} would be caught by water — floating there, or
     * standing in it with its head out. Either ends a fall, and that is the whole question a plunge
     * asks: {@link #swimEnter} does not care which of the two it gets, because water cancels the
     * fall at any depth and one block of it is enough (gauntlet H6 is a plunge into exactly one).
     */
    private boolean catchesAFall(int x, int y, int z) {
        return this.grid.cell(x, y, z) == CellType.WATER && fits(x, y, z, 0.0);
    }



    /**
     * Step off the shore into the neighbouring water column: onto a surface level with the bank, or
     * off a ledge down into one — a <b>plunge</b>. The far column must be open at our level and all
     * the way down to the waterline, which is the same scan: the cells the probe walks through are
     * the ones the body falls through.
     *
     * <p><b>The plunge is not bounded by {@code maxDrop}</b> — that number is how far this body
     * will fall onto <em>ground</em>, and water cancels the fall entirely, so charging maxDrop made
     * a Person refuse the dive every player makes (gauntlet E4, H6). The bound is
     * {@link #MAX_PLUNGE}, a search bound; the deterrent is the price, {@link #dropCost} of the
     * depth.
     *
     * <p>Depth is measured from the FEET, not from the cell, for the same reason {@code stepTo}
     * does it: taking off from a slab is half a block further down than the cells suggest.
     *
     * <p>Tagged {@link MoveType#SWIM} — the destination feet land in water.
     */
    private void swimEnter(long current, Node node, int x, int y, int z, double from,
                           int dx, int dz) {
        int nx = x + dx;
        int nz = z + dz;
        if (isSurfaceSwim(nx, y, nz)) { // water level with the bank: step straight in
            relax(current, node, pack(nx, y, nz), FLOATING, MoveType.SWIM, SWIM_COST);
            return;
        }
        if (!hasClearance(nx, y, nz)) return; // can't even move into the near column
        int waterline = y - 1;
        int limit = y - MAX_PLUNGE;
        while (waterline >= limit && this.grid.cell(nx, waterline, nz) == CellType.PASSABLE) {
            waterline--; // fall through the air above the water
        }
        if (!this.grid.inBounds(nx, waterline, nz)) {
            // The column ran off the bottom of what we captured: whatever is down there — water to
            // dive into, or more rock — this search cannot say, and must not pretend the silence
            // was a floor. See sealedIn.
            this.boundsRefused = true;
        }
        if (waterline >= limit && catchesAFall(nx, waterline, nz)) {
            // The landing keeps whatever footing the cell really has — the bed when the pool is
            // shallow enough to stand in, FLOATING when it is not. Asking the cell rather than
            // assuming a float is what stops a plunge into one block of water (H6) from planting a
            // node that every land move afterwards disagrees with.
            //
            // Cost is whichever is dearer: the swim it becomes, or the fall it was. Both are
            // ≥ WALK_COST over one cardinal cell, so the horizontal heuristic stays admissible.
            relax(current, node, pack(nx, waterline, nz), footing(nx, waterline, nz), MoveType.SWIM,
                    Math.max(SWIM_COST, dropCost(from - waterline)));
        }
    }

    /**
     * Climb out of the water onto solid ground: wade onto a beach at the same level, or pull up onto
     * a bank up to {@code jumpHeight} above. Tagged as an ordinary {@link MoveType#WALK}/{@link
     * MoveType#JUMP} land move (the destination is standable ground); the follower recognises it as
     * the exit because it is still in the water when it gets there.
     */
    private void swimExit(long current, Node node, int x, int y, int z, int dx, int dz) {
        int nx = x + dx;
        int nz = z + dz;
        double bank = footing(nx, y, nz);
        if (bank != NO_FOOTING) {
            relax(current, node, pack(nx, y, nz), bank, MoveType.WALK, SWIM_COST);
            return;
        }
        for (int up = 1; up <= this.profile.jumpHeight(); up++) {
            double ledge = footing(nx, y + up, nz);
            if (ledge != NO_FOOTING) {
                relax(current, node, pack(nx, y + up, nz), ledge, MoveType.JUMP, SWIM_COST);
                return;
            }
        }
    }

    /**
     * Leaps: jump a gap of 1..{@code maxLeap} cells to same-level ground. A column counts as gap
     * when the body fits through it but has no floor at this level — pit, trench (leaping a 1-deep
     * trench at 2.4 beats dipping through it at 3.0), lava, water, chasm. Needs takeoff headroom, a
     * body-height+1 corridor over every gap column (the arc rises a block), a standable landing.
     * Same-level landings only in v1.
     */
    private void leapNeighbor(long current, Node node, int x, int y, int z, double from,
                              int dx, int dz) {
        int maxLeap = Math.min(this.profile.maxLeap(), LEAP_COSTS.length - 1);
        if (maxLeap < 1 || this.profile.jumpHeight() < 1) return;
        int overhead = this.profile.topCell(from - y) + 1;   // first cell above the head
        if (this.grid.cell(x, y + overhead, z) != CellType.PASSABLE) return; // takeoff headroom
        for (int gap = 1; gap <= maxLeap; gap++) {
            int gx = x + gap * dx;
            int gz = z + gap * dz;
            // The column must be open for the flight but floorless at this level — else it is
            // walkable ground (no leap needed) or a wall (no leap possible). A partial floor in it
            // counts as ground for that purpose: you walk onto a slab rather than leaping it, and
            // the corridor check below would have refused the cell anyway.
            if (this.grid.cell(gx, y - 1, gz) == CellType.GROUND) return;
            for (int i = 0; i <= overhead; i++) { // one past the head: the arc rises a block
                if (this.grid.cell(gx, y + i, gz) != CellType.PASSABLE) return;
            }
            int lx = x + (gap + 1) * dx;
            int lz = z + (gap + 1) * dz;
            double landing = footing(lx, y, lz);
            // Same-level landings only, still — but "level" is the footing, so leaping onto a
            // slab-topped far bank is a leap rather than a refusal. Landings a full cell up or
            // down remain out of the model (gauntlet A5/A6/A7).
            if (landing != NO_FOOTING && Math.abs(landing - from) <= STEP_UP) {
                // No run-up requirement, deliberately (Luiz): the follower sprints from inside the
                // takeoff cell and never backs up, so ground behind it changed which leaps were
                // ALLOWED without changing how any was EXECUTED. Capability is purely geometric;
                // if 3-gaps prove unreliable, fix the follower, not this check.
                relax(current, node, pack(lx, y, lz), landing, MoveType.LEAP, LEAP_COSTS[gap]);
                return; // landed on the near edge of the far side; wider leaps from here are moot
            }
        }
    }

    /**
     * One stride (see {@link #STRIDES}): a multi-cell straight step across flat ground. Legal only
     * when the <em>entire</em> bounding box of the step is standable at this level — a conservative
     * superset of every cell the body sweeps at any angle, so no obstacle, hole, or danger cell
     * can hide inside a stride, and terrain that isn't flat degrades to the unit moves.
     */
    private void strideNeighbor(long current, Node node, int x, int y, int z, double from,
                                int dx, int dz, double cost) {
        int x0 = Math.min(x, x + dx);
        int x1 = Math.max(x, x + dx);
        int z0 = Math.min(z, z + dz);
        int z1 = Math.max(z, z + dz);
        // The worst trouble this stride passes THROUGH. A cell surcharge only bites where the
        // search stands, and a stride does not stand in the cells it crosses — so without this a
        // body strides straight over the doorway that wedged it. (The worst rather than the sum:
        // crossing one bad cell should cost about what standing in it would.)
        //
        // Not done for dread: a fright is a large, smooth field, so the endpoints of any stride
        // near one are already paying, whereas a setback is a single cell — precisely what a
        // stride can straddle uncharged. Leaps are left alone too, and that is not an omission:
        // a leap genuinely arcs over the cells between.
        double crossed = 0.0;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                if (cx == x && cz == z) continue; // where we stand — standable by construction
                // Every cell swept must be footing the body could walk across without a step up:
                // a stride is one long straight move, so anything it can't take in its own stride
                // must degrade to the unit moves that price the climb properly.
                if (!walkableFlank(cx, y, cz, from)) return;
                // Strides are open-ground moves: careful ground (rim lanes, narrow bridges) and
                // water take unit steps at their own cost instead — precise and correctly priced.
                // A stride prices itself by its length alone, so letting one sweep a wet cell would
                // buy three blocks of wading for the price of walking them.
                if (isCareful(cx, y, cz) || isWater(cx, y, cz)) return;
                if (cx != x + dx || cz != z + dz) { // the destination is charged by relax itself
                    crossed = Math.max(crossed, grudge(pack(cx, y, cz)));
                }
            }
        }
        relax(current, node, pack(x + dx, y, z + dz), footing(x + dx, y, z + dz), MoveType.WALK,
                cost + crossed);
    }

    /**
     * One cardinal step, whatever the terrain does vertically: a level walk, a step up or down onto
     * a partial floor, a jump onto a full block, or a drop of up to {@code maxDrop}.
     *
     * <p>Which of those it is falls out of one number — the <b>rise</b> between the two footings —
     * rather than out of a case per shape of ground. That is why a staircase reads as walking
     * rather than as four hops: each stair is half a block, and half a block is a walk.
     */
    private void cardinalNeighbor(long current, Node node, int x, int y, int z, double from,
                                  int dx, int dz) {
        int nx = x + dx;
        int nz = z + dz;
        // The highest place to stand in the destination column that is within reach: scan from one
        // cell up (a step or a jump) down to the deepest survivable landing, and take the first
        // footing found. A cell that is not passable ends the scan — you cannot fall through a
        // floor to a better one under it, and you cannot walk through a wall to what is behind it.
        // The cells the scan passes through are the fall path, so passing them is the
        // fall-clearance check, as it always was.
        for (int ny = y + 1; ny >= y - this.profile.maxDrop(); ny--) {
            double to = footing(nx, ny, nz);
            if (to != NO_FOOTING) {
                stepTo(current, node, x, y, z, from, nx, ny, nz, to);
                return;
            }
            if (this.grid.cell(nx, ny, nz) != CellType.PASSABLE) {
                return;
            }
        }
    }

    /** Classifies a reachable neighbouring footing by its rise and relaxes it. */
    private void stepTo(long current, Node node, int x, int y, int z, double from,
                        int nx, int ny, int nz, double to) {
        double rise = to - from;
        if (rise > STEP_UP) {
            if (this.profile.jumpHeight() < 1 || rise > JUMP_UP) return;
            // Headroom to jump: a clear cell above the head to rise into. Measured from where the
            // feet actually are — a body already standing half a block up has its head there too.
            if (this.grid.cell(x, y + this.profile.topCell(from - y) + 1, z) != CellType.PASSABLE) return;
            relax(current, node, pack(nx, ny, nz), to, MoveType.JUMP,
                    JUMP_COST * terrainFactor(x, y, z, nx, ny, nz));
            return;
        }
        if (rise >= -STEP_UP) {
            relax(current, node, pack(nx, ny, nz), to, MoveType.WALK,
                    WALK_COST * terrainFactor(x, y, z, nx, ny, nz));
            return;
        }
        // A fall. The scan bounds the CELL to maxDrop, but a raised takeoff makes the real fall
        // deeper than the cells suggest — standing on a slab and dropping maxDrop cells is half a
        // block further than this body agreed to.
        double depth = -rise;
        if (depth > this.profile.maxDrop()) return;
        relax(current, node, pack(nx, ny, nz), to, MoveType.DROP,
                dropCost(depth) * terrainFactor(x, y, z, nx, ny, nz));
    }

    /**
     * One diagonal step — the cardinal move's twin, and built the same way: scan the destination
     * column for the first footing within reach and let the rise say whether it is a walk, a jump
     * or a drop.
     *
     * <p><b>The flanks need not be floors.</b> A body crossing the centre line overlaps both
     * flanking columns, so a solid one is still refused as a corner cut through a wall — but
     * requiring them to be <em>standable</em> refused every pair of blocks touching only at a
     * corner, which a player crosses perfectly well: the feet are carried by the two blocks on the
     * diagonal and the empty corners are passed over. A flank that harms, or one too tall to sweep
     * through, still stops the move.
     */
    private void diagonalNeighbor(long current, Node node, int x, int y, int z, double from,
                                  int dx, int dz) {
        int nx = x + dx;
        int nz = z + dz;
        for (int ny = y + 1; ny >= y - this.profile.maxDrop(); ny--) {
            double to = footing(nx, ny, nz);
            if (to != NO_FOOTING) {
                diagonalTo(current, node, x, y, z, from, nx, ny, nz, to, dx, dz);
                return;
            }
            if (this.grid.cell(nx, ny, nz) != CellType.PASSABLE) {
                return;
            }
        }
    }

    /** Classifies a reachable diagonal footing by its rise, once both flanks allow the sweep. */
    private void diagonalTo(long current, Node node, int x, int y, int z, double from,
                            int nx, int ny, int nz, double to, int dx, int dz) {
        double rise = to - from;
        double lo = Math.min(from, to);
        double hi = Math.max(from, to);
        if (!diagonalFlank(nx, z, lo, hi) || !diagonalFlank(x, nz, lo, hi)) return;

        // Every cost here must stay at or above the move's horizontal length (√2), or the
        // Euclidean heuristic stops being a lower bound and A* stops being optimal. A cardinal
        // drop of one costs 1.0, which is under it — hence the floor.
        if (rise > STEP_UP) {
            if (this.profile.jumpHeight() < 1 || rise > JUMP_UP) return;
            if (this.grid.cell(x, y + this.profile.topCell(from - y) + 1, z) != CellType.PASSABLE) return;
            relax(current, node, pack(nx, ny, nz), to, MoveType.JUMP,
                    Math.max(DIAGONAL_COST, JUMP_COST) * terrainFactor(x, y, z, nx, ny, nz));
            return;
        }
        if (rise >= -STEP_UP) {
            relax(current, node, pack(nx, ny, nz), to, MoveType.WALK,
                    DIAGONAL_COST * terrainFactor(x, y, z, nx, ny, nz));
            return;
        }
        double depth = -rise;
        if (depth > this.profile.maxDrop()) return;
        relax(current, node, pack(nx, ny, nz), to, MoveType.DROP,
                Math.max(DIAGONAL_COST, dropCost(depth)) * terrainFactor(x, y, z, nx, ny, nz));
    }

    /**
     * Whether a cell is footing the body could walk across from {@code from} without a step up —
     * what a STRIDE needs of every cell it sweeps, which is a stricter thing than what a diagonal
     * needs of its flanks. A stride walks ON each of them; a diagonal only passes between two of
     * them, carried by the blocks at its ends.
     */
    private boolean walkableFlank(int x, int y, int z, double from) {
        double f = footing(x, y, z);
        return f != NO_FOOTING && Math.abs(f - from) <= STEP_UP;
    }

    /**
     * Whether the body may sweep through one flanking column of a diagonal running between the
     * footings {@code lo} and {@code hi}. It must clip no solid and touch nothing harmful; it does
     * not need anything to stand on.
     *
     * <p>The lowest cell may hold a partial floor the body steps over — a carpet beside a
     * doorway is not a reason to walk the long way round — but everything above it, all the way to
     * where the head reaches at the higher end of the move, has to be clear.
     */
    private boolean diagonalFlank(int fx, int fz, double lo, double hi) {
        int loCell = (int) Math.floor(lo);
        int hiCell = (int) Math.floor(hi) + this.profile.topCell(hi - Math.floor(hi));
        CellType bottom = this.grid.cell(fx, loCell, fz);
        if (bottom == CellType.STEP) {
            if (loCell + this.grid.surface(fx, loCell, fz) - lo > STEP_UP) return false;
        } else if (bottom != CellType.PASSABLE) {
            return false;
        }
        for (int cy = loCell + 1; cy <= hiCell; cy++) {
            if (this.grid.cell(fx, cy, fz) != CellType.PASSABLE) return false;
        }
        return true;
    }

    /**
     * Where the feet come to rest if the body stands with {@code (x,y,z)} as its feet-cell, as an
     * absolute y — or {@link #NO_FOOTING} if it cannot stand there at all. The one question the
     * whole neighbour model is built on: every move is a pair of footings and the rise between
     * them.
     *
     * <p>There are exactly two ways to have footing, and a cell is never both. The search keys its
     * nodes by cell, so a standing place that answered to two cells would be two nodes for one
     * spot, with two costs and two parents — hence a {@link CellType#STEP} is its <em>own</em>
     * feet-cell and never a floor for the cell above it.
     *
     * <p><b>Shallow water is footing</b>, and putting it here rather than beside the swim moves is
     * the whole of what makes wading work: every land move is built on this one answer, so a puddle
     * a body can stand in becomes ordinary ground to all of them at once. A body wades where it can
     * stand on the bed with its head clear of the surface — the same two questions the dry branch
     * below asks, of the same two cells, against a different {@link CellType}.
     */
    private double footing(int x, int y, int z) {
        CellType here = this.grid.cell(x, y, z);
        if (here == CellType.STEP) {
            double surface = this.grid.surface(x, y, z);
            return fits(x, y, z, surface) ? y + surface : NO_FOOTING;
        }
        if (here != CellType.PASSABLE && here != CellType.WATER) {
            return NO_FOOTING; // solid, harmful, or off the edge of the world
        }
        if (this.grid.cell(x, y - 1, z) != CellType.GROUND) {
            return NO_FOOTING; // nothing under us — or a STEP, which is its own feet-cell
        }
        // fits() demands PASSABLE overhead, so for a water cell it is also the test that the head
        // is above the waterline: one more cell of water up there and this is swimming, not wading.
        return fits(x, y, z, 0.0) ? y : NO_FOOTING;
    }

    /** Whether feet-cell {@code (x,y,z)} affords footing at all — {@link #footing} as a predicate. */
    private boolean isStandable(int x, int y, int z) {
        return footing(x, y, z) != NO_FOOTING;
    }

    /** How far into a cell its own surface sits, in sixteenths — 0 for anything but a partial floor. */
    private int surface16At(int x, int y, int z) {
        if (this.grid.cell(x, y, z) != CellType.STEP) return 0;
        return Math.max(0, Math.min(15, (int) Math.round(this.grid.surface(x, y, z) * 16.0)));
    }

    /**
     * Whether a body standing at {@code surface} above the floor of feet-cell {@code (x,y,z)} has
     * room for the rest of itself. The feet cell is clear above the surface by construction (a
     * {@link CellType#STEP} is solid only up to it, a {@link CellType#PASSABLE} not at all), so
     * this checks the cells above — one more when a raised surface pushes the head into it.
     *
     * <p><b>conservative by up to a fifth of a block.</b> The body is modelled as
     * {@code height} whole blocks tall because that is the only height a profile declares; a Person
     * is really 1.8, so a surface less than 0.2 up (a carpet, a pressure plate) is charged for
     * headroom it does not use. That costs a detour, where being optimistic would plan a route that
     * wedges. The exact fix is a fractional {@code body.stand_height} aspect beside
     * {@code body.height}.
     */
    private boolean fits(int x, int y, int z, double surface) {
        int top = this.profile.topCell(surface);
        for (int i = 1; i <= top; i++) {
            if (this.grid.cell(x, y + i, z) != CellType.PASSABLE) return false;
        }
        return true;
    }

    /** Memoized {@link NavGrids#isNearDeepDrop} — see {@link #CAREFUL_COST_FACTOR}. */
    private boolean isCareful(int x, int y, int z) {
        return this.carefulCache.computeIfAbsent(pack(x, y, z),
                key -> NavGrids.isNearDeepDrop(this.grid, this.profile.maxDrop(), x, y, z));
    }

    /**
     * The cost factor for a unit move between two feet-cells — everything about the ground that
     * makes it slower than open dry ground, in one number. Careful ground at either end means the
     * follower throttles; wet at either end means the body is pushing through water. They multiply
     * because they are independent, and one step can be both.
     */
    private double terrainFactor(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        double factor = 1.0;
        if (isCareful(fromX, fromY, fromZ) || isCareful(toX, toY, toZ)) {
            factor *= CAREFUL_COST_FACTOR;
        }
        if (isWater(fromX, fromY, fromZ) || isWater(toX, toY, toZ)) {
            factor *= WADE_COST_FACTOR;
        }
        return factor;
    }

    /** What one stroke into this cell costs: dearer with the head under than at the surface. */
    private double strokeCost(int x, int y, int z) {
        return isSubmerged(x, y, z) ? SUBMERGED_COST : SWIM_COST;
    }

    /**
     * {@link #relax} for a move that ends in water, carrying the breath clock with it.
     *
     * <p>How long a body has been under is not a property of the cell it ends in, so it rides on
     * the node: each submerged cell adds one, anything else resets to zero, and a run past
     * {@link MoveCapabilities#maxSubmerged} is not offered. That budget is read off the breath
     * gauge when the request is made, so the same tunnel is open to a rested body and shut to one
     * that just came up from another.
     *
     * <p><b>An approximation, in the safe direction.</b> A* closes a cell the first time it pops
     * it, so a cell first reached by a long dive keeps that dive's clock even if a later, dearer
     * route would have arrived with more air — refusing a route the body could have made rather
     * than planning one that drowns it. An exact answer means putting the air into the node KEY and
     * searching a state space a few hundred times larger.
     */
    private void relaxWater(long current, Node from, long neighbor, MoveType move, double cost,
            int nx, int ny, int nz) {
        int run = isSubmerged(nx, ny, nz) ? from.submergedRun + 1 : 0;
        if (run > this.profile.maxSubmerged()) {
            // Further under than this body has breath to come back from. Remembered, because a
            // search fenced by its own lungs must not come back claiming the rock shut it in.
            this.breathRefused = true;
            return;
        }
        relax(current, from, neighbor, FLOATING, move, cost, run);
    }

    /** Whether this feet-cell is wet — a body standing here is standing IN water. */
    private boolean isWater(int x, int y, int z) {
        return this.grid.cell(x, y, z) == CellType.WATER;
    }

    /**
     * Falling is quick but each extra block adds commitment (and, eventually, damage): full walk
     * cost for the step off the edge, +0.3 per block beyond the first. Never below {@link
     * #WALK_COST} — every move must cost at least its one cardinal step or the (horizontal-only)
     * heuristic would overestimate and break A*'s optimality.
     */
    private static double dropCost(double depth) {
        return Math.max(WALK_COST, WALK_COST + 0.3 * (depth - 1));
    }

    /** Whether this body fits standing flat at feet-cell {@code (x,y,z)} — its own cell included. */
    private boolean hasClearance(int x, int y, int z) {
        for (int i = 0; i <= this.profile.topCell(0.0); i++) {
            if (this.grid.cell(x, y + i, z) != CellType.PASSABLE) return false;
        }
        return true;
    }

    /**
     * Standard A* edge relaxation: record-or-improve the neighbour and (re)queue it.
     *
     * <p>{@code footing} is where the feet land, absolutely; it is stored on the node as an offset
     * inside the cell so the reconstructed {@link Waypoint} can tell the follower the true standing
     * height. Rounding to sixteenths is lossless for every vanilla shape (they are all built on the
     * sixteenth grid) and keeps a waypoint an exactly comparable value.
     */
    private void relax(long current, Node from, long neighbor, double footing, MoveType move,
                       double cost) {
        // A move that ends anywhere but under water puts the breath clock back to zero, which for
        // every land move is the truth: the body has its head in the air again.
        relax(current, from, neighbor, footing, move, cost, 0);
    }

    private void relax(long current, Node from, long neighbor, double footing, MoveType move,
                       double cost, int submergedRun) {
        int ny = unpackY(neighbor);
        if (!this.domain.contains(unpackX(neighbor), ny, unpackZ(neighbor))) {
            return; // outside the fence there is no world, not merely a worse one
        }
        int surface16 = footing == NO_FOOTING ? 0
                : Math.max(0, Math.min(15, (int) Math.round((footing - ny) * 16.0)));
        // Scaled by the ground, then surcharged for fear: roughness is how tiring the crossing is,
        // so it multiplies the crossing, while dread is a flat toll for setting foot at all. The
        // heuristic survives both because neither can make a move cost less than its length.
        double g = from.g + cost * (1.0 + roughness(neighbor)) + dread(neighbor) + grudge(neighbor);
        Node node = this.nodes.get(neighbor);
        if (node == null) {
            node = new Node();
            node.g = g;
            node.parent = current;
            node.move = move;
            node.surface16 = surface16;
            node.submergedRun = submergedRun;
            this.nodes.put(neighbor, node);
        } else if (!node.closed && g < node.g) {
            node.g = g;
            node.parent = current;
            node.move = move;
            node.surface16 = surface16;
            node.submergedRun = submergedRun;
        } else {
            return;
        }
        this.open.push(neighbor, g + heuristic(unpackX(neighbor), ny, unpackZ(neighbor)));
    }

    /**
     * What it costs this body, in extra steps, to set foot in a cell — nothing at all unless it
     * knows of something to fear near it.
     *
     * <p><b>Zero danger is exactly zero cost.</b> Not approximately: a world with nothing
     * frightening in it produces bit-identical paths to one computed before any of this existed,
     * which is what lets the path-integrity regression pair keep meaning what it meant.
     *
     * <p><b>It never makes a cell impassable.</b> The surcharge is finite, so a body walled in by
     * things it is afraid of still finds its way out — a strong preference, not a rule. And since
     * it only ever raises a cost, the heuristic stays admissible.
     */
    private double dread(long cell) {
        if (danger.isEmpty()) {
            return 0.0;
        }
        return DREAD_COST * danger.at(unpackX(cell), unpackY(cell), unpackZ(cell));
    }

    /**
     * How much this body would rather not go back to a place that has already beaten it — nothing
     * at all for a body that has not lately been having trouble, which is nearly all of them.
     *
     * <p>Separate from {@link #dread} because they price different things: fear is about what might
     * happen, this is about what already did. They add rather than compete.
     *
     * <p>Like fear, it never makes a cell impassable: the surcharge is finite, so a body with no
     * alternative pays and goes. The point is to make the OTHER doorway win when there is
     * one. It only ever raises a cost, so the heuristic stays admissible exactly as dread does.
     */
    private double grudge(long cell) {
        if (this.setbacks.isEmpty()) {
            return 0.0;
        }
        return SETBACK_COST * this.setbacks.at(unpackX(cell), unpackY(cell), unpackZ(cell));
    }

    /**
     * How much more tiresome this body finds the ground under one cell than the best ground there
     * is — nothing at all unless the request named whose legs these are.
     *
     * <p><b>A cost, not a tie-break.</b> Breaking ties between equally cheap routes at random buys
     * almost nothing: this model prices a move at its true Euclidean length, so exact ties are rare
     * and the ones that exist are usually two spellings of one line. Eight seeds produced 1.5
     * distinct routes per trip that way, and lines 1.4 blocks apart. What spreads people out is
     * disagreeing about what the ground is worth.
     *
     * <p><b>It is bounded, and the bound is the promise.</b> Every move is multiplied by at most
     * {@code 1 + ROUGHNESS}, so a route chosen under one body's opinion of the ground is within
     * {@code ROUGHNESS} of the genuine optimum. {@link #ROUGHNESS_MIDPOINT} spends a little more of
     * that budget to keep the search affordable, taking the whole promise to about 3%. Over 48
     * open-ground trips the worst detour measured 0.22%, for eight distinct routes per eight
     * settlers and lines up to 3.2 blocks apart.
     *
     * <p><b>Coherent, not noise.</b> The value is drawn per patch of {@code 1 << ROUGHNESS_SHIFT}
     * cells rather than per cell. That is what bends a route instead of merely roughening one:
     * independent per-cell noise is a random walk that cancels over any distance worth walking,
     * while a patch big enough to walk across is a reason to go round.
     *
     * <p>Unrelated to {@link #dread}, which is added after this and never scaled by
     * it: what a body is afraid of is not a matter of taste.
     */
    private double roughness(long cell) {
        if (this.variety == 0L) {
            return 0.0;
        }
        long h = this.variety
                ^ (unpackX(cell) >> ROUGHNESS_SHIFT) * 0xBF58476D1CE4E5B9L
                ^ (unpackY(cell) >> ROUGHNESS_SHIFT) * 0xD6E8FEB86659FD93L
                ^ (unpackZ(cell) >> ROUGHNESS_SHIFT) * 0x94D049BB133111EBL;
        // SplittableRandom's mixer: cheap, and it has to be a good one — neighbouring patches
        // differ by a single small addend, and a weak mix would let them stay neighbours in value
        // too, which is a gradient every body in the world would lean down the same way.
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return ROUGHNESS * ((h >>> 11) * 0x1.0p-53); // the top 53 bits as [0, 1)
    }

    /**
     * The widest disagreement two bodies may have about one patch of ground, as a fraction of what
     * crossing it costs — and so also the worst any of them can be off the true optimum.
     *
     * <p>Two percent is where the measured curve turns over: below it settlers start filing back
     * into one line (1% still fans them out, 0.5% barely does), and above it the extra spread is
     * bought with detours a player can see. See {@link #roughness} for the numbers.
     */
    private static final double ROUGHNESS = 0.02;
    /**
     * How big one patch of ground is, as a power of two: four cells to a side. About the size of a
     * thing you would walk round rather than over. That is the whole idea — see {@link #roughness}.
     */
    private static final int ROUGHNESS_SHIFT = 2;

    /**
     * How many steps of detour one unit of danger is worth.
     *
     * <p>Sized against the field's inverse-square falloff so that the result reads the way the
     * behaviour is described: standing next to a remembered creeper costs about a dozen steps
     * (never go there), five blocks away about half a step (lean away), ten blocks away almost
     * nothing (a route that happens to pass wide is not worth bending).
     */
    private static final double DREAD_COST = 8.0;

    /**
     * How many steps of detour one unit of remembered trouble is worth.
     *
     * <p>Under {@link #DREAD_COST} deliberately, and the gap is the argument: a creeper might kill
     * you, whereas a doorway that wedged you once merely wasted a second. Against the field's
     * inverse-square falloff this reads as about six steps on the exact spot that beat you, a step
     * and a half two blocks off, a quarter of a step five blocks off — a lean, not a wall.
     *
     * <p>Sized to lose to a genuine detour: where the only way through is the way that beat us the
     * body tries again along much the same line, and the strength counter is what makes the second
     * and third attempts push harder.
     */
    private static final double SETBACK_COST = 6.0;

    /**
     * Euclidean distance on the horizontal plane, or the height still to be made up, whichever is
     * larger. The horizontal half stays admissible because <em>every move costs at least its
     * horizontal Euclidean length</em> (walk 1, diagonal √2, strides exactly their length, jump
     * 2 ≥ 1, drops ≥ 1). (Octile was the tight bound for unit moves only; a (1,2) stride at √5
     * undercuts its 1+0.414 estimate, so strides forced the switch.)
     *
     * <p><b>Height is the LARGER of the two, never the sum.</b> A 3-deep drop covers three vertical
     * blocks for one step's cost, so summing would overestimate — but each is a lower bound on its
     * own, and the greater of two lower bounds is still one. Useful because with no vertical
     * term a goal straight down reads as distance ZERO from everywhere above it, and the search
     * fans out through the whole body of water.
     *
     * <p>The two rates are what the cheapest move of each kind charges per block of height. Down,
     * a long plunge, whose {@link #dropCost} tends to 0.3 a block from above and never reaches it.
     * UP, a step onto a partial floor: a whole walk for {@link MoveCapabilities#STEP_UP} of a
     * block, so 1.67, and 1.5 keeps a margin under it. A cheaper way down or up would move these
     * with it.
     */
    private double heuristic(int x, int y, int z) {
        double dx = x - this.goalX;
        double dz = z - this.goalZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        int dy = y - this.goalY;
        double base = Math.max(horizontal, dy > 0 ? dy * CHEAPEST_DESCENT : -dy * CHEAPEST_CLIMB);
        return this.variety == 0L ? base : base * ROUGHNESS_MIDPOINT;
    }

    /**
     * What a seeded search adds to the estimate above: the roughness an average patch of ground
     * carries, so the guess meets the prices halfway.
     *
     * <p><b>Without this the feature is unaffordable.</b> {@link #roughness} only ever raises a
     * price, so leaving the estimate alone keeps it a lower bound and keeps A* exactly optimal —
     * and that is precisely the trap. A* must expand every node whose f undercuts the true cost, so
     * an estimate loose by 2% of the distance admits every node within 2% of optimal. Over open
     * ground, where the estimate is otherwise nearly exact, that band is enormous: measured at
     * <b>five times</b> the expansions and nearly five times the wall clock over six long
     * plain-crossings — the common case, and the worst one.
     *
     * <p>Assuming the average patch instead costs the guarantee of an exactly optimal route
     * <em>under the seeded prices</em>, one already given up the moment the prices were bent. The
     * standard weighted-A* bound replaces it and the two compose: within
     * {@code (1 + ROUGHNESS/2) × (1 + ROUGHNESS)}, near enough 3%, of the genuine best, and measured
     * an order of magnitude inside that. Those same plain-crossings run 16% <em>faster</em> than an
     * unseeded search; the 186-station gauntlet, mostly searches that exhaust their budget and so
     * have no estimate left to be helped by, pays 6%.
     *
     * <p>Half is the exact midpoint of a value drawn uniformly from {@code [0, ROUGHNESS)}.
     * Raising it buys speed against the same bound (0.75 searched <em>two-thirds faster</em>); that
     * is a different feature. An unseeded search never sees any of this: {@code variety == 0} takes
     * the estimate untouched, so the canonical answer stays admissible, optimal, and bit-identical.
     */
    private static final double ROUGHNESS_MIDPOINT = 1.0 + ROUGHNESS / 2.0;

    /** Least a move can cost per block it descends — {@link #dropCost}'s asymptote, never met. */
    private static final double CHEAPEST_DESCENT = 0.3;
    /** Least a move can cost per block it climbs, with a margin under the real floor of 1.67. */
    private static final double CHEAPEST_CLIMB = 1.5;

    /**
     * How close a cell is to the goal <em>for choosing the partial-path endpoint</em> — full 3-D
     * distance, unlike the (horizontal-only, admissible) search heuristic. With the
     * horizontal metric, the ground at the base of an unreachable tower scored zero and every
     * partial path marched the person to stand under the target; in 3-D a summit near the goal's
     * height wins instead, so "as close as I could get" includes altitude.
     */
    private double partialScore(int x, int y, int z) {
        double dx = x - this.goalX;
        double dy = y - this.goalY;
        double dz = z - this.goalZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Walks the parent chain from {@code end} back to the start and emits it forward. */
    private Path reconstruct(long end, boolean reachedGoal, boolean sealed, int reachableCells) {
        Deque<Waypoint> chain = new ArrayDeque<>();
        long key = end;
        Node node = this.nodes.get(key);
        while (node.parent != NO_PARENT) {
            chain.addFirst(new Waypoint(unpackX(key), unpackY(key), unpackZ(key), node.move,
                    node.surface16));
            key = node.parent;
            node = this.nodes.get(key);
        }
        return new Path(new ArrayList<>(chain), reachedGoal, sealed, reachableCells);
    }

    // Positions are packed into a long with BlockPos.asLong's layout (x: 26 bits, z: 26, y: 12) —
    // the same key the compat layer will naturally have on hand. core/ carries its own copy of the
    // trivial bit math rather than importing Minecraft.

    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
    }

    static int unpackX(long key) {
        return (int) (key >> 38); // x sits at the top: arithmetic shift sign-extends it
    }

    static int unpackZ(long key) {
        return (int) (key << 26 >> 38);
    }

    static int unpackY(long key) {
        return (int) (key << 52 >> 52);
    }

    /**
     * Binary min-heap of (f, position) entries with lazy duplicates (see {@link #search}'s closed
     * check). Ties in f pop the most recently pushed entry first — deterministic, and on cost
     * plateaus it digs toward the goal instead of flood-filling.
     */
    private static final class OpenHeap {
        private double[] f = new double[64];
        private long[] pos = new long[64];
        private int[] seq = new int[64];
        private int size;
        private int counter;

        boolean isEmpty() {
            return this.size == 0;
        }

        void push(long position, double fScore) {
            if (this.size == this.f.length) {
                this.f = Arrays.copyOf(this.f, this.size * 2);
                this.pos = Arrays.copyOf(this.pos, this.size * 2);
                this.seq = Arrays.copyOf(this.seq, this.size * 2);
            }
            int i = this.size++;
            this.f[i] = fScore;
            this.pos[i] = position;
            this.seq[i] = this.counter++;
            siftUp(i);
        }

        long pop() {
            long top = this.pos[0];
            this.size--;
            if (this.size > 0) {
                move(this.size, 0);
                siftDown(0);
            }
            return top;
        }

        private void siftUp(int i) {
            double fi = this.f[i];
            long pi = this.pos[i];
            int si = this.seq[i];
            while (i > 0) {
                int parent = (i - 1) >> 1;
                if (!lessThan(fi, si, this.f[parent], this.seq[parent])) break;
                move(parent, i);
                i = parent;
            }
            this.f[i] = fi;
            this.pos[i] = pi;
            this.seq[i] = si;
        }

        private void siftDown(int i) {
            double fi = this.f[i];
            long pi = this.pos[i];
            int si = this.seq[i];
            while (true) {
                int child = 2 * i + 1;
                if (child >= this.size) break;
                int right = child + 1;
                if (right < this.size && lessThan(this.f[right], this.seq[right], this.f[child], this.seq[child])) {
                    child = right;
                }
                if (!lessThan(this.f[child], this.seq[child], fi, si)) break;
                move(child, i);
                i = child;
            }
            this.f[i] = fi;
            this.pos[i] = pi;
            this.seq[i] = si;
        }

        private boolean lessThan(double fa, int seqA, double fb, int seqB) {
            if (fa != fb) return fa < fb;
            return seqA > seqB; // newest first on ties
        }

        private void move(int from, int to) {
            this.f[to] = this.f[from];
            this.pos[to] = this.pos[from];
            this.seq[to] = this.seq[from];
        }
    }
}
