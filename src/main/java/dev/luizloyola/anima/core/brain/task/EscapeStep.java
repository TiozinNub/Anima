package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import java.util.ArrayList;
import java.util.List;

/**
 * One move toward getting out of somewhere this body cannot walk out of.
 *
 * <p><b>One step, then look again</b>, not a compiled plan like the chop's dance card: an escape
 * changes the world with every block it removes, and "am I still shut in" is re-answered for free
 * by the next route search. Every step strictly enlarges what the body can reach, which makes this
 * a monotone loop.
 *
 * <p><b>The way out is chosen by shape.</b> A body in a room is walled in sideways and wants a hole
 * in the wall; a body down a hole is walled in upward and wants a stair. What tells them apart is
 * looking for somewhere to come out: a sideways cut is only offered when there is already-open,
 * standable ground on the far side, within a few blocks. Without that check the sideways rung
 * applies nearly everywhere, and in a pit it is the cheapest rung and a useless one.
 *
 * <p>The rungs, preferred in this order when several apply:
 * <ol>
 *   <li><b>Step out</b> — cut through the wall beside us and walk out. Only where the way out is
 *       actually sideways.
 *   <li><b>Cut a stair</b> — open the two cells above the block next door and step up into them,
 *       opening our own ceiling first if there is one. The way out of anything with a lid.
 *   <li><b>Lower yourself</b> — break the floor and drop one, until what is left is a fall this
 *       body can take.
 *   <li><b>Say so</b> — no arm, nothing cuttable, or every rung refused. Last and
 *       a FAILURE, so the drive goes on its cooldown instead of shouting every tick
 *       and the board hears about a body that cannot help itself.
 * </ol>
 *
 * <p>The first three need an arm ({@link ProfileAspect#BODY_CAN_DIG}); a body without one still
 * notices it is shut in and says so, so the last rung is not optional.
 */
public final class EscapeStep implements CompoundTask {

    private final List<Method> methods =
            List.of(new StepOut(), new CutAStair(), new LowerYourself(), new SaySo());

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

    // ── rung 1: step out ─────────────────────────────────────────────────────────────────────

    /**
     * Cut through the wall next to us and walk out of it — the cheapest way out of a room, and the
     * wrong way out of a pit. That is what the far-side probe in {@code pick} is for.
     */
    private final class StepOut implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return canDig(ctx) && pick(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            int[] choice = pick(ctx);
            return choice == null ? Double.MAX_VALUE : choice[2];
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            int[] choice = pick(ctx);
            Pos here = ctx.percepts().position();
            int cells = height(ctx);
            int thickness = thicknessOf(ctx, choice[0], choice[1]);
            List<Pos> cuts = new ArrayList<>();
            for (int step = 1; step < thickness; step++) {
                cutsFor(ctx, here.x() + choice[0] * step, here.y(), here.z() + choice[1] * step,
                        cells, cuts);
            }
            Pos out = new Pos(here.x() + choice[0] * thickness, here.y(),
                    here.z() + choice[1] * thickness);
            narrate(ctx, "escape", "cutting out through the wall to ("
                    + out.x() + ", " + out.y() + ", " + out.z() + ")");
            return steps(cuts, out);
        }

        @Override
        public String describe() {
            return "cut through the wall";
        }

