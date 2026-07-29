package dev.luizloyola.anima.core.brain.sense;

/**
 * Who decides how many line-of-sight checks one body may run this tick.
 *
 * <p>Sight is the expensive channel (every ray is a block walk through the world), and the sense
 * spends more of them when it is behind, so a hundred mobs arriving at once are noticed within
 * about four ticks. The ceiling is aggregate, not per-agent: eight rays each with three hundred
 * agents is two thousand four hundred a tick and protects nothing.
 *
 * <p>Asking for more than is granted is normal and safe — an over-budget re-check waits a tick, an
 * over-budget discovery stays queued — so a tight budget makes agents slower to notice things,
 * never blind to them.
 */
@FunctionalInterface
public interface RayBudget {

    /**
     * How many of {@code wanted} rays this body may spend on tick {@code now}. Never negative,
     * never more than asked.
     *
     * @param agent identity of the asking body, so the pool can be fair between them across ticks.
     *     Compared by identity; any stable per-body object will do.
     */
    int grant(Object agent, int wanted, long now);

    /** No ceiling at all — everyone gets what they ask for. For headless rigs and single-agent tests. */
    RayBudget UNMETERED = (agent, wanted, now) -> wanted;
}
