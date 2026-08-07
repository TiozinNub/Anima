package dev.luizloyola.anima.core.agent.need;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.Metabolism;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Hunger as a gauge — a VIEW over the body's {@link Metabolism}, and never a second number.
 *
 * <p>The metabolism is the physiology — saturation, exhaustion, a regen cadence, ticked against
 * vanilla's own constants — and the food bar is one reading off it. A stored copy would give a body
 * two answers to "how hungry are you" that drift apart, so this holds no state: every call goes to
 * the organ.
 *
 * <p>The bands come from one declaration, {@link NeedKind#HUNGER}'s levels, and the ramp through
 * them reproduces {@code 1 - food/20} exactly, because the corners were always collinear.
 *
 * <p>{@link #tick()} does nothing: the body ticks the metabolism itself, since only it knows the
 * gamerule and whether it is hurt, and only it can apply the heal or starvation hit.
 */
public final class FoodNeed implements Gauge {

    private final Metabolism metabolism;
    private final Supplier<AgentProfile> profile;

    public FoodNeed(Metabolism metabolism, Supplier<AgentProfile> profile) {
        this.metabolism = Objects.requireNonNull(metabolism, "metabolism");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public NeedKind kind() {
        return NeedKind.HUNGER;
    }

    /** The food bar itself, {@code 0..20} — the organ's number, in the organ's units. */
    @Override
    public double value() {
        return metabolism.foodLevel();
    }

    @Override
    public double pressure() {
        return NeedKind.HUNGER.ramp().pressureAt(profile.get(), value());
    }

    @Override
    public NeedLevel level() {
        return NeedKind.HUNGER.ramp().levelAt(profile.get(), value());
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "food %.0f/%.0f sat %.1f exh %.1f (%s)",
                value(), NeedKind.HUNGER.axisMax(), metabolism.saturation(),
                metabolism.exhaustion(), level().key());
    }
}