        /**
         * The cheapest cardinal with somewhere to come out: {@code {dx, dz, thickness}}, or null.
         *
         * <p>The probe is what makes this rung mean anything: cutting sideways applies almost
         * everywhere, and in a pit it is cheap and useless. So the wall is only worth punching when
         * a standable, already-open cell lies on the OTHER side within {@link #WALL_LIMIT}.
         */
        private int[] pick(BrainContext ctx) {
            Pos here = ctx.percepts().position();
            int cells = height(ctx);
            int[] best = null;
            for (int[] d : CARDINALS) {
                for (int thickness = 1; thickness <= WALL_LIMIT; thickness++) {
                    int x = here.x() + d[0] * thickness;
                    int z = here.z() + d[1] * thickness;
                    if (anyUnsafe(ctx, x, here.y(), z, cells)
                            || !solidFloor(ctx, x, here.y() - 1, z)) {
                        break; // no floor to walk out onto, or something we will not open
                    }
                    if (cutsToClear(ctx, x, here.y(), z, cells) > 0) {
                        continue; // still inside the wall — keep looking for its far side
                    }
                    if (thickness == 1) {
                        break; // open and adjacent: this is not the wall that has us
                    }
                    int cuts = 0;
                    for (int step = 1; step < thickness; step++) {
                        cuts += cutsToClear(ctx, here.x() + d[0] * step, here.y(),
                                here.z() + d[1] * step, cells);
                    }
                    if (best == null || cuts < best[2]) {
                        best = new int[] {d[0], d[1], cuts};
                    }
                    break;
                }
            }
            return best;
        }

