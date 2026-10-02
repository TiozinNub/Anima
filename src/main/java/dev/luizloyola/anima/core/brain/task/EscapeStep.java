package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.need.NeedKind;
import dev.luizloyola.anima.core.agent.need.NeedLevel;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.continuity.Ephemeral;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.NavGrid;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One move toward getting out of somewhere this body cannot walk out of.
 *
 * <p><b>One step, then look again</b>, not a compiled plan: an escape changes the world with every
 * block it removes, and "am I still shut in" is re-answered by the next route search. Every step
 * strictly enlarges what the body can reach — a monotone loop.
 *
 * <p><b>Chosen by shape</b>: a body in a room is walled in sideways, a body down a hole upward. A
 * sideways cut is only offered where there is already-open, standable ground on the far side of the
 * wall; without that check it applies nearly everywhere, and in a pit it is the cheapest rung and a
 * useless one.
 *
 * <p><b>And from anywhere the body can reach</b>, not only underfoot: a cut costs its blocks and
 * walking to it a little per block, so a way out four steps away can beat one underfoot needing two
 * more blocks broken — without that, a body that cut its way onto a roof refuses to dig while the
 * move that frees it waits at the rim (2026-08-12).
 *
 * <p>The rungs, in preference order:
 * <ol>
 *   <li><b>Rise</b> — in water and short of air, or shut in with the head under, swim to the
 *       nearest air first and tread there until the lungs are full.
 *   <li><b>Straight up</b> — no swim to air: cut the ceiling over the head and rise through it,
 *       proven way out or not. Nothing else is cut under water unless the breath is still easy:
 *       a body sealed in a flooded gap once cut a stair sideways into the rock until it drowned.
 *   <li><b>Step out</b> — cut through the wall beside us, only where the way out is sideways.
 *   <li><b>Cut a stair</b> — open the two cells above the block next door and step up, our own
 *       ceiling first. The way out of anything with a lid.
 *   <li><b>Lower yourself</b> — break the floor and drop, until what is left is a fall this body
 *       can take.
 *   <li><b>Say so</b> — no arm, nothing cuttable, or every rung refused. Last and a
 *       FAILURE, so the drive takes its cooldown and the board hears about it.
 * </ol>
 *
 * <p>The cutting rungs need an arm ({@link ProfileAspect#BODY_CAN_DIG}); a body without one still
 * notices it is shut in and still says so.
 */
public final class EscapeStep implements CompoundTask {

    private final List<Method> methods =
            List.of(new Rise(), new StraightUp(), new StepOut(), new CutAStair(),
                    new LowerYourself(), new SaySo());

    @Override
    public List<Method> methods() {
        return this.methods;
    }

    @Override
    public String describe() {
        return "get out of here";
    }

    /**
     * No, though escaping breaks structural blocks by definition. {@link Task#reshapesGround()}
     * means "work that put the body here, so do not judge it stuck while it runs"; this is the
     * opposite, and its loop condition is the confinement verdict. Answering yes would switch that
     * verdict off the instant the first block came out: cut, stop, wander off, come back, cut.
     *
     * <p>Written down rather than left to the default, so a later tidy-up pass reads it first.
     */
    @Override
    public boolean reshapesGround() {
        return false;
    }

    // ── reading the world ────────────────────────────────────────────────────────────────────

    /** The four ways out, in the fixed order that makes the choice deterministic. */
    private static final int[][] CARDINALS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    private static boolean canDig(BrainContext ctx) {
        return ctx.profile().b(ProfileAspect.BODY_CAN_DIG);
    }

    /**
     * Whether this cell holds something an arm could take out of the way. Water never is, and an
     * unloaded cell is refused for being unseen — swinging at what you cannot see opens a wall into
     * a lake. {@code BlockBreaker.begin} makes the final call, refusing bedrock, a claimed cell or
     * one out of reach and failing the step so the next picks differently.
     */
    private static boolean cuttable(BrainContext ctx, int x, int y, int z) {
        BlockKind kind = ctx.percepts().blocks().at(x, y, z);
        return kind != BlockKind.AIR && kind != BlockKind.WATER && kind != BlockKind.UNKNOWN;
    }

    private static boolean open(BrainContext ctx, int x, int y, int z) {
        return ctx.percepts().blocks().at(x, y, z) == BlockKind.AIR;
    }

    /**
     * Whether cutting here would let something in that the body cannot deal with. Water floods,
     * and an unloaded cell is an unread one — see {@link #cuttable}.
     */
    private static boolean unsafe(BrainContext ctx, int x, int y, int z) {
        BlockKind kind = ctx.percepts().blocks().at(x, y, z);
        return kind == BlockKind.WATER || kind == BlockKind.UNKNOWN;
    }

    /** How many cells of this body's column are not already open — what a cut would cost. */
    private static int cutsToClear(BrainContext ctx, int x, int y, int z, int cells) {
        int count = 0;
        for (int i = 0; i < cells; i++) {
            if (!open(ctx, x, y + i, z)) {
                count++;
            }
        }
        return count;
    }

    /** Adds the cells of a column that need opening, bottom-up, to {@code out}. */
    private static void cutsFor(BrainContext ctx, int x, int y, int z, int cells, List<Pos> out) {
        for (int i = 0; i < cells; i++) {
            if (!open(ctx, x, y + i, z)) {
                out.add(new Pos(x, y + i, z));
            }
        }
    }

    /** How many cells this body occupies standing up. */
    private static int height(BrainContext ctx) {
        return MoveCapabilities.of(ctx.profile()).clearCells();
    }

    /**
     * How thick a wall is worth punching through. Past this it stops being a wall and starts being
     * a hillside, and cutting a stair up out of the hole is both cheaper and likelier to reach
     * anywhere.
     */
    private static final int WALL_LIMIT = 3;

    private static void narrate(BrainContext ctx, String event, String detail) {
        ctx.journal().record(Category.BRAIN, event, detail);
    }

    /**
     * What one block of walking is worth against one block of cutting — dirt is the better part of a
     * second, stone several, so roughly "four steps to save a swing".
     */
    private static final double WALK_PER_BLOCK = 0.25;

    /**
     * How far across the region a way out is looked for. A proved prison is small by construction,
     * so this almost never bites; it is here so that the one case which is not — a body shut into
     * something large — cannot turn a per-second percept into a survey of a warehouse.
     */
    private static final int SCAN_RADIUS = 24;

    /**
     * One way out: where to stand to take it, what to cut from there, and where it comes out
     * ({@code null} when the act is its own arrival, as going downward is).
     */
    private record Option(Pos from, List<Pos> cuts, @Nullable Pos into, double cost) {
    }

    /**
     * The best each rung can do anywhere in reach, worked out once. Memoised per task instance: the
     * arbiter builds a fresh {@code EscapeStep} per grant, and {@link Method#applicable},
     * {@link Method#estimateCost} and {@link Method#decompose} would otherwise each re-scan.
     */
    @Ephemeral("a memo of this grant's look around, asked again when a method is next chosen")
    private @Nullable Option[] scan;

    private Option[] scan(BrainContext ctx) {
        if (this.scan == null) {
            this.scan = survey(ctx);
        }
        return this.scan;
    }

    private static final int STEP_OUT = 0;
    private static final int STAIR = 1;
    private static final int LOWER = 2;

    /**
     * The best each rung can do — <b>from here if anything can be done from here, and only
     * otherwise from anywhere in reach</b>.
     *
     * <p>Underfoot first is not an optimisation: cost counts blocks to cut and nothing counts as
     * PROGRESS, so pooling the region scores a cheap tread at the bottom of a shaft like the one at
     * the top that would free the body — live (2026-08-13), a settler spent two minutes walking its
     * own staircase opening the cheapest tread. "Here" is where the last act left the body, the only
     * progress signal this ladder has.
     *
     * <p>The region is the FALLBACK (a body on a roof with the rim four steps away), and reads one
     * cell instead of hundreds.
     */
    private static Option[] survey(BrainContext ctx) {
        Option[] best = new Option[3];
        if (!canDig(ctx) || (inWater(ctx) && breath(ctx) > 0.0)) {
            return best;
        }
        Pos here = ctx.percepts().position();
        best[STEP_OUT] = stepOutFrom(ctx, here, 0.0);
        best[STAIR] = stairFrom(ctx, here, 0.0);
        best[LOWER] = lowerFrom(ctx, here, 0.0);
        if (best[STEP_OUT] != null || best[STAIR] != null || best[LOWER] != null) {
            return best;
        }
        for (Pos from : reachable(ctx)) {
            double walk = WALK_PER_BLOCK * distance(here, from);
            best[STEP_OUT] = cheaper(best[STEP_OUT], stepOutFrom(ctx, from, walk));
            best[STAIR] = cheaper(best[STAIR], stairFrom(ctx, from, walk));
            best[LOWER] = cheaper(best[LOWER], lowerFrom(ctx, from, walk));
        }
        return best;
    }

    /**
     * Everywhere this body could stand — the proved region when there is one, and otherwise just
     * where it is. The fallback matters: the rungs have to work for a body that has not been proved
     * shut in at all, which is every use of this class outside the drive that owns it.
     */
    private static List<Pos> reachable(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        List<Pos> region = ctx.percepts().confinement().region();
        if (region.isEmpty()) {
            return List.of(here);
        }
        List<Pos> near = new ArrayList<>(region.size());
        for (Pos cell : region) {
            if (distance(here, cell) <= SCAN_RADIUS) {
                near.add(cell);
            }
        }
        return near.isEmpty() ? List.of(here) : near;
    }

    private static double distance(Pos a, Pos b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static @Nullable Option cheaper(@Nullable Option a, @Nullable Option b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.cost() < a.cost() ? b : a;
    }

    /** The walk to where a way out is, then the cutting, then stepping through it. */
    private static List<Task> steps(BrainContext ctx, Option option) {
        Pos here = ctx.percepts().position();
        List<Task> tasks = new ArrayList<>(option.cuts().size() + 2);
        if (!here.equals(option.from())) {
            tasks.add(new GoTo(option.from().x(), option.from().y(), option.from().z())
                    .leavingShelter());
        }
        for (Pos cut : option.cuts()) {
            tasks.add(new BreakBlock(cut.x(), cut.y(), cut.z()));
        }
        if (option.into() != null) {
            tasks.add(new GoTo(option.into().x(), option.into().y(), option.into().z())
                    .leavingShelter());
        }
        return tasks;
    }

    // ── rung 0: rise ─────────────────────────────────────────────────────────────────────────

    /**
     * The level at which a body in water drops everything for air: {@code short}, whatever else it
     * is doing (Luiz, 2026-10-02). The bar used to sit between short and gasping, and a settler at
     * the surface with her eyes under ate and wandered until she drowned.
     */
    private static final NeedLevel SHORT = NeedKind.BREATH.level("short").orElseThrow();
    /** How much water is searched for air. A lake's surface is rarely far; a flooded cave may be. */
    private static final int AIR_SEARCH_CELLS = 512;

    /** Whether the body's feet are in water — where its breath can run out. */
    private static boolean inWater(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        return ctx.percepts().terrain().cell(here.x(), here.y(), here.z()) == CellType.WATER;
    }

    private static double breath(BrainContext ctx) {
        return ctx.percepts().needs().pressure(NeedKind.BREATH);
    }

    /**
     * In water and short of air, so getting out outranks everything. Whether the head is under is
     * not asked: a swimmer's eyes ride below the surface with open air over its cell, and that is
     * where a settler drowned while the old head check called her free (forest, 2026-10-02).
     */
    public static boolean shortOfAir(BrainContext ctx) {
        return inWater(ctx) && ctx.percepts().needs().gauge(NeedKind.BREATH)
                .map(air -> air.value() <= SHORT.value(ctx.profile()))
                .orElse(false);
    }

    /** In water with something other than air over its head cell — water, or a flooded gap's rock. */
    private static boolean headUnder(BrainContext ctx) {
        if (!inWater(ctx)) {
            return false;
        }
        Pos here = ctx.percepts().position();
        int head = here.y() + height(ctx) - 1;
        return ctx.percepts().terrain().cell(here.x(), head, here.z()) != CellType.PASSABLE;
    }

    /** Whether getting to air is this ladder's first business: short of it, or shut in under water. */
    private static boolean airFirst(BrainContext ctx) {
        return shortOfAir(ctx) || headUnder(ctx);
    }

    @Ephemeral("a memo, asked again when a method is next chosen")
    private @Nullable Pos air;
    @Ephemeral("goes with the memo above")
    private boolean airSought;

    /**
     * The nearest water cell with air over it, through water only, or null. Six-way through water
     * rather than a route search: the body may be in a gap no standing body fits, and the question
     * is only where the surface is.
     */
    private @Nullable Pos air(BrainContext ctx) {
        if (!this.airSought) {
            this.airSought = true;
            this.air = nearestAir(ctx.percepts().terrain(), ctx.percepts().position());
        }
        return this.air;
    }

    private static final int[][] SIX = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
            {0, -1, 0}};

    static @Nullable Pos nearestAir(NavGrid grid, Pos from) {
        ArrayDeque<Pos> open = new ArrayDeque<>();
        Set<Pos> seen = new HashSet<>();
        open.add(from);
        seen.add(from);
        while (!open.isEmpty() && seen.size() <= AIR_SEARCH_CELLS) {
            Pos cell = open.poll();
            if (grid.cell(cell.x(), cell.y(), cell.z()) == CellType.WATER
                    && grid.cell(cell.x(), cell.y() + 1, cell.z()) == CellType.PASSABLE) {
                return cell;
            }
            for (int[] d : SIX) {
                Pos next = new Pos(cell.x() + d[0], cell.y() + d[1], cell.z() + d[2]);
                if (seen.add(next) && grid.cell(next.x(), next.y(), next.z()) == CellType.WATER) {
                    open.add(next);
                }
            }
        }
        return null;
    }

    /**
     * Swim to the nearest air and get the breath back there. First, and costless, whenever it
     * applies; already at the surface, only the second half.
     */
    private final class Rise implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return airFirst(ctx) && air(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Pos to = air(ctx);
            if (to.equals(ctx.percepts().position())) {
                narrate(ctx, "escape", "holding its head up for air");
                return List.of(new Tread());
            }
            narrate(ctx, "escape", "swimming up for air at (" + to.x() + ", " + to.y() + ", "
                    + to.z() + ")");
            return List.of(new GoTo(to.x(), to.y(), to.z()).leavingShelter(), new Tread());
        }

        @Override
        public String describe() {
            return "swim up for air";
        }
    }

    // ── rung 0b: straight up ─────────────────────────────────────────────────────────────────

    /** How far above the feet the way up is read, in cells. */
    private static final int UP_LOOK = 8;
    /**
     * The highest cell above the feet cut from where the body floats: eyes to block centre stays
     * inside a 4.5 reach for a swimming body and a treading one alike. Anything higher waits for the
     * body to rise to it.
     */
    private static final int UP_REACH = 3;
    /** What a way up costs over the cuts when nothing above it was seen to be air. */
    private static final double UNPROVEN_UP = 1.0;

    /** The solid cells over the head within reach, bottom-up, and whether air was seen above. */
    private record WayUp(List<Pos> cuts, boolean airAbove) {
    }

    /**
     * Straight up from here, or null when up gains nothing. Water is swum through; danger or an
     * uncuttable cell stops the look, and the cut right under it is dropped so nothing pours in.
     */
    private static @Nullable WayUp wayUp(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        NavGrid terrain = ctx.percepts().terrain();
        List<Pos> cuts = new ArrayList<>();
        boolean air = false;
        boolean lid = false;
        for (int dy = 1; dy <= UP_LOOK; dy++) {
            int y = here.y() + dy;
            CellType cell = terrain.cell(here.x(), y, here.z());
            if (cell == CellType.WATER) {
                continue;
            }
            if (cell == CellType.PASSABLE || cell == CellType.CLIMB) {
                air = true;
                break;
            }
            if (cell == CellType.DANGER || !cuttable(ctx, here.x(), y, here.z())) {
                if (!cuts.isEmpty() && cuts.get(cuts.size() - 1).y() == y - 1) {
                    cuts.remove(cuts.size() - 1);
                }
                break;
            }
            if (dy > UP_REACH) {
                lid = true; // a lid out of reach: rise to it, and the next look cuts it
                break;
            }
            cuts.add(new Pos(here.x(), y, here.z()));
        }
        return cuts.isEmpty() && !air && !lid ? null : new WayUp(cuts, air);
    }

    @Ephemeral("a memo, asked again when a method is next chosen")
    private @Nullable WayUp up;
    @Ephemeral("goes with the memo above")
    private boolean upSought;

    private @Nullable WayUp up(BrainContext ctx) {
        if (!this.upSought) {
            this.upSought = true;
            WayUp way = wayUp(ctx);
            this.up = way != null && (way.cuts().isEmpty() || canDig(ctx)) ? way : null;
        }
        return this.up;
    }

    /**
     * Cut the ceiling over the head and rise through it — for a body under water with no swim to
     * air, whether or not anything proves the way up opens. A settler drowned in a one-cell flooded
     * gap under turf, one grass block from the sky, because nothing was cut under water unless the
     * breath was easy and the stair needed a tread beside it (forest, 2026-10-02).
     */
    private final class StraightUp implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return airFirst(ctx) && up(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            WayUp way = up(ctx);
            // Under any sideways cut of the same size: the water is already where the body is.
            return way == null ? Double.MAX_VALUE
                    : way.cuts().size() * 0.5 + (way.airAbove() ? 0.0 : UNPROVEN_UP);
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            WayUp way = up(ctx);
            List<Task> tasks = new ArrayList<>(way.cuts().size() + 1);
            if (way.cuts().isEmpty()) {
                narrate(ctx, "escape", "rising straight up for air");
            } else {
                Pos first = way.cuts().get(0);
                narrate(ctx, "escape", "cutting up for air at (" + first.x() + ", " + first.y()
                        + ", " + first.z() + ")");
            }
            for (Pos cut : way.cuts()) {
                tasks.add(new BreakBlock(cut.x(), cut.y(), cut.z()));
            }
            tasks.add(new Tread());
            return tasks;
        }

        @Override
        public String describe() {
            return "cut up for air";
        }
    }

    /**
     * Hold still in the water and let buoyancy lift the head out, until the lungs are full or the
     * body is out of the water. With no route the swimmer treads upright, head clear. Fails once the air stops coming back for
     * {@link #TREAD_PATIENCE} ticks — still under something — so the ladder looks again from
     * wherever the body has risen to.
     */
    public static final class Tread implements PrimitiveTask {
        /** Ticks without the breath coming back before treading is given up as still under. */
        public static final int TREAD_PATIENCE = 40;

        private double last = -1.0;
        private int stalled;

        public Tread() {
        }

        public Tread(double last, int stalled) {
            this.last = last;
            this.stalled = stalled;
        }

        @Override
        public TaskStatus tick(BrainContext ctx) {
            double now = breath(ctx);
            if (!inWater(ctx) || now <= 0.0) {
                return TaskStatus.SUCCESS;
            }
            if (this.last < 0.0) {
                ctx.actuators().mover().stop(); // a stroke under way keeps the eyes under
            }
            if (this.last < 0.0 || now < this.last) {
                this.stalled = 0;
            } else if (++this.stalled > TREAD_PATIENCE) {
                return TaskStatus.FAILED;
            }
            this.last = now;
            return TaskStatus.RUNNING;
        }

        @Override
        public void cancel(BrainContext ctx) {
        }

        @Override
        public String describe() {
            return "tread water";
        }

        @Override
        public String failureDetail() {
            return "still under, the air not coming back";
        }

        /** The breath pressure last read, or -1 before the first tick. */
        public double last() {
            return this.last;
        }

        /** Ticks in a row the breath has not come back. */
        public int stalled() {
            return this.stalled;
        }
    }

    // ── rung 1: step out ─────────────────────────────────────────────────────────────────────

    /**
     * Cut through the wall beside a cell and walk out of it — the cheapest way out of a room, and
     * the wrong way out of a pit. That is what the far-side probe is for.
     */
    private final class StepOut implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return scan(ctx)[STEP_OUT] != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            Option option = scan(ctx)[STEP_OUT];
            return option == null ? Double.MAX_VALUE : option.cost();
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Option option = scan(ctx)[STEP_OUT];
            narrate(ctx, "escape", "cutting out through the wall at (" + option.from().x() + ", "
                    + option.from().y() + ", " + option.from().z() + ")");
            return steps(ctx, option);
        }

        @Override
        public String describe() {
            return "cut through the wall";
        }
    }

    /**
     * The cheapest wall to punch through from {@code from}, or null when none is worth it: only
     * where there is already-open, standable ground on the OTHER side within {@link #WALL_LIMIT} —
     * the difference between a room and a hillside, read directly rather than from a heightmap.
     * Without that probe the rung applies almost everywhere, and in a pit it is cheap and useless.
     */
    private static @Nullable Option stepOutFrom(BrainContext ctx, Pos from, double walk) {
        int cells = height(ctx);
        Option best = null;
        for (int[] d : CARDINALS) {
            for (int thickness = 1; thickness <= WALL_LIMIT; thickness++) {
                int x = from.x() + d[0] * thickness;
                int z = from.z() + d[1] * thickness;
                if (anyUnsafe(ctx, x, from.y(), z, cells)
                        || !solidFloor(ctx, x, from.y() - 1, z)) {
                    break; // no floor to walk out onto, or something we will not open
                }
                if (cutsToClear(ctx, x, from.y(), z, cells) > 0) {
                    continue; // still inside the wall — keep looking for its far side
                }
                if (thickness == 1) {
                    break; // open and adjacent: this is not the wall that has us
                }
                List<Pos> cuts = new ArrayList<>();
                for (int step = 1; step < thickness; step++) {
                    cutsFor(ctx, from.x() + d[0] * step, from.y(), from.z() + d[1] * step, cells,
                            cuts);
                }
                best = cheaper(best, new Option(from, cuts,
                        new Pos(x, from.y(), z), cuts.size() + walk));
                break;
            }
        }
        return best;
    }

    // ── rung 2: cut a stair ──────────────────────────────────────────────────────────────────

    /**
     * Open the cells above the block beside a cell and step up into them — one tread of a staircase
     * cut into whatever is holding the body down. Its own ceiling goes first when there is one,
     * because a body with a lid on cannot jump.
     */
    private final class CutAStair implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return scan(ctx)[STAIR] != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            Option option = scan(ctx)[STAIR];
            return option == null ? Double.MAX_VALUE : option.cost();
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Option option = scan(ctx)[STAIR];
            narrate(ctx, "escape", "cutting a stair up to (" + option.into().x() + ", "
                    + option.into().y() + ", " + option.into().z() + ")");
            return steps(ctx, option);
        }

        @Override
        public String describe() {
            return "cut a stair upward";
        }
    }

    /** The cheapest tread to cut from {@code from}, or null when there is none. */
    private static @Nullable Option stairFrom(BrainContext ctx, Pos from, double walk) {
        if (ctx.profile().i(ProfileAspect.BODY_JUMP_HEIGHT) <= 0) {
            return null;
        }
        int cells = height(ctx);
        Option best = null;
        for (int[] d : CARDINALS) {
            int x = from.x() + d[0];
            int z = from.z() + d[1];
            // The tread itself has to be something to stand ON, so it must not be cut.
            if (!solidFloor(ctx, x, from.y(), z)) {
                continue;
            }
            if (anyUnsafe(ctx, x, from.y() + 1, z, cells)
                    || unsafe(ctx, from.x(), from.y() + cells, from.z())) {
                continue;
            }
            // Nothing to cut means the step up is already reachable, so taking it escapes nothing:
            // skipping it keeps the rung about opening a way out, not walking around indoors.
            int count = cutsToClear(ctx, x, from.y() + 1, z, cells)
                    + cutsToClear(ctx, from.x(), from.y() + cells, from.z(), 1);
            if (count == 0) {
                continue;
            }
            List<Pos> cuts = new ArrayList<>();
            // Our own lid first: without headroom over the feet-cell there is no jump to make.
            cutsFor(ctx, from.x(), from.y() + cells, from.z(), 1, cuts);
            cutsFor(ctx, x, from.y() + 1, z, cells, cuts);
            best = cheaper(best, new Option(from, cuts, new Pos(x, from.y() + 1, z),
                    cuts.size() + walk));
        }
        return best;
    }

    // ── rung 3: lower yourself ───────────────────────────────────────────────────────────────

    /**
     * Take the floor out and drop: for a body stranded on top of something, down is the way out, and
     * a fall too deep becomes takeable by removing what it stands on (four above a platform, break
     * below, now three).
     *
     * <p>The measure is the ground AROUND the cell, not the column under it — measuring the column
     * made an earlier cut apply on flat meadow, there being a landing under every floor everywhere.
     */
    private final class LowerYourself implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return scan(ctx)[LOWER] != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            Option option = scan(ctx)[LOWER];
            // Dearer than a cut of the same size: going down is a commitment a stair is not.
            return option == null ? Double.MAX_VALUE : option.cost() + 4.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Option option = scan(ctx)[LOWER];
            narrate(ctx, "escape", "digging down " + option.cuts().size() + " to get off ("
                    + option.from().x() + ", " + option.from().y() + ", " + option.from().z() + ")");
            return steps(ctx, option);
        }

        @Override
        public String describe() {
            return "lower yourself";
        }
    }

    /**
     * Every block to take out from under {@code from}, top down, to leave a drop this body can take
     * — or null when that is not what is wrong there.
     *
     * <p>The whole descent, not one block of it: one break per grant ended the task each time and
     * whatever ran next walked the body away, so a settler chewed a mound's rim without ever
     * completing a descent.
     */
    private static @Nullable Option lowerFrom(BrainContext ctx, Pos from, double walk) {
        MoveCapabilities body = MoveCapabilities.of(ctx.profile());
        int lowest = Integer.MAX_VALUE;
        for (int[] d : CARDINALS) {
            // Below, not the top of the column: a heightmap answers about the whole column, so a
            // canopy or a floor overhead would be reported as the ground beside us. See
            // landingBelow, and the test that pins it.
            int landing = landingBelow(ctx, from.x() + d[0], from.z() + d[1], from.y());
            if (landing == Integer.MIN_VALUE) {
                return null; // nothing we can see to aim at; do not commit to a hole
            }
            lowest = Math.min(lowest, landing);
        }
        int drop = from.y() - (lowest + 1);
        if (drop <= body.maxDrop()) {
            return null; // it can just step off; mining down would be the long way
        }
        List<Pos> cuts = new ArrayList<>();
        for (int i = 1; i <= drop - body.maxDrop(); i++) {
            int y = from.y() - i;
            if (!cuttable(ctx, from.x(), y, from.z())
                    || !solidFloor(ctx, from.x(), y - 1, from.z())) {
                break; // nothing to take out here, or nothing to land on once it is gone
            }
            cuts.add(new Pos(from.x(), y, from.z()));
        }
        return cuts.isEmpty() ? null : new Option(from, cuts, null, cuts.size() + walk);
    }

    // ── rung 4: say so ───────────────────────────────────────────────────────────────────────

    /**
     * Nothing else worked. Failing puts the drive on its cooldown instead of shouting every tick,
     * and tells the board (the arbiter's {@code driveFailed}) that a body cannot help itself.
     *
     * <p>The only rung a body without an arm has, so the ladder bids even when it cannot
     * dig: a settler sealed in by somebody else's work is shut in and claim-vetoed from cutting
     * out, and always ends here.
     */
    private final class SaySo implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return Double.MAX_VALUE / 2; // always last, and never compared away by an arithmetic tie
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Pos here = ctx.percepts().position();
            narrate(ctx, "shut in", "cannot get out of "
                    + ctx.percepts().confinement().cells() + " cells at ("
                    + here.x() + ", " + here.y() + ", " + here.z() + ")"
                    + (canDig(ctx) ? " and cannot cut a way out from where it stands"
                            : " and has nothing to dig with"));
            return List.of(new Stuck());
        }

        @Override
        public String describe() {
            return "call for help";
        }
    }

    /** The failing leaf of {@link SaySo} — see that method's doc for why this fails on purpose. */
    public static final class Stuck implements PrimitiveTask {
        @Override
        public TaskStatus tick(BrainContext ctx) {
            return TaskStatus.FAILED;
        }

        @Override
        public void cancel(BrainContext ctx) {
        }

        @Override
        public String describe() {
            return "stuck";
        }

        @Override
        public String failureDetail() {
            return "shut in with no way out";
        }
    }

    // ── shared ───────────────────────────────────────────────────────────────────────────────

    /**
     * How far down a look for a landing reaches, in cells. Bounded because these are block reads —
     * four columns' worth every time the rung is scored — and deep enough for anything a body could
     * be stranded on and still mine off; past it, "I cannot see where I would land" is the correct
     * answer. Shares its number with the pathfinder's plunge look.
     */
    private static final int LANDING_LOOK = 32;

    /**
     * The first thing in this column that would hold a body up, at or <em>below</em> {@code fromY}
     * — or {@link Integer#MIN_VALUE} when there is none within {@link #LANDING_LOOK}, or the column
     * cannot be read.
     *
     * <p>Starts AT the body's own level, so a wall beside it reads as a landing level with its feet
     * rather than being seen through; sideways confinement belongs to the rungs above.
     *
     * <p>Water is not a landing, keeping the motion-blocking heightmap's meaning; teaching this rung
     * otherwise would have to know whether the body can swim.
     */
    private static int landingBelow(BrainContext ctx, int x, int z, int fromY) {
        BlockProbe blocks = ctx.percepts().blocks();
        for (int y = fromY; y > fromY - LANDING_LOOK; y--) {
            BlockKind kind = blocks.at(x, y, z);
            if (kind == BlockKind.UNKNOWN) {
                return Integer.MIN_VALUE; // a column we cannot read is not one we can aim at
            }
            if (kind != BlockKind.AIR && kind != BlockKind.WATER) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Whether this cell would hold a body up — anything solid, water excluded. */
    private static boolean solidFloor(BrainContext ctx, int x, int y, int z) {
        BlockKind kind = ctx.percepts().blocks().at(x, y, z);
        return kind != BlockKind.AIR && kind != BlockKind.WATER && kind != BlockKind.UNKNOWN;
    }

    /** Whether any cell of a column is one we would regret opening. */
    private static boolean anyUnsafe(BrainContext ctx, int x, int y, int z, int cells) {
        for (int i = 0; i < cells; i++) {
            if (unsafe(ctx, x, y + i, z)) {
                return true;
            }
        }
        return false;
    }

}
