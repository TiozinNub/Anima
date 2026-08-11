package dev.luizloyola.anima.core.nav;

import dev.luizloyola.anima.core.brain.sense.DangerField;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A* over a {@link NavGrid}, answering a {@link PathRequest} with a {@link Path}. Pure and
 * stateless per call ({@link #find} builds a private search instance), so it is safe on a worker
 * thread: the search sees an immutable classification, never the live world.
 *
 * <p>The neighbour model is parameterized by {@link MoveCapabilities} — cardinal walk, jump-up-1,
 * drop-up-to-{@code maxDrop}, level diagonals that refuse to cut corners, plus {@link #STRIDES},
 * longer flat steps at intermediate angles. Deep holes and {@link CellType#DANGER} cells produce no
 * neighbour at all.
 *
 * <p>An unreachable goal (walled off, outside the grid, or {@code maxNodes} spent) returns the
 * path to the expanded cell closest to it (smallest heuristic, ties to the cheaper), flagged
 * {@code reachedGoal=false}. Expansion is deterministic: f-ties break toward the most recently
 * pushed node, neighbour order is fixed.
 */
public final class Pathfinder {
    private static final double SQRT2 = Math.sqrt(2.0);
    /** Cost of one cardinal walk step; the unit every other cost is relative to. */
    private static final double WALK_COST = 1.0;
    private static final double DIAGONAL_COST = SQRT2;
    /** Jumping is slow (stall, arc) — twice a plain step, so ramps beat hop-scotch when both exist. */
    private static final double JUMP_COST = 2.0;
    /**
     * Leap cost by gap width (index = gap). Each exceeds the covered distance (gap+1, the
     * admissibility floor) by a risk premium that grows STEEPLY: per block covered 1.2, 1.8 and
     * 2.4, against a plain walk's 1.0 and the careful factor's 2.2. The old flat premiums
     * (2.4 / 3.6 / 5.0) priced a 3-block sprint jump under careful walking, making the riskiest
     * move the cheapest way past an obstacle.
     *
     * <p>The 1-gap price is untouched: leaping a shallow trench must keep beating dipping through
     * it (2.4 &lt; drop 1.0 + jump 2.0), and hopping a one-wide stream must keep beating wading.
     *
     * <p>Do not multiply by {@link #carefulFactor}: it is the follower's throttle as time and a leap
     * is never throttled — charging it priced a one-wide water hop above swimming.
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
        boolean closed;
    }

    private final Map<Long, Node> nodes = new HashMap<>();
    private final OpenHeap open = new OpenHeap();
    /** Careful-ground memo: each cell is probed by every incident edge, and one probe costs ~20
     *  grid reads — cache it per search. */
    private final Map<Long, Boolean> carefulCache = new HashMap<>();
    /**
     * What this body would rather not walk past. A snapshot taken before the search left the
     * server thread — see {@link PathRequest#of(int, int, int, int, int, int, MoveCapabilities,
     * DangerField)}.
     */
    private final DangerField danger;

    /**
     * Where the body may stand at all — {@link PathRequest#domain()}. Enforced here at the one
     * funnel every move generator feeds, on the FEET cell of the offered node: a fenced search
     * cannot so much as consider a cell outside it. (A leap still arcs over cells it never
     * stands in, exactly as a player clears a gap.)
     */
    private final NavDomain domain;

    private Pathfinder(NavGrid grid, PathRequest request) {
        this.grid = grid;
        this.profile = request.profile();
        this.danger = request.danger();
        this.domain = request.domain();
        this.goalX = request.goalX();
        this.goalY = request.goalY();
        this.goalZ = request.goalZ();
    }

    /** Never returns {@code null}. */
    public static Path find(NavGrid grid, PathRequest request) {
        return new Pathfinder(grid, request).search(request);
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
        this.open.push(start, heuristic(request.startX(), request.startZ()));

        long best = start;
        double bestScore = partialScore(request.startX(), request.startY(), request.startZ());
        double bestG = 0.0;

        int expanded = 0;
        while (!this.open.isEmpty()) {
            long current = this.open.pop();
            Node node = this.nodes.get(current);
            // The heap holds stale duplicates instead of supporting decrease-key; the first pop of
            // a position carries its best f, later pops of it are no-ops.
            if (node.closed) continue;
            node.closed = true;

            if (current == goal) {
                return reconstruct(current, true);
            }
            double score = partialScore(unpackX(current), unpackY(current), unpackZ(current));
            if (score < bestScore || (score == bestScore && node.g < bestG)) {
                best = current;
                bestScore = score;
                bestG = node.g;
            }
            if (++expanded >= request.maxNodes()) {
                break;
            }
            expandNeighbors(current, node);
        }
        return reconstruct(best, false);
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
        swimNeighbors(current, node, x, y, z);
    }

    /**
     * Water moves, generated only for a swimmer ({@link MoveCapabilities#canSwim()}): a body in the
     * water strokes to anywhere within reach or climbs out onto a bank, and a body on land steps
     * off the shore into water. All of it lives in this one method so the water moves have a
     * single home.
     */
    private void swimNeighbors(long current, Node node, int x, int y, int z) {
        if (!this.profile.canSwim()) return;
        if (isSurfaceSwim(x, y, z)) {
            for (int[] d : CARDINALS) swimCross(current, node, x, y, z, d[0], d[1]);
            for (int[] d : DIAGONALS) swimCrossDiagonal(current, node, x, y, z, d[0], d[1]);
            for (int[] d : CARDINALS) swimExit(current, node, x, y, z, d[0], d[1]);
        } else {
            for (int[] d : CARDINALS) swimEnter(current, node, x, y, z, d[0], d[1]);
        }
    }

    /**
     * Whether feet-cell {@code (x,y,z)} is a floating spot at the water surface — the topmost water
     * block of a column: feet in water, the {@code height-1} cells above air, so the head is above
     * the waterline (occupiable, no drowning).
     */
    private boolean isSurfaceSwim(int x, int y, int z) {
        if (this.grid.cell(x, y, z) != CellType.WATER) return false;
        for (int i = 1; i <= this.profile.topCell(0.0); i++) {
            if (this.grid.cell(x, y + i, z) != CellType.PASSABLE) return false;
        }
        return true;
    }

    /** One surface stroke to an adjacent water-surface cell at the same level (connected water is flat). */
    private void swimCross(long current, Node node, int x, int y, int z, int dx, int dz) {
        int nx = x + dx;
        int nz = z + dz;
        if (isSurfaceSwim(nx, y, nz)) {
            relax(current, node, pack(nx, y, nz), FLOATING, MoveType.SWIM, SWIM_COST);
        }
    }

    /**
     * A diagonal surface stroke: both flanks must also be open water, so the body can't clip a
     * corner block mid-stroke (the same corner-cut guard the on-land diagonal uses).
     */
    private void swimCrossDiagonal(long current, Node node, int x, int y, int z, int dx, int dz) {
        int nx = x + dx;
        int nz = z + dz;
        if (isSurfaceSwim(nx, y, nz) && isSurfaceSwim(nx, y, z) && isSurfaceSwim(x, y, nz)) {
            relax(current, node, pack(nx, y, nz), FLOATING, MoveType.SWIM, SWIM_COST * SQRT2);
        }
    }

    /**
     * Step off the shore into the neighbouring water column: onto a surface level with the bank, or
     * up to {@code maxDrop} below it, since water negates the fall. The far column must be open at
     * our level first. Tagged {@link MoveType#SWIM} — the feet land in water.
     */
    private void swimEnter(long current, Node node, int x, int y, int z, int dx, int dz) {
        int nx = x + dx;
        int nz = z + dz;
        if (isSurfaceSwim(nx, y, nz)) { // water level with the bank: step straight in
            relax(current, node, pack(nx, y, nz), FLOATING, MoveType.SWIM, SWIM_COST);
            return;
        }
        if (!hasClearance(nx, y, nz)) return; // can't even move into the near column
        int waterline = y - 1;
        int limit = y - this.profile.maxDrop();
        while (waterline >= limit && this.grid.cell(nx, waterline, nz) == CellType.PASSABLE) {
            waterline--; // fall through the air above the water
        }
        if (waterline >= limit && isSurfaceSwim(nx, waterline, nz)) {
            relax(current, node, pack(nx, waterline, nz), FLOATING, MoveType.SWIM, SWIM_COST);
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
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                if (cx == x && cz == z) continue; // where we stand — standable by construction
                // Every cell swept must be footing the body could walk across without a step up:
                // a stride is one long straight move, so anything it can't take in its own stride
                // must degrade to the unit moves that price the climb properly.
                if (!walkableFlank(cx, y, cz, from)) return;
                // Strides are open-ground moves: careful ground (rim lanes, narrow bridges)
                // takes unit steps at the careful cost instead — precise and correctly priced.
                if (isCareful(cx, y, cz)) return;
            }
        }
        relax(current, node, pack(x + dx, y, z + dz), footing(x + dx, y, z + dz), MoveType.WALK, cost);
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
                    JUMP_COST * carefulFactor(x, y, z, nx, ny, nz));
            return;
        }
        if (rise >= -STEP_UP) {
            relax(current, node, pack(nx, ny, nz), to, MoveType.WALK,
                    WALK_COST * carefulFactor(x, y, z, nx, ny, nz));
            return;
        }
        // A fall. The scan bounds the CELL to maxDrop, but a raised takeoff makes the real fall
        // deeper than the cells suggest — standing on a slab and dropping maxDrop cells is half a
        // block further than this body agreed to.
        double depth = -rise;
        if (depth > this.profile.maxDrop()) return;
        relax(current, node, pack(nx, ny, nz), to, MoveType.DROP,
                dropCost(depth) * carefulFactor(x, y, z, nx, ny, nz));
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
                    Math.max(DIAGONAL_COST, JUMP_COST) * carefulFactor(x, y, z, nx, ny, nz));
            return;
        }
        if (rise >= -STEP_UP) {
            relax(current, node, pack(nx, ny, nz), to, MoveType.WALK,
                    DIAGONAL_COST * carefulFactor(x, y, z, nx, ny, nz));
            return;
        }
        double depth = -rise;
        if (depth > this.profile.maxDrop()) return;
        relax(current, node, pack(nx, ny, nz), to, MoveType.DROP,
                Math.max(DIAGONAL_COST, dropCost(depth)) * carefulFactor(x, y, z, nx, ny, nz));
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
     * absolute y — or {@link #NO_FOOTING}. Every move is a pair of footings and the rise between.
     *
     * <p>A cell never has footing both ways: nodes are keyed by cell, so one standing place
     * answering to two cells would be two nodes with two costs and parents. Hence a
     * {@link CellType#STEP} is its <em>own</em> feet-cell, never a floor for the cell above.
     */
    private double footing(int x, int y, int z) {
        CellType here = this.grid.cell(x, y, z);
        if (here == CellType.STEP) {
            double surface = this.grid.surface(x, y, z);
            return fits(x, y, z, surface) ? y + surface : NO_FOOTING;
        }
        if (here != CellType.PASSABLE) {
            return NO_FOOTING; // solid, harmful, water, or off the edge of the world
        }
        if (this.grid.cell(x, y - 1, z) != CellType.GROUND) {
            return NO_FOOTING; // nothing under us — or a STEP, which is its own feet-cell
        }
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

    /** The cost factor for a unit move between the two feet-cells: careful at either end means
     *  the follower walks it slowly (and it borders something worth not stumbling into). */
    private double carefulFactor(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return isCareful(fromX, fromY, fromZ) || isCareful(toX, toY, toZ) ? CAREFUL_COST_FACTOR : 1.0;
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
        int ny = unpackY(neighbor);
        if (!this.domain.contains(unpackX(neighbor), ny, unpackZ(neighbor))) {
            return; // outside the fence there is no world, not merely a worse one
        }
        int surface16 = footing == NO_FOOTING ? 0
                : Math.max(0, Math.min(15, (int) Math.round((footing - ny) * 16.0)));
        double g = from.g + cost + dread(neighbor);
        Node node = this.nodes.get(neighbor);
        if (node == null) {
            node = new Node();
            node.g = g;
            node.parent = current;
            node.move = move;
            node.surface16 = surface16;
            this.nodes.put(neighbor, node);
        } else if (!node.closed && g < node.g) {
            node.g = g;
            node.parent = current;
            node.move = move;
            node.surface16 = surface16;
        } else {
            return;
        }
        this.open.push(neighbor, g + heuristic(unpackX(neighbor), unpackZ(neighbor)));
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
     * How many steps of detour one unit of danger is worth.
     *
     * <p>Sized against the field's inverse-square falloff so that the result reads the way the
     * behaviour is described: standing next to a remembered creeper costs about a dozen steps
     * (never go there), five blocks away about half a step (lean away), ten blocks away almost
     * nothing (a route that happens to pass wide is not worth bending).
     */
    private static final double DREAD_COST = 8.0;

    /**
     * Euclidean distance on the horizontal plane. Admissible because <em>every move costs at least
     * its horizontal Euclidean length</em> (walk 1, diagonal √2, strides their length, jump 2,
     * drops ≥ 1). Octile was tight for unit moves only — a (1,2) stride at √5 undercuts its 1+0.414
     * — so strides forced the switch. Vertical distance is ignored: a 3-deep drop covers 3 blocks
     * for one step's cost.
     */
    private double heuristic(int x, int z) {
        double dx = x - this.goalX;
        double dz = z - this.goalZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

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
    private Path reconstruct(long end, boolean reachedGoal) {
        Deque<Waypoint> chain = new ArrayDeque<>();
        long key = end;
        Node node = this.nodes.get(key);
        while (node.parent != NO_PARENT) {
            chain.addFirst(new Waypoint(unpackX(key), unpackY(key), unpackZ(key), node.move,
                    node.surface16));
            key = node.parent;
            node = this.nodes.get(key);
        }
        return new Path(new ArrayList<>(chain), reachedGoal);
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
