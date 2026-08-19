package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.craft.Workbench;
import dev.luizloyola.anima.core.inv.ItemSpec;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * BE at a workbench — the achieve-goal the 3×3 half of {@link CraftFor} runs through. Satisfied when
 * a known table stands within arm's reach, world-verified ({@link Workbench#standingAtOne}); two
 * ways otherwise, and their prices are the policy:
 *
 * <ul>
 *   <li><b>Walk to a known one</b>, priced at the distance — so a settlement converges on shared
 *       tables instead of one per person.</li>
 *   <li><b>Make one and put it down</b>, a flat {@link #PLACE_COST}: obtain the item (log → planks →
 *       table, or one already in the pack), place it beside, and claim it for the party at once —
 *       the next subtask needs it.</li>
 * </ul>
 *
 * <p>Tables sprout where the need arises: no workshop policy, no blueprint.
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

    private final List<Method> methods = List.of(new WalkToKnown(), new MakeAndPlace());

    public EnsureTable() {
        this(Set.of());
    }

    public EnsureTable(Set<String> pursued) {
        this.pursued = Set.copyOf(pursued);
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
    private static final class WalkToKnown implements Method {
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
         * A cell to stand in beside the table: an empty side neighbour, else the anchor's column —
         * the pathfinder then fails outright and the round places a fresh table.
         */
        private static Pos standableBeside(Pos anchor, BrainContext ctx) {
            BlockProbe probe = ctx.percepts().blocks();
            Pos best = null;
            double bestDistance = Double.MAX_VALUE;
            Pos here = ctx.percepts().position();
            for (int[] side : SIDES) {
                Pos cell = new Pos(anchor.x() + side[0], anchor.y(), anchor.z() + side[1]);
                if (probe.at(cell.x(), cell.y(), cell.z()) != BlockKind.AIR) {
                    continue;
                }
                double distance = Workbench.distance(cell, here);
                if (distance < bestDistance) {
                    best = cell;
                    bestDistance = distance;
                }
            }
            return best != null ? best : anchor;
        }
    }

    /** Obtain a table item (craft it from the pack if need be), place it, claim it for the party. */
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
            Pos spot = spotBeside(ctx);
            return List.of(
                    new ObtainItem(ItemSpec.anyOf(Set.of(Workbench.ITEM_ID)), 1, pursued),
                    new PlaceBlock(Workbench.ITEM_ID, spot.x(), spot.y(), spot.z()),
                    new FoundPlace(Workbench.POI, spot.x(), spot.y(), spot.z()));
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
                    if (probe.at(x, feet.y(), z) == BlockKind.AIR
                            && probe.at(x, feet.y() - 1, z) != BlockKind.AIR) {
                        return new Pos(x, feet.y(), z);
                    }
                }
            }
            return null;
        }
    }

    /** The eight horizontal neighbours, sides first — a table in a corner is awkward to reach. */
    private static final int[][] SIDES = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
}
