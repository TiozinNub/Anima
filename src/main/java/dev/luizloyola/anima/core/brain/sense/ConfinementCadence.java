package dev.luizloyola.anima.core.brain.sense;

/**
 * When a body asks again whether it is shut in — one slot per agent, so the answer costs what one
 * survey costs rather than what fifty do at once.
 *
 * <p><b>Asymmetric on purpose.</b> The prompt cadence was written so a body which has just cut its
 * way out stops digging instead of carrying on out of habit — that body is the SEALED one. A body
 * that has just proved it can walk out has nothing to react to, and confinement cannot change under
 * it without something changing the world or moving it, so it waits {@link #FREE_TICKS}.
 *
 * <p><b>Offset by identity, never by age.</b> {@code Entity.tickCount} starts at zero for every
 * entity in a chunk load, so a cadence keyed off it puts every body on one tick and keeps them
 * there — a hitch once a window, for as long as the world is loaded. The offset here comes from the
 * agent's own id, so it survives a reload, and a group forced onto one tick (a chunk load, autonomy
 * coming back on) spreads again on the very next ask rather than staying in lockstep.
 */
public final class ConfinementCadence {

    /** Re-ask this often while shut in — see the class note: this is the responsive case. */
    public static final int SEALED_TICKS = 20;

    /** Re-ask this often while free. The cost of being wrong is noticing late, not acting wrong. */
    public static final int FREE_TICKS = 100;

    private final int phase;
    private int dueAt;
    private boolean armed;

    /** @param seed anything permanent about the agent — its id, never its age or its position. */
    public ConfinementCadence(long seed) {
        this.phase = Math.floorMod(Long.hashCode(seed), FREE_TICKS);
    }

    /** Which of the {@link #FREE_TICKS} slots this agent owns. */
    public int phase() {
        return this.phase;
    }

    /**
     * Whether the standing verdict has gone stale.
     *
     * <p>The FIRST call only arms the clock — it never comes due on the spot. A body's tick count
     * is whatever the world handed it (zero on a chunk load, thousands when autonomy comes back
     * on), and answering the first ask immediately would put every body that started together on
     * one tick again, which is the thing this class exists to stop.
     */
    public boolean due(int now) {
        if (!this.armed) {
            this.armed = true;
            this.dueAt = nextSlot(now, FREE_TICKS);
            return false;
        }
        return now >= this.dueAt;
    }

    /** Records a survey and schedules the next from what it found. */
    public void ran(int now, Confinement verdict) {
        this.armed = true;
        this.dueAt = nextSlot(now, verdict.sealed() ? SEALED_TICKS : FREE_TICKS);
    }

    /**
     * The next tick in THIS agent's residue class, so two agents that answered on the same tick
     * come due on different ones. Landing exactly on the slot means the whole window is owed, not
     * that nothing is.
     */
    private int nextSlot(int now, int interval) {
        int ahead = Math.floorMod(this.phase - now, interval);
        return now + (ahead == 0 ? interval : ahead);
    }
}
