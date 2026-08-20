package dev.luizloyola.anima.core.store;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRule;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A store as Anima's own vocabulary — a place the same shape as
 * {@link dev.luizloyola.anima.core.craft.Workbench}: a {@link BlockKind} the compat classifier
 * recognises by capability (anything carrying an inventory) rather than by a list of block names,
 * and a {@link GrowthRule} remembering each store as its own place — merge radius 0, so a row of
 * chests stays a row of stores and never becomes one warehouse. Perception picks up every store,
 * including one another settler placed. Nothing here takes an item in or out; a store is
 * somewhere to stand.
 */
public final class Store {

    /** The block, as perception's vocabulary. Recognised by the compat classifier. */
    public static final BlockKind BLOCK = BlockKind.register("store");

    /** The remembered place. Merge radius 0: two adjacent chests are still two stores. */
    public static final PoiKind POI = PoiKind.register("store", 0, "");

    /**
     * How close is "at the store" — the survival player's own interaction range, the same number
     * the breaker's arm uses.
     */
    public static final double REACH = 4.0;

    /** The one item that places into a {@link #BLOCK}. String-level vanilla knowledge. */
    public static final String ITEM_ID = "minecraft:chest";

    /** Each store cell is its own thing: a row of chests is a row of memories, not a warehouse. */
    public static final GrowthRule RULE = new Rule();

    private Store() {
    }

    /**
     * Whether a store this body KNOWS about stands within reach right now. The remembered anchor
     * is re-read through the probe, and a claim the world no longer backs (broken, burned) is
     * disproven on the spot — dropped from the party's claims, not just this body's sighting,
     * since a placed chest belongs to the party. One probe read on the happy path; no memory, no
     * reads.
     */
    public static boolean standingAtOne(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        Optional<PoiMemory> known = ctx.knowledge().nearest(POI, here);
        if (known.isEmpty() || distance(known.get().anchor(), here) > REACH) {
            return false;
        }
        Pos anchor = known.get().anchor();
        if (ctx.percepts().blocks().at(anchor.x(), anchor.y(), anchor.z()) != BLOCK) {
            ctx.knowledge().disprove(POI, anchor);
            return false;
        }
        return true;
    }

    /** The nearest remembered store, wherever it stands. */
    public static Optional<PoiMemory> nearestKnown(BrainContext ctx) {
        // Avoided stores are skipped, which is what makes a full chest self-correcting: the
        // deposit marks it, the next round of the achieve-goal cannot see it, and the body either
        // walks to another or builds one. Found in-world on 2026-08-20 — without this a settler
        // stood over a chest somebody had filled with 10,000 grass blocks and re-opened it every
        // round until the round cap, then failed the errand and wandered off.
        //
        // Deliberately NOT applied to standingAtOne: where a body IS standing is a fact, and an
        // avoid-mark is a preference about where to go next.
        long now = ctx.percepts().time();
        Pos here = ctx.percepts().position();
        return ctx.knowledge().all(POI).stream()
                .filter(memory -> !ctx.knowledge().isAvoided(POI, memory.anchor(), now))
                .min(java.util.Comparator.comparingDouble(
                        memory -> distance(memory.anchor(), here)));
    }

    public static double distance(Pos a, Pos b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** One evaluation per cell — a store is a thing, never a mass. */
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
