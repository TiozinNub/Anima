package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * BE at a workbench — the achieve-goal the 3×3 half of {@link CraftFor} runs through. Satisfied when
 * a known table stands within arm's reach, world-verified ({@link Workbench#standingAtOne}); two
 * ways otherwise, and their prices are the policy:
 *
 * <ul>
 *   <li><b>Walk to a known one</b>, priced at the distance — so a settlement converges on shared
 *       tables instead of one per person.</li>
 *   <li><b>Make one and put it down</b>, a flat {@link #PLACE_COST}: obtain the item (log → planks →
 *       table, or one already in the pack) and place it beside. It is this body's own, not the
 *       party's: {@link PackUpTable} picks it back up once the craft is made.</li>
 * </ul>
 *
 * <p>A table carried but refused where it was to go says why in the journal, once per refusal.
 */
public final class EnsureTable implements AchieveTask {

    /**
     * The walk-vs-place breakeven, in the same blocks-flavoured cost every method prices in: a known
     * table nearer than this wins.
     */
    public static final double PLACE_COST = 32.0;

    /**
     * The occurs-check, threaded through from the {@link CraftFor} that needed the table — so a
     * (modded) table recipe that itself wants a table terminates instead of ensuring forever.
     * Vanilla never trips this; the guard exists because the recipe book is a datapack.
     */
    private final Set<String> pursued;

    private final List<Method> methods = List.of(new WalkToKnown(), new MakeAndPlace(), new SayWhyNot());

    /** Where the last make-and-place put the table, and where the body stood; null once said. */
    private @Nullable Pos spot;
    private @Nullable Pos stood;

    public EnsureTable() {
        this(Set.of());
    }

    public EnsureTable(Set<String> pursued) {
        this(pursued, null, null);
    }

    /** One restored mid-way: the spot a table was last put down at, and where the body stood. */
    public EnsureTable(Set<String> pursued, @Nullable Pos spot, @Nullable Pos stood) {
        this.pursued = Set.copyOf(pursued);
        this.spot = spot;
        this.stood = stood;
    }

    public Optional<Pos> spot() {
        return Optional.ofNullable(spot);
    }

    public Optional<Pos> stood() {
        return Optional.ofNullable(stood);
    }

    /** What the codec writes so a reload keeps refusing the same cycles. */
    public Set<String> pursued() {
        return pursued;
    }

    @Override
    public boolean satisfied(BrainContext ctx) {
        return Workbench.standingAtOne(ctx);
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "be at a workbench";
    }

    /** Walk into reach of the nearest remembered table. */
    public static final class WalkToKnown implements Method {
        /** How far under the anchor's level a side may be stood on: the arm still reaches it. */
        static final int STAND_DROP = 2;

        @Override
        public boolean applicable(BrainContext ctx) {
            return Workbench.nearestKnown(ctx).isPresent();
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return Workbench.nearestKnown(ctx)
                    .map(known -> Workbench.distance(known.anchor(), ctx.percepts().position()))
                    .orElse(Double.MAX_VALUE);
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            PoiMemory known = Workbench.nearestKnown(ctx).orElseThrow();
            Pos beside = standableBeside(known.anchor(), ctx);
            return List.of(new GoTo(beside.x(), beside.y(), beside.z()));
        }

        @Override
        public String describe() {
            return "walk to a known workbench";
        }

        /**
         * A cell to stand in beside the anchor: an empty side neighbour, else the anchor's column —
         * the pathfinder then fails outright and the caller reacts (a fresh table gets placed
         * here). Public so every walk to a thing — a store, a consumer's plant — shares this rather
         * than keeping its own copy of the shape; a caller that would rather have no way at all
         * asks {@link #standBeside}.
         */
        public static Pos standableBeside(Pos anchor, BrainContext ctx) {
            return standBeside(anchor, ctx).orElse(anchor);
        }

        /**
         * The side cell {@link #standableBeside} would pick, empty when there is none: no open side,
         * or every one a walk lately found no way to ({@link BrainContext#unreached}). A corner
         * counts only past an open side, for the arm does not reach a chest through the edge of a
         * wall. The stand has a floor: the open side itself, or the first floor under it within
         * {@link #STAND_DROP}.
         */
        public static Optional<Pos> standBeside(Pos anchor, BrainContext ctx) {
            long now = ctx.percepts().time();
            return standBeside(anchor, ctx.percepts().blocks(), ctx.percepts().position(),
                    cell -> ctx.unreached().struck(cell, now));
        }

