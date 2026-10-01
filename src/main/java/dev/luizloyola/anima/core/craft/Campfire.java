package dev.luizloyola.anima.core.craft;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRule;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The campfire, beside {@link Furnace}: a block perception recognises and a place a party keeps.
 * Four slots that cook at once with no fuel and drop what they make beside it, so it is never a
 * store and nothing is ever taken out of it — reaching into it is {@code CampfireAccess}'s.
 */
public final class Campfire {

    public static final BlockKind BLOCK = BlockKind.register("campfire");

    /** Merge radius 1, as the furnace's: campfires side by side are worked one by one. */
    public static final PoiKind POI = PoiKind.register("campfire", 1, "");

    public static final double REACH = Workbench.REACH;

    public static final String ITEM_ID = "minecraft:campfire";

    /** How many campfires one cook works at once (directions spec, decision 27). */
    public static final int WORKED = 4;

    /** How far from the first campfire another stands to be worked from the same spot. */
    public static final int HEARTH = 3;

    public static final GrowthRule RULE = new Rule();

    private Campfire() {
    }

    /**
     * The campfires a cook could work from beside {@code at}: {@code at} itself, then the rest
     * within {@link #HEARTH}, nearest first — those the body knows of, and those it sees from
     * there. Seen as well as known: a body standing at a fire has not yet walked the columns that
     * would teach it the one beside it. Which {@link #WORKED} of them burn is the cook's to read.
     */
    public static List<Pos> hearth(BrainContext ctx, Pos at) {
        Set<Pos> near = new HashSet<>();
        for (PoiMemory fire : ctx.knowledge().all(POI)) {
            if (Workbench.distance(fire.anchor(), at) <= HEARTH) {
                near.add(fire.anchor());
            }
        }
        BlockProbe sight = ctx.percepts().blocks();
        for (int dx = -HEARTH; dx <= HEARTH; dx++) {
            for (int dz = -HEARTH; dz <= HEARTH; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Pos p = new Pos(at.x() + dx, at.y() + dy, at.z() + dz);
                    if (Workbench.distance(p, at) <= HEARTH && sight.at(p.x(), p.y(), p.z()) == BLOCK) {
                        near.add(p);
                    }
                }
            }
        }
        near.remove(at);
        List<Pos> sorted = new ArrayList<>(near);
        sorted.sort(Comparator.comparingDouble((Pos p) -> Workbench.distance(p, at))
                .thenComparingInt(Pos::x).thenComparingInt(Pos::z).thenComparingInt(Pos::y));
        sorted.add(0, at);
        return sorted;
    }

    private static final class Rule implements GrowthRule {
        @Override
        public PoiKind kind() {
            return POI;
        }

        @Override
        public boolean joins(Pos p, BlockKind kind, BlockProbe probe) {
            return kind == BLOCK;
        }

        @Override
        public List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, BlockProbe probe) {
            List<Evaluation> each = new ArrayList<>(blocks.size());
            for (Pos cell : blocks.keySet()) {
                each.add(new Evaluation(cell, 1, Map.of(cell, BLOCK)));
            }
            return each;
        }
    }
}
