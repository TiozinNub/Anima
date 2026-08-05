package dev.luizloyola.anima.core.appearance;

import java.util.List;
import java.util.Objects;

/**
 * Everything needed to bake one appearance: a canvas, the parts that never change, and the parts
 * that do.
 *
 * <p>A consumer composes one from whatever it considers an appearance; Anima bakes, caches and
 * names the result, knowing nothing about what any of it means. The canvas size lives here rather
 * than being assumed, so a 64×32 wolf works on the same machinery as a 64×64 person.
 *
 * <h2>Why the split</h2>
 * By <em>churn</em>: recompositing every part through its colour operations at animation rates
 * across a settlement does not perform.
 *
 * <ul>
 *   <li>{@link #statics()} — body, hair, clothes, colour operations already resolved. Cached as
 *       pixels under {@link #staticHash()} and <b>shared</b> by every agent whose statics match.</li>
 *   <li>{@link #dynamics()} — the small, often-changing sprites, blitted over a copy of the static
 *       base, so a change restores a rectangle.</li>
 * </ul>
 *
 * <p>The static base is also the agent with no mood and no grime — the <b>neutral texture</b> a
 * portrait wants, at no extra cost.
 *
 * <p>⚠️ The canvas size is deliberately <b>not</b> part of either hash (decision: Luiz), which is
 * safe only because part lists are species-specific: two recipes cannot share one part list across
 * two canvas sizes.
 */
public record Recipe(int width, int height, List<Part> statics, List<Part> dynamics) {
    public Recipe {
        statics = List.copyOf(Objects.requireNonNull(statics, "statics"));
        dynamics = List.copyOf(Objects.requireNonNull(dynamics, "dynamics"));
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("a canvas of " + width + "x" + height + " bakes nothing");
        }
    }

    /** A recipe with nothing that changes. */
    public static Recipe of(int width, int height, List<Part> statics) {
        return new Recipe(width, height, statics, List.of());
    }

    /**
     * Names the finished texture — every part, in order. Two agents sharing this share one baked
     * texture and one entry in the cache.
     */
    public long hash() {
        return Canonical.hash(Canonical.stream(all()));
    }

    /**
     * Names the <em>static base</em> — the shared pixels the dynamic parts are drawn over, and the
     * neutral texture a portrait uses. Equal to {@link #hash()} when nothing is dynamic.
     */
    public long staticHash() {
        return Canonical.hash(Canonical.stream(statics));
    }

    /** Every part in composite order — statics first, then the dynamics drawn over them. */
    public List<Part> all() {
        if (dynamics.isEmpty()) {
            return statics;
        }
        List<Part> parts = new java.util.ArrayList<>(statics.size() + dynamics.size());
        parts.addAll(statics);
        parts.addAll(dynamics);
        return List.copyOf(parts);
    }
}
