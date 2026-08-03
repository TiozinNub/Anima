package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Map;

/**
 * Something for Anima's own suites to recognise: the library ships no botany, so a core
 * test supplies the vocabulary itself (Autarkia's suites use the real {@code TreeRule}).
 *
 * <p>Credulous: every leaf or log joins and the whole mass is one thing. What makes a
 * good tree belongs to the rule that has an opinion about it.
 */
public final class FakeGrowthRule implements GrowthRule {

    /** Merge radius 4 — coarse enough that a stand of trunks reads as one thicket. */
    public static final PoiKind THICKET = PoiKind.register("test_thicket", 4, " trees");

    public static final FakeGrowthRule INSTANCE = new FakeGrowthRule();

    /** Registers this rule for leaves and logs. Pair with {@link GrowthRules#reset()}. */
    public static void register() {
        GrowthRules.register(BlockKind.LEAVES, INSTANCE);
        GrowthRules.register(BlockKind.LOG, INSTANCE);
    }

    private FakeGrowthRule() {
    }

    @Override
    public PoiKind kind() {
        return THICKET;
    }

    @Override
    public boolean joins(Pos p, BlockKind kind, BlockProbe probe) {
        return kind == BlockKind.LEAVES || kind == BlockKind.LOG;
    }

    @Override
    public List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, Pos seed, BlockProbe probe) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        Pos anchor = null;
        for (Pos p : blocks.keySet()) {
            // Lowest cell, nearest the seed on a tie — the same shape a real rule's anchor takes.
            if (anchor == null || p.y() < anchor.y()
                    || (p.y() == anchor.y() && distSq(p, seed) < distSq(anchor, seed))) {
                anchor = p;
            }
        }
        return List.of(new Evaluation(anchor, blocks.size(), blocks));
    }

    private static long distSq(Pos a, Pos b) {
        long dx = (long) a.x() - b.x();
        long dz = (long) a.z() - b.z();
        return dx * dx + dz * dz;
    }
}