        /** How far through the wall the open cell we found lies. */
        private int thicknessOf(BrainContext ctx, int dx, int dz) {
            Pos here = ctx.percepts().position();
            int cells = height(ctx);
            for (int thickness = 1; thickness <= WALL_LIMIT; thickness++) {
                if (cutsToClear(ctx, here.x() + dx * thickness, here.y(),
                        here.z() + dz * thickness, cells) == 0) {
                    return thickness;
                }
            }
            return WALL_LIMIT;
        }
    }

    // ── rung 2: cut a stair ──────────────────────────────────────────────────────────────────

    /**
     * Open the cells above the block beside us and step up into them — one tread of a staircase
     * cut into whatever is holding us down. Our own ceiling goes first when there is one, because
     * a body with a lid on cannot jump.
     */
    private final class CutAStair implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return canDig(ctx) && ctx.profile().i(ProfileAspect.BODY_JUMP_HEIGHT) > 0
                    && pick(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            int[] choice = pick(ctx);
            return choice == null ? Double.MAX_VALUE : choice[2];
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            int[] choice = pick(ctx);
            Pos here = ctx.percepts().position();
            int cells = height(ctx);
            int x = here.x() + choice[0];
            int z = here.z() + choice[1];
            List<Pos> cuts = new ArrayList<>();
            // Our own lid first: without headroom over our feet-cell there is no jump to make.
            cutsFor(ctx, here.x(), here.y() + cells, here.z(), 1, cuts);
            cutsFor(ctx, x, here.y() + 1, z, cells, cuts);
            narrate(ctx, "escape", "cutting a stair up to " + x + ", " + (here.y() + 1) + ", " + z);
            return steps(cuts, new Pos(x, here.y() + 1, z));
        }

        @Override
        public String describe() {
            return "cut a stair upward";
        }

        /** The cheapest cardinal to cut a tread into: {@code {dx, dz, cuts}}, or null. */
        private int[] pick(BrainContext ctx) {
            Pos here = ctx.percepts().position();
            int cells = height(ctx);
            int[] best = null;
            for (int[] d : CARDINALS) {
                int x = here.x() + d[0];
                int z = here.z() + d[1];
                // The tread itself has to be something to stand ON, so it must not be cut.
                if (!solidFloor(ctx, x, here.y(), z)) {
                    continue;
                }
                if (anyUnsafe(ctx, x, here.y() + 1, z, cells)
                        || unsafe(ctx, here.x(), here.y() + cells, here.z())) {
                    continue;
                }
                int cuts = cutsToClear(ctx, x, here.y() + 1, z, cells)
                        + cutsToClear(ctx, here.x(), here.y() + cells, here.z(), 1);
                if (cuts == 0) {
                    continue; // the step up is already clear; something else is what stops us
                }
                if (best == null || cuts < best[2]) {
                    best = new int[] {d[0], d[1], cuts};
                }
            }
            return best;
        }
    }

    // ── rung 3: lower yourself ───────────────────────────────────────────────────────────────

    /**
     * Take the floor out and drop one. For a body stranded on top of something, down is the way out
     * — a fall one too deep to survive becomes one it can take by removing the block under its feet
     * (four above a good platform, break below, and now it is three). Iterative: each step lowers
     * the body by one and the next look re-surveys from where it now stands.
     *
     * <p><b>Know where you land</b> is the whole of the safety. The column below is walked to the
     * first thing that would stop a body; there has to BE one, within reach of this look, nothing
     * on the way down may be water this body cannot swim or a cell we could not read, and the
     * remaining fall has to be survivable. "Never break the floor" is the wrong rule — it forbids
     * climbing down your own mast (see {@code Riser}: un-building is mining).
     */
    private final class LowerYourself implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return canDig(ctx) && !descent(ctx).isEmpty();
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            // Dearer than a cut of the same size: going down is a commitment a stair is not.
            return 4.0 + descent(ctx).size();
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            List<Pos> cuts = descent(ctx);
            Pos here = ctx.percepts().position();
            narrate(ctx, "escape", "digging down " + cuts.size() + " to get off "
                    + here.x() + ", " + here.y() + ", " + here.z());
            List<Task> tasks = new ArrayList<>(cuts.size());
            for (Pos cut : cuts) {
                tasks.add(new BreakBlock(cut.x(), cut.y(), cut.z()));
            }
            return tasks;
        }

        @Override
        public String describe() {
            return "lower yourself";
        }

        /**
         * Every block to take out from under us, top down, to leave a drop this body can simply
         * take — or empty when that is not what is wrong here.
         *
         * <p><b>The whole descent, not one block of it.</b> One break per grant ends the task and
         * hands the wheel back, and whatever runs next walks the body away: watched live
         * (2026-08-12) chewing an entire mound perimeter one rim block at a time without ever
         * completing a descent.
         *
         * <p>The measure is the ground AROUND the body, not the column under it: there is a landing
         * under every floor everywhere, so an earlier cut that asked "is there something to land
         * on" applied on perfectly flat meadow. What matters is how far this body is above the
         * ground it is trying to reach; a fall it could take should be taken.
         */
        private List<Pos> descent(BrainContext ctx) {
            Pos here = ctx.percepts().position();
            MoveCapabilities body = MoveCapabilities.of(ctx.profile());
            BlockProbe blocks = ctx.percepts().blocks();
            int lowest = Integer.MAX_VALUE;
            for (int[] d : CARDINALS) {
                int surface = blocks.surfaceY(here.x() + d[0], here.z() + d[1]);
                if (surface == Integer.MIN_VALUE) {
                    return List.of(); // a column we cannot read is not a landing we can aim at
                }
                lowest = Math.min(lowest, surface);
            }
            int drop = here.y() - (lowest + 1);
            if (drop <= body.maxDrop()) {
                return List.of(); // it can just step off; mining down would be the long way
            }
            List<Pos> cuts = new ArrayList<>();
            for (int i = 1; i <= drop - body.maxDrop(); i++) {
                int y = here.y() - i;
                if (!cuttable(ctx, here.x(), y, here.z())
                        || !solidFloor(ctx, here.x(), y - 1, here.z())) {
                    break; // nothing to take out here, or nothing to land on once it is gone
                }
                cuts.add(new Pos(here.x(), y, here.z()));
            }
            return cuts;
        }
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

    /**
     * The cuts, then the move — the shape every digging rung shares.
     *
     * <p>Cut from the bottom up. Either order leaves a gravel ceiling able to fall into the hole
     * just made, so neither is safe; bottom-up at least means the body is looking at the cell it is
     * about to stand in when that happens, and a fall that spoils the step fails the move rather
     * than the swing — which records a setback and sends the next look somewhere else.
     */
    private static List<Task> steps(List<Pos> cuts, Pos into) {
        List<Task> tasks = new ArrayList<>(cuts.size() + 1);
        for (Pos cut : cuts) {
            tasks.add(new BreakBlock(cut.x(), cut.y(), cut.z()));
        }
        tasks.add(new GoTo(into.x(), into.y(), into.z()));
        return tasks;
    }
}
