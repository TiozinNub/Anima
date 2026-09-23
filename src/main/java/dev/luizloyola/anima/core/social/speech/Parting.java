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
 *
 * <p>A third clock is the player's alone: the conversation <b>set aside</b>. Putting the panel down
 * ends nothing (decision: Luiz, 2026-09-23) — a player steps back to reposition — so it only starts
 * a wait, which picking the panel back up cancels and patience runs out.
 */
public final class Parting {

    /** What the wait has come to. */
    public enum Verdict { NONE, SELF_LEFT, OTHER_GONE }

    /** Half-range so {@code now - stamp} on a never-set clock cannot overflow. */
    private static final long NEVER = Long.MIN_VALUE / 2;

    private long apartSince = NEVER;
    /** When the panel was put down on this record, or {@link #NEVER} while it is up or never was. */
    private long putDownAt = NEVER;

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

    /** The panel went away with the record still open. A second put-down keeps the first stamp. */
    public void putDown(long now) {
        if (putDownAt == NEVER) {
            putDownAt = now;
        }
    }

    /** The panel is up again: the wait never ran. */
    public void pickedUp() {
        putDownAt = NEVER;
    }

    /** Whether the conversation has been set aside for longer than {@code patience} — let drop. */
    public boolean dropped(long now, int patience) {
        return putDownAt != NEVER && now - putDownAt > patience;
    }

    /** A fresh record starts together, whatever the last one ended on — panel up, nothing waiting. */
    public void reset() {
        apartSince = NEVER;
        putDownAt = NEVER;
    }
}
