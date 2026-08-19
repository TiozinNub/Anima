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

    /**
     * Begins a pause of {@code ticks}, clamped to zero or above so a negative value cannot count
     * up forever. Zero itself is not made safe here: idle() would read true again on the very next
     * check, so a phase loop that starts the next unit on idle spins forever rather than
     * completing — which is why every duration knob that feeds this is floored at 1, not clamped
     * to 0 here.
     *
     * <p><b>The tick that calls this is also a counting tick.</b> A pause of N ticks costs exactly
     * N — this call and the first {@link #elapsed()} that completes it may land on the very same
     * tick. A caller must therefore fall through to {@link #elapsed()} in the same {@code tick()}
     * invocation rather than returning {@code RUNNING} right after {@code start}; returning early
     * spends a whole extra tick starting the pause before a single unit of it has counted down,
     * making an N-tick pause cost N+1.
     */
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