        /**
         * Whether the anchor has a side to stand at by its blocks alone — the shape
         * {@link #standBeside} keeps, for a caller with no body whose walks it could ask about: a
         * party counting what its stores hold that anybody could take out.
         */
        public static boolean hasOpenSide(Pos anchor, BlockProbe probe) {
            return standBeside(anchor, probe, anchor, cell -> false).isPresent();
        }

        private static Optional<Pos> standBeside(Pos anchor, BlockProbe probe, Pos here,
                                                 Predicate<Pos> struck) {
            Pos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int[] side : SIDES) {
                Pos cell = footed(probe, anchor.x() + side[0], anchor.y(), anchor.z() + side[1]);
                if (cell == null || struck.test(cell)) {
                    continue;
                }
                if (side[0] != 0 && side[1] != 0
                        && probe.at(anchor.x() + side[0], anchor.y(), anchor.z()) != BlockKind.AIR
                        && probe.at(anchor.x(), anchor.y(), anchor.z() + side[1]) != BlockKind.AIR) {
                    continue;
                }
                double distance = Workbench.distance(cell, here);
                if (distance < bestDistance) {
                    best = cell;
                    bestDistance = distance;
                }
            }
            return Optional.ofNullable(best);
        }

        /**
         * The first cell of column {@code (x, z)} from {@code y} down that is open with a floor under
         * it. A side open over a pit is no stand: one eight above the pit floor was walked to the
         * floor, out of reach, for good (run/normal, 2026-10-02).
         */
        private static @Nullable Pos footed(BlockProbe probe, int x, int y, int z) {
            for (int at = y; at >= y - STAND_DROP; at--) {
                if (probe.at(x, at, z) != BlockKind.AIR) {
                    return null;
                }
                if (probe.at(x, at - 1, z) != BlockKind.AIR) {
                    return new Pos(x, at, z);
                }
            }
            return null;
        }
    }

    /** Obtain a table item (craft it from the pack if need be), place it, note it as this body's. */
    private final class MakeAndPlace implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return spotBeside(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return PLACE_COST;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            spot = spotBeside(ctx);
            stood = ctx.percepts().position();
            List<Task> steps = new ArrayList<>(4);
            steps.add(new ObtainItem(ItemSpec.anyOf(Set.of(Workbench.ITEM_ID)), 1, pursued));
            steps.addAll(Ground.clearAndPlace(ctx, Workbench.ITEM_ID, spot));
            steps.add(new NoteFieldTable(spot.x(), spot.y(), spot.z()));
            return steps;
        }

        @Override
        public String describe() {
            return "make a workbench and put it down";
        }

        /**
         * An empty cell on solid ground beside the body — where the table goes. Two rings of
         * neighbours, nearest first; {@code null} when the body is somehow bricked in, which
         * makes the method inapplicable rather than a doomed decomposition.
         */
        private static Pos spotBeside(BrainContext ctx) {
            BlockProbe probe = ctx.percepts().blocks();
            Pos feet = ctx.percepts().position();
            for (int ring = 1; ring <= 2; ring++) {
                for (int[] side : SIDES) {
                    int x = feet.x() + side[0] * ring;
                    int z = feet.z() + side[1] * ring;
                    Pos cell = new Pos(x, feet.y(), z);
                    if (probe.at(x, feet.y(), z) == BlockKind.AIR
                            && probe.at(x, feet.y() - 1, z) != BlockKind.AIR
                            && !PlaceBlock.occupied(ctx, cell)) {
                        return cell;
                    }
                }
            }
            return null;
        }
    }

    /**
     * After a make-and-place that got the table but did not put it down: the refusal, in the
     * journal ({@link PlaceFrom.WhyNot}), and a failure.
     */
    private final class SayWhyNot implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return spot != null && ctx.percepts().inventory().count(Workbench.ITEM_ID::equals) > 0
                    && ctx.percepts().blocks().at(spot.x(), spot.y(), spot.z()) != Workbench.BLOCK;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 1.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Pos at = spot;
            spot = null; // said once: the next round tries again before it says anything
            return List.of(new PlaceFrom.WhyNot(Placing.of(Workbench.ITEM_ID, at), null, stood));
        }

        @Override
        public String describe() {
            return "say why the workbench would not go down";
        }
    }

    /** The eight horizontal neighbours, sides first — a table in a corner is awkward to reach. */
    private static final int[][] SIDES = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
}
