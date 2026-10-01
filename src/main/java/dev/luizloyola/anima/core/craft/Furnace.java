package dev.luizloyola.anima.core.craft;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRule;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.social.PlaceRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The furnace, beside {@link Workbench}: a block perception recognises and a place a party keeps.
 * Never a store, though it has an inventory: what is in it is on its way to becoming something
 * else, and reaching into it is {@code FurnaceAccess}'s, slot by slot.
 */
public final class Furnace {

    public static final BlockKind BLOCK = BlockKind.register("furnace");

    /** Merge radius 1, as the workbench's: two furnaces side by side are two furnaces. */
    public static final PoiKind POI = PoiKind.register("furnace", 1, "");

    public static final double REACH = Workbench.REACH;

    public static final String ITEM_ID = "minecraft:furnace";

    public static final GrowthRule RULE = new Rule();

    private Furnace() {
    }

    /** The party's furnaces, as memories — a furnace somebody else built is not ours to load. */
    public static List<PoiMemory> ours(BrainContext ctx) {
        long now = ctx.percepts().time();
        List<PoiMemory> out = new ArrayList<>();
        for (PlaceRow row : ctx.knowledge().places().all(POI)) {
            out.add(row.toMemory(now));
        }
        return out;
    }

    /** The party's furnace nearest to this body. */
    public static Optional<PoiMemory> nearestOurs(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        PoiMemory best = null;
        double bestDistance = Double.MAX_VALUE;
        for (PoiMemory furnace : ours(ctx)) {
            double distance = Workbench.distance(furnace.anchor(), here);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = furnace;
            }
        }
        return Optional.ofNullable(best);
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
