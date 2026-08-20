package dev.luizloyola.anima.core.store;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.GrowthRule;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A store as Anima's own vocabulary — a place the same shape as
 * {@link dev.luizloyola.anima.core.craft.Workbench}: a {@link BlockKind} the compat classifier
 * recognises by capability (anything carrying an inventory) rather than by a list of block names,
 * and a {@link GrowthRule} remembering each store as its own place — merge radius 0, so a row of
 * chests stays a row of stores and never becomes one warehouse. Perception picks up every store,
 * including one another settler placed. Nothing here takes an item in or out; a store is
 * somewhere to stand.
 *
 * <p><b>One chest, not one cell.</b> A double chest is two block entities and one inventory, so it
 * is one place spanning two cells, anchored at {@link #kindFor the lower half} — the same pick
 * {@code Lids} counts their shared lid under and {@code WorldContainers} resolves their shared
 * inventory from.
 */
public final class Store {

    /** The block, as perception's vocabulary. Recognised by the compat classifier. */
    public static final BlockKind BLOCK = BlockKind.register("store");

    /**
     * The far half of a double store, whose anchor is one cell back along X.
     *
     * <p>Perception needs the halves told apart because <b>the world cannot tell it</b>: two single
     * chests side by side and one double chest are the same two cells to a {@link BlockProbe}, and
     * only the compat classifier can see the joint. Naming the axis rather than hunting for a
     * neighbouring anchor is what keeps a wall of double chests pairing the way it was built — see
     * {@link Rule#evaluate}.
     */
    public static final BlockKind FAR_X = BlockKind.register("store_far_x");

    /** The far half of a double store joined along Z — {@link #FAR_X}'s twin. */
    public static final BlockKind FAR_Z = BlockKind.register("store_far_z");

    /** Every kind of store cell, and so every seed the classifier has to register. */
    public static final List<BlockKind> SEEDS = List.of(BLOCK, FAR_X, FAR_Z);

    /** Whether this is somewhere to put things. Either half of a double store counts. */
    public static boolean isStore(BlockKind kind) {
        return kind == BLOCK || kind == FAR_X || kind == FAR_Z;
    }

    /**
     * Which kind a container cell is, given the offset to the other half of its pair — {@code (0,
     * 0)} for anything joined to nothing.
     *
     * <p><b>The anchor is the half with the lower coordinate</b>, the same pick {@code Lids} counts
     * a shared lid under. So the other half always lies at +X or +Z, and a far half has only its
     * own axis left to name.
     */
    public static BlockKind kindFor(int dx, int dz) {
        if (dx < 0) {
            return FAR_X;
        }
        if (dz < 0) {
            return FAR_Z;
        }
        return BLOCK;
    }

    /** The remembered place. Merge radius 0: two adjacent chests are still two stores. */
    public static final PoiKind POI = PoiKind.register("store", 0, "");

    /**
     * How close is "at the store" — the survival player's own interaction range, the same number
     * the breaker's arm uses.
     */
    public static final double REACH = 4.0;

    /** The one item that places into a {@link #BLOCK}. String-level vanilla knowledge. */
    public static final String ITEM_ID = "minecraft:chest";

    /** Each chest is its own thing: a row of chests is a row of memories, not a warehouse. */
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
        if (!isStore(ctx.percepts().blocks().at(anchor.x(), anchor.y(), anchor.z()))) {
            ctx.knowledge().disprove(POI, anchor);
            return false;
        }
        return true;
    }

    /**
     * A store beside the body that would not open, and the one correction the world supports.
     *
     * <p><b>Gone</b> — mined, burned — is a claim the whole party must stop planning against, so it
     * is disproven. <b>Still standing</b> is a chest shut to us: a solid block on the lid, or a cat
     * sitting on it. That belief is RIGHT, so only a timer un-blinds it — unmarked, the chest is
     * the cheapest method again the very next round and the body walks back to it until the round
     * cap. Same shape as a chest found full, and the same knob, rather than a second number for
     * the same idea.
     *
     * <p>Out of reach is neither: no evidence at all, and the memory survives the walk away.
     */
    public static void wouldNotOpen(BrainContext ctx, Pos at) {
        if (distance(at, ctx.percepts().position()) > REACH) {
            return;
        }
        if (isStore(ctx.percepts().blocks().at(at.x(), at.y(), at.z()))) {
            ctx.knowledge().avoid(POI, at, ctx.percepts().time()
                    + ctx.profile().i(ProfileAspect.STORES_FULL_AVOID_TICKS));
        } else {
            ctx.knowledge().disprove(POI, at);
        }
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

    /** One evaluation per CHEST — a store is a thing, never a mass, and a double chest is one. */
    private static final class Rule implements GrowthRule {
        @Override
        public PoiKind kind() {
            return POI;
        }

        @Override
        public boolean joins(Pos p, BlockKind kind, BlockProbe probe) {
            return isStore(kind);
        }

        /**
         * <b>Anchors are paired first, and only with the half that names them.</b> A far half can
         * touch a neighbouring chest's anchor, and that neighbour can sort below its own — so
         * pairing by nearest or lowest adjacent anchor mispairs a wall of double chests, while the
         * axis in the kind cannot be misread. What no anchor claimed then stands alone: a single
         * chest, or a far half whose anchor fell outside a scan cut short, which is half-remembered
         * rather than not remembered at all.
         */
        @Override
        public List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, BlockProbe probe) {
            List<Evaluation> each = new ArrayList<>(blocks.size());
            Set<Pos> paired = new HashSet<>();
            for (Map.Entry<Pos, BlockKind> cell : blocks.entrySet()) {
                Pos far = cell.getValue() == BLOCK ? farHalfOf(cell.getKey(), blocks) : null;
                if (far != null) {
                    paired.add(cell.getKey());
                    paired.add(far);
                    each.add(new Evaluation(cell.getKey(), 2,
                            Map.of(cell.getKey(), BLOCK, far, blocks.get(far))));
                }
            }
            for (Map.Entry<Pos, BlockKind> cell : blocks.entrySet()) {
                if (!paired.contains(cell.getKey())) {
                    each.add(new Evaluation(cell.getKey(), 1,
                            Map.of(cell.getKey(), cell.getValue())));
                }
            }
            return each;
        }

        /** The far half naming {@code anchor}, or null when it stands alone. */
        private static Pos farHalfOf(Pos anchor, Map<Pos, BlockKind> blocks) {
            Pos alongX = new Pos(anchor.x() + 1, anchor.y(), anchor.z());
            if (blocks.get(alongX) == FAR_X) {
                return alongX;
            }
            Pos alongZ = new Pos(anchor.x(), anchor.y(), anchor.z() + 1);
            return blocks.get(alongZ) == FAR_Z ? alongZ : null;
        }
    }
}
