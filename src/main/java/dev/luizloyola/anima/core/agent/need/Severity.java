package dev.luizloyola.anima.core.agent.need;

/**
 * How loudly a need is asking, in one word that means the same whichever need said it — so a HUD, a
 * command or a filter can use it without knowing what hunger or company are.
 *
 * <p><b>Derived from pressure, and nothing branches on it</b> (decision: Luiz, 2026-08-06): it
 * cannot disagree with the number it summarises, and there is one ranking rather than two. A drive
 * reads {@link Gauge#pressure()}; only pixels read this.
 *
 * <p><b>The words are deliberately colourless</b> (decision: Luiz, 2026-08-08): a rung prints
 * beside the need's own word ({@code peckish}, {@code lonely}), so feeling-words
 * ({@code desperate}) would read as a competing opinion, and a mod registering warmth or boredom
 * would inherit somebody else's adjectives.
 *
 * <p>The thresholds are the ones the hunger bands were carved at, now the scale for every need.
 */
public enum Severity {

    COMFORTABLE,
    /** Wanting, but outbid by almost anything. */
    MILD,
    /** Wanting enough to win an argument. */
    URGENT,
    /** Wanting enough that price stops mattering. */
    CRITICAL;

    /** Where {@code pressure} falls: {@code >= 0.85} CRITICAL, {@code >= 0.60} URGENT, {@code >= 0.30} MILD. */
    public static Severity of(double pressure) {
        if (pressure >= 0.85) {
            return CRITICAL;
        }
        if (pressure >= 0.60) {
            return URGENT;
        }
        return pressure >= 0.30 ? MILD : COMFORTABLE;
    }
}
