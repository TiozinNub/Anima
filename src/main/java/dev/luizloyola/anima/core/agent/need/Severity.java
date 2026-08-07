package dev.luizloyola.anima.core.agent.need;

/**
 * How loudly a need is asking, in one word that means the same thing whichever need said it — so a
 * HUD can colour it and a filter can ask "is anybody desperate?" without knowing what hunger is.
 *
 * <p><b>Derived from pressure, and nothing branches on it</b> (decision: Luiz, 2026-08-06).
 * Derived, so it can never disagree with the number it summarises; behaviour-free, so there is one
 * ranking rather than two that drift apart — a drive reads {@link Gauge#pressure()}, only pixels
 * read this.
 *
 * <p>The thresholds are the ones the hunger bands were carved at, and now mean that for every need.
 */
public enum Severity {

    COMFORTABLE,
    /** Wanting, but outbid by almost anything. */
    NAGGING,
    /** Wanting enough to win an argument. */
    URGENT,
    /** Wanting enough that price stops mattering. */
    DESPERATE;

    /** Where {@code pressure} falls: {@code >= 0.85} DESPERATE, {@code >= 0.60} URGENT, {@code >= 0.30} NAGGING. */
    public static Severity of(double pressure) {
        if (pressure >= 0.85) {
            return DESPERATE;
        }
        if (pressure >= 0.60) {
            return URGENT;
        }
        return pressure >= 0.30 ? NAGGING : COMFORTABLE;
    }
}
