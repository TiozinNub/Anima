package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import java.util.Map;

/**
 * One kind of thing the sensor recognizes by growing it — the pluggable half of
 * {@link RegionGrowth}. A rule says which blocks belong ({@link #joins}) and judges the finished
 * collection ({@link #evaluate}): accepted things become a {@link PoiMemory}, the rest negative
 * claims, so the same non-thing is not re-investigated every crossing. New detections are new
 * rules; the machinery and the store never change.
 *
 * <p>Rules are stateless — one instance serves every growth of its kind.
 */
public interface GrowthRule {
    PoiKind kind();

    /** Whether this block belongs to the structure being grown. */
    boolean joins(Pos p, BlockKind kind, BlockProbe probe);

    /**
     * Judges the fully-grown collection and <b>individuates</b> it: one evaluation per distinct
     * thing the mass contains — a fused canopy is several trees, a lake is one body. Growth answers
     * "what is connected", this "how many things is that", so felling one tree of a grove does not
     * cost the memory of the others. An empty list means nothing this rule recognizes is there.
     *
     * <p>Says nothing about who is looking, so the answer is worked out once and lent to everybody
     * ({@link PlaceIndex}) instead of re-derived per observer; say where a body may walk with
     * {@link Evaluation#approach} and let the caller pick the near end.
     *
     * <p>May read the probe (surface checks): bounded by the region's size and not
     * wallet-budgeted, a one-time cost per completed growth.
     */
    List<Evaluation> evaluate(Map<Pos, BlockKind> blocks, BlockProbe probe);

    /**
     * One accepted structure: where a body may walk to reach it ({@code approach}), how much is
     * there ({@code units}), and the cells it owns ({@code blocks}) — its share of the mass,
     * claimed under its anchor. Shares are disjoint; cells no evaluation claims are, by that
     * omission, declared not-a-thing.
     *
     * <p>{@code approach} is a set of candidates, not a choice: {@link Anchors#choose} picks per
     * observer, so one structure hands two bodies different anchors without being evaluated twice.
     * Never empty for an accepted structure, and every cell in it should be one of
     * {@code blocks}.
     */
    record Evaluation(List<Pos> approach, int units, Map<Pos, BlockKind> blocks) {
        public Evaluation {
            if (approach.isEmpty()) {
                throw new IllegalArgumentException("an accepted structure with nowhere to walk");
            }
            approach = List.copyOf(approach);
        }

        /** The single-cell shape — a rule with exactly one sensible way in. */
        public Evaluation(Pos approach, int units, Map<Pos, BlockKind> blocks) {
            this(List.of(approach), units, blocks);
        }
    }
}
