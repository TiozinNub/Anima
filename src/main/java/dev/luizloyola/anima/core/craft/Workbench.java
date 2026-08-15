package dev.luizloyola.anima.core.craft;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRule;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The crafting table as Anima's own vocabulary — the one place-kind the library declares beside
 * {@link PoiKind#HERD}, because the machinery that finds one ({@code CraftFor}, via
 * {@code EnsureTable}) is the library's. It rides the ordinary extension points: a
 * {@link BlockKind} the compat classifier recognises, a {@link GrowthRule} remembering each table
 * as its own place. Perception picks up every table, including one another settler placed.
 */
public final class Workbench {

    /** The block, as perception's vocabulary. Recognised by the compat classifier. */
    public static final BlockKind BLOCK = BlockKind.register("workbench");

    /** The remembered place. Merge radius 1: two adjacent tables are still two tables. */
    public static final PoiKind POI = PoiKind.register("workbench", 1, "");

    /**
     * How close is "at the table" — the survival player's own interaction range, the same number
     * the breaker's arm uses.
     */
    public static final double REACH = 4.0;

    /** The one item that places into a {@link #BLOCK}. String-level vanilla knowledge. */
    public static final String ITEM_ID = "minecraft:crafting_table";

    /** Each table cell is its own thing: a row of three tables is three memories, not a shop. */
    public static final GrowthRule RULE = new Rule();

    private Workbench() {
    }

    /**
     * Whether a table this body KNOWS about stands within reach right now. The remembered anchor
     * is re-read through the probe, and a memory the world no longer backs (griefed, burned) is
     * forgotten on the spot. One probe read on the happy path; no memory, no reads.
     */
    public static boolean standingAtOne(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        Optional<PoiMemory> known = ctx.knowledge().nearest(POI, here);
        if (known.isEmpty() || distance(known.get().anchor(), here) > REACH) {
            return false;
        }
        Pos anchor = known.get().anchor();
        if (ctx.percepts().blocks().at(anchor.x(), anchor.y(), anchor.z()) != BLOCK) {
            ctx.knowledge().forget(POI, anchor);
            return false;
        }
        return true;
    }

    /** The nearest remembered table, wherever it stands. */
    public static Optional<PoiMemory> nearestKnown(BrainContext ctx) {
        return ctx.knowledge().nearest(POI, ctx.percepts().position());
    }

    public static double distance(Pos a, Pos b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** One evaluation per cell — a table is a thing, never a mass. */
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

    /** The memory a body writes for a table it just placed itself — see {@code NotePlace}. */
    public static PoiMemory memoryOf(Pos anchor, long now) {
        return new PoiMemory(POI, anchor, Region.of(anchor), 1, false, now);
    }
}
