package dev.luizloyola.anima.core.agent.need;

/**
 * One gauge the body owns and ticks — a level, a pressure, and a line of text about itself.
 *
 * <p><b>Level and pressure are two numbers.</b> Level is how full the gauge is, pressure
 * how much the brain should care; for hunger they are mirrored, but {@link Company}'s comfort runs
 * out at <em>both</em> ends and one number cannot say "0.95 is as wrong as 0.05". Instincts bid on
 * pressure.
 *
 * <p>Needs are BODY state: the brain reads them and never writes them, so there are no
 * setters — whatever moves a gauge is its own typed business, named for what happened to the body
 * ({@code eat}, {@code observe}).
 */
public interface Gauge {

    /** What this gauge measures — its name in listings, commands and on disk. */
    NeedKind kind();

    /** How full, {@code 0..1}. What "full" means is the gauge's own business. */
    double level();

    /**
     * How badly this wants the brain's attention, {@code 0..1} — what an instinct bids on. 0 means
     * this need is not asking for anything, which is a different statement from {@code level() == 0}.
     */
    double pressure();

    /** One line for an operator: the level, whatever bounds explain it, and the band it falls in. */
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
