package dev.luizloyola.anima.core.agent.need;

import dev.luizloyola.anima.core.agent.AgentProfile;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Air as a gauge — a VIEW over the body's own air supply, and never a second number.
 *
 * <p>Where hunger needed an organ ({@code Metabolism}) because vanilla gives a plain living body no
 * food data, air is already ticked by the game for anything alive, so this holds no state at all:
 * every call goes to the body.
 *
 * <p><b>Two suppliers rather than the body itself</b>, because this is {@code core/} and the body
 * is a Minecraft entity — and a consumer whose creature keeps its air elsewhere supplies that
 * instead.
 *
 * <p><b>The reading stops at empty.</b> The game runs the counter past zero into negative numbers
 * as its damage timer; the axis this need declared begins at zero, and below it the body is
 * {@code drowning} either way.
 *
 * <p>{@link #tick()} does nothing: what moves this is the game's business.
 */
public final class BreathNeed implements Gauge {

    private final IntSupplier air;
    private final IntSupplier fullBreath;
    private final Supplier<AgentProfile> profile;

    /**
     * @param air how many ticks of air the body has left, right now
     * @param fullBreath how many it has when it has just surfaced — read live, because a lungful is
     *     per body and may be shifted by a modifier mid-life. Expected to be the same declaration
     *     {@code BREATH}'s {@code easy} level carries (see that need); reported by
     *     {@link #describe()} so a body where the two ever came apart says so in a readout instead
     *     of quietly spending half of every breath at a pressure it should not feel
     * @param profile this body's resolved aspects, as a supplier — bodies build their roster in
     *     field initialisers, before they can answer what species they are
     */
    public BreathNeed(IntSupplier air, IntSupplier fullBreath, Supplier<AgentProfile> profile) {
        this.air = Objects.requireNonNull(air, "air");
        this.fullBreath = Objects.requireNonNull(fullBreath, "fullBreath");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public NeedKind kind() {
        return NeedKind.BREATH;
    }

    /** Ticks of air left, never below empty — the body's own number, in the body's own units. */
    @Override
    public double value() {
        return Math.max(0, air.getAsInt());
    }

    @Override
    public double pressure() {
        return NeedKind.BREATH.ramp().pressureAt(profile.get(), value());
    }

    @Override
    public NeedLevel level() {
        return NeedKind.BREATH.ramp().levelAt(profile.get(), value());
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "air %.0f/%d (%s)",
                value(), fullBreath.getAsInt(), level().key());
    }
}
