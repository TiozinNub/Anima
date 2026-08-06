package dev.luizloyola.anima.core.appearance.catalog;

import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * A curated set of colours an agent's binding may take, and how likely each of them is.
 *
 * <p>A ladder is the <b>list of values this kind of thing is allowed to be</b>, in order, so a
 * position on it means something: storing the position rather than the colour is what makes a tone
 * heritable and two settlers comparable.
 *
 * <h2>Weights</h2>
 * Unweighted does <b>not</b> mean uniform — a flat draw over a twelve-entry tone ladder reads as a
 * random table rather than a population — so a bare array is drawn <b>triangularly</b>, common in
 * the middle and rare at the ends. That can only say "the middle is commoner", an accident of where
 * a colour was typed; {@code weights} states it outright, one number per colour, any scale.
 *
 * <pre>
 *   "skin": ["F5D0B0", "EFC0A0", …]                        // triangular over the order given
 *   "skin": { "colors": [ … ], "weights": [1, 3, 8, 8, 3, 1] }   // said outright
 * </pre>
 *
 * <p>Weights are relative: {@code [1, 2]} and {@code [50, 100]} are the same ladder. A weight of
 * <b>zero excludes a colour</b> without removing it — deleting an entry would renumber every colour
 * after it and silently repaint every agent already carrying one of those positions.
 */
public record LadderSpec(List<Integer> colors, List<Integer> weights) {

    /** A colour for a ladder that has none, or an index into an empty one. */
    public static final int FALLBACK = 0xFFFFFF;

    public LadderSpec {
        colors = List.copyOf(Objects.requireNonNull(colors, "colors"));
        weights = List.copyOf(Objects.requireNonNull(weights, "weights"));
        if (!weights.isEmpty() && weights.size() != colors.size()) {
            throw new IllegalArgumentException(
                    "a ladder has " + colors.size() + " colour(s) but " + weights.size() + " weight(s)");
        }
        for (int weight : weights) {
            if (weight < 0) {
                throw new IllegalArgumentException("a ladder weight may not be negative: " + weight);
            }
        }
    }

    /** An unweighted ladder — drawn triangularly, see the class note. */
    public static LadderSpec of(List<Integer> colors) {
        return new LadderSpec(colors, List.of());
    }

    public int size() {
        return colors.size();
    }

    public boolean weighted() {
        return !weights.isEmpty();
    }

    /** The colour at this position, wrapping, so no index can break a bake. */
    public int color(int index) {
        return colors.isEmpty() ? FALLBACK : colors.get(Math.floorMod(index, colors.size()));
    }

    /**
     * A position on this ladder.
     *
     * <p>Weighted where weights were authored, triangular where they were not — and never uniform,
     * for the reason in the class note.
     */
    public int pick(RandomGenerator random) {
        if (colors.isEmpty()) {
            return 0;
        }
        if (!weighted()) {
            // The mean of two uniform draws. Integer division tilts the result imperceptibly low,
            // uncorrected: the point is a hump rather than a flat line.
            return colors.size() == 1 ? 0
                    : (random.nextInt(colors.size()) + random.nextInt(colors.size())) / 2;
        }
        int total = weights.stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            // Every weight zero is a ladder somebody switched off entirely: fall back to the first
            // colour rather than divide by it.
            return 0;
        }
        int roll = random.nextInt(total);
        for (int at = 0; at < weights.size(); at++) {
            roll -= weights.get(at);
            if (roll < 0) {
                return at;
            }
        }
        return weights.size() - 1;
    }
}
