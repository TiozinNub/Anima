package dev.luizloyola.anima.core.brain.knowledge;

/**
 * Who decides how many blocks one body may read this tick.
 *
 * <p>Places are the expensive channel: at 150 walkers in a real wood it was very nearly the whole
 * server thread, nine reads in ten the ray fan. The ceiling that matters is aggregate —
 * {@code limits.reads_per_tick} caps one body, and 256 each across 300 agents is 77,000 reads a
 * tick. Same shape as {@code RayBudget} for the being sense, and a second pool: one
 * allowance would let a body in a dense wood spend it all on foliage and go blind to the creeper
 * behind it.
 *
 * <p>Being granted less than asked is normal and safe — {@code PoiSensorCore} serves the near
 * field first and the skyline sweep takes what is left; columns wait, growths resume next tick.
 */
@FunctionalInterface
public interface ReadBudget {

    /**
     * How many of {@code wanted} block reads this body may spend on tick {@code now}. Never
     * negative, never more than asked.
     *
     * @param agent identity of the asking body, so the pool can be fair between them across ticks.
     *     Compared by identity; any stable per-body object will do.
     */
    int grant(Object agent, int wanted, long now);

    /**
     * Hands back what was granted and not spent, on the same tick it was granted. The place sense
     * asks for its whole allowance and only then discovers how little it needs, unlike the being
     * sense; without this, a hundred idle agents would hold a busy one out of a budget nobody was
     * using.
     */
    default void refund(Object agent, int unused, long now) {
    }

    /** No ceiling at all — everyone gets what they ask for. For headless rigs and single-agent tests. */
    ReadBudget UNMETERED = (agent, wanted, now) -> wanted;
}
