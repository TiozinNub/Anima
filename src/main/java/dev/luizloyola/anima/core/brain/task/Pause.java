package dev.luizloyola.anima.core.brain.task;

/**
 * A worked pause: the tick budget a timed action spends before its effect lands.
 *
 * <p>One {@code int}, so a task's codec carries it and a reload resumes the pause instead of
 * restarting the work — the property {@code CraftStep} has had since crafting learned to take time.
 *
 * <p><b>It holds no progress beyond the current unit, and that is deliberate.</b> Preemption cancels
 * the deepest primitive and drops the whole tree, so anything a task keeps in its own fields is
 * destroyed by an interruption. A timed action lets the WORLD hold its progress — each completed
 * unit is a fact out there — and keeps only the countdown in here.
 */
public final class Pause {

    private int remaining;

    /** Whether nothing is being waited on — the moment to verify the world and start a unit. */
    public boolean idle() {
        return remaining == 0;
    }

    /** Begins a pause of {@code ticks}. A non-positive budget is no pause at all, not a stuck task. */
    public void start(int ticks) {
        this.remaining = Math.max(0, ticks);
    }

    /**
     * Spends one tick. {@code true} exactly once — on the tick the pause completes, which is the
     * tick the caller's effect belongs on.
     */
    public boolean elapsed() {
        return remaining > 0 && --remaining == 0;
    }

    /** Ticks left, for the codec. */
    public int remaining() {
        return remaining;
    }

    /** Puts a reload back mid-pause. */
    public void restore(int ticks) {
        this.remaining = Math.max(0, ticks);
    }
}
