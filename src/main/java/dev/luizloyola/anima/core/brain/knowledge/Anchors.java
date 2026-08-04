package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Comparator;
import java.util.List;

/**
 * Which end of a known structure <em>this</em> body would walk to — the observer's half of
 * recognising a place. The world's half is worked out once and lent to everybody, so
 * {@link GrowthRule#evaluate} runs once per structure, not once per body.
 *
 * <p><b>Lowest first, then nearest</b>: you fell a tree at its stump, and approach cells are
 * usually a structure's floor. Distance is horizontal, or a body on a hill would prefer the far
 * side of the crown. Ties break by cell order, since an anchor is a memory's identity.
 */
public final class Anchors {

    /** Low-to-high, then west-to-east, then north-to-south — the tie-break of last resort. */
    private static final Comparator<Pos> ORDER = Comparator.comparingInt(Pos::y)
            .thenComparingInt(Pos::x).thenComparingInt(Pos::z);

    private Anchors() {
    }

    /**
     * The cell of {@code approach} a body at {@code from} would head for. Never null for a
     * non-empty list, which {@link GrowthRule.Evaluation} guarantees for anything accepted.
     */
    public static Pos choose(List<Pos> approach, Pos from) {
        Pos best = null;
        long bestDist = Long.MAX_VALUE;
        for (Pos cell : approach) {
            if (best == null) {
                best = cell;
                bestDist = horizontalDistSq(cell, from);
                continue;
            }
            if (cell.y() > best.y()) {
                continue; // higher: down beats near
            }
            long dist = horizontalDistSq(cell, from);
            if (cell.y() < best.y() || dist < bestDist
                    || (dist == bestDist && ORDER.compare(cell, best) < 0)) {
                best = cell;
                bestDist = dist;
            }
        }
        return best;
    }

    /** Squared horizontal distance — height never decides which end of a thing you walk to. */
    public static long horizontalDistSq(Pos a, Pos b) {
        long dx = a.x() - b.x();
        long dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }
}
