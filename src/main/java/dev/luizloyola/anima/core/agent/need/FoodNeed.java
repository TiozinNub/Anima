package dev.luizloyola.anima.core.agent.need;

import dev.luizloyola.anima.core.agent.Metabolism;
import java.util.Objects;

/**
 * Hunger as a gauge — a VIEW over the body's {@link Metabolism}, and never a second number: a
 * stored copy would give a body two answers to "how hungry are you" that agree on the first day and
 * drift by the second. This holds no state at all; every call goes to the organ.
 *
 * <p>{@link #tick()} therefore does nothing — only the body knows the gamerule and whether it is
 * hurt, and only the body can apply the heal or the starvation hit that comes back.
 */
public final class FoodNeed implements Gauge {

    private final Metabolism metabolism;

    public FoodNeed(Metabolism metabolism) {
        this.metabolism = Objects.requireNonNull(metabolism, "metabolism");
    }

    @Override
    public NeedKind kind() {
        return NeedKind.FOOD;
    }

    /** A full bar is 1, an empty one is 0 — {@code foodLevel / 20}. */
    @Override
    public double level() {
        return 1.0 - metabolism.hunger();
    }

    /**
     * The metabolism's own hunger, unchanged: {@code 1 - food/20}. The arbiter's tolerance curve
     * and the Eat instinct read the same number through the organ. That is what keeps the band
     * thresholds ({@code 0.30 / 0.60 / 0.85}) meaning one thing.
     */
    @Override
    public double pressure() {
        return metabolism.hunger();
    }

    @Override
    public String describe() {
        return metabolism.describe();
    }
}
