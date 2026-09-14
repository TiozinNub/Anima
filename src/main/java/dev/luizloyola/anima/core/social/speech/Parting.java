package dev.luizloyola.anima.core.social.speech;

/**
 * The trailed-off clock of a participant with no perception to run one — who left, and for how
 * long. {@code Converse} reads the same question off a settler's own percepts; a player's seat
 * reads distance and hands the answer here, one call per tick, and this decides when the wait
 * has run.
 *
 * <p>Two clocks, one stamp: the moment the two came apart. A participant who left is called on
 * it after a short grace; a counterpart who is gone gets patience. Together again clears the
 * stamp, and so does every verdict — a clock that outlived its record closed the next one the
 * moment its first line landed (client-caught 2026-09-14).
 */
public final class Parting {

    /** What the wait has come to. */
    public enum Verdict { NONE, SELF_LEFT, OTHER_GONE }

    /** Half-range so {@code now - stamp} on a never-set clock cannot overflow. */
    private static final long NEVER = Long.MIN_VALUE / 2;

    private long apartSince = NEVER;

    /**
     * One tick of the wait.
     *
     * @param selfLeft whether this participant has moved off the spot the conversation was at
     * @param otherGone whether the counterpart is beyond reach, or gone altogether
     * @param selfGrace how long {@code selfLeft} must hold before it is leaving
     * @param otherPatience how long {@code otherGone} must hold before they are let go
     */
    public Verdict tick(long now, boolean selfLeft, boolean otherGone, int selfGrace,
            int otherPatience) {
        if (!selfLeft && !otherGone) {
            apartSince = NEVER; // together again; whatever was running never ran
            return Verdict.NONE;
        }
        if (apartSince == NEVER) {
            apartSince = now;
            return Verdict.NONE;
        }
        if (selfLeft && now - apartSince > selfGrace) {
            apartSince = NEVER;
            return Verdict.SELF_LEFT;
        }
        if (otherGone && now - apartSince > otherPatience) {
            apartSince = NEVER;
            return Verdict.OTHER_GONE;
        }
        return Verdict.NONE;
    }

    /** A fresh record starts together, whatever the last one ended on. */
    public void reset() {
        apartSince = NEVER;
    }
}
