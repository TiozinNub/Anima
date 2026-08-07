package dev.luizloyola.anima.core.agent.need;

import org.jspecify.annotations.Nullable;

/**
 * One gauge the body owns and ticks — a reading, a pressure, a word for how it feels, and a line of
 * text about itself.
 *
 * <p><b>Value and pressure are two numbers.</b> The value is in the need's own units,
 * what an operator tunes and what a readout prints; the pressure is the cross-need currency,
 * {@code 0..1}, the one number a drive compares against another drive's. {@link Company}'s comfort
 * runs out at <em>both</em> ends (0.05 and 0.95 are equally uncomfortable), which one number could
 * never say.
 *
 * <p>Needs are BODY state: the brain reads them and never writes them, so this interface has no
 * setters. Whatever moves a gauge is its own typed business, named for what happened to the body
 * ({@code eat}, {@code observe}, {@code conversed}) and reached with
 * {@link Needs#gauge(NeedKind, Class)}.
 */
public interface Gauge {

    /** What this gauge measures — its name in listings, commands and on disk. */
    NeedKind kind();

    /** The current reading, in {@link NeedKind#unit()}. What "full" means is the need's business. */
    double value();

    /**
     * How badly this wants the brain's attention, {@code 0..1} — what a drive bids. 0 means this
     * need is not asking for anything, which is a different statement from a reading of 0.
     */
    double pressure();

    /**
     * What this body is called right now — {@code peckish}, {@code alone}. Null only for a need
     * declared without levels, whose gauge answers for its own pressure.
     */
    @Nullable
    NeedLevel level();

    /**
     * How loudly this is asking, in a word that means the same for every need. Derived, so it can
     * never disagree with the pressure it summarises. For the eye only — see {@link Severity}.
     */
    default Severity severity() {
        return Severity.of(pressure());
    }

    /** One line for an operator: the reading, whatever bounds explain it, and what it is called. */
    String describe();

    /**
     * Advance one tick. The default does nothing — correct for a <em>view</em> over state the body
     * ticks elsewhere ({@link FoodNeed} over the metabolism). A gauge that owns its number overrides
     * this; what it needs from outside arrives through its own typed observers, so this one call
     * never grows an argument for a need Anima has not heard of.
     */
    default void tick() {
    }
}
