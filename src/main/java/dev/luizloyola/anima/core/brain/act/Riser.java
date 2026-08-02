package dev.luizloyola.anima.core.brain.act;

/**
 * The rise-one actuator port — "I need to be one higher": jump, place a carried block into the cell
 * just vacated, land on it. The body owns the jump/place timing the way the breaker owns crack
 * timing; the brain only asks for steps. Coming down needs no port of its own — what was placed is
 * ordinary blocks, broken underfoot with the {@link BlockBreaker}.
 *
 * <p>Ledger-free, unlike the removed scaffolder: the chop plan's mast is the record of what stands,
 * so the body keeps none and needs no height cap. Refusals cost nothing, and a cell that keeps
 * beating the body gets refused rather than retried forever.
 *
 * <p>Same lifecycle contract as the siblings: after a successful {@link #up}, {@link #state()}
 * reports RISING until the step lands (RISEN) or dies (FAILED); the next {@link #up} or
 * {@link #abort} clears a terminal state.
 */
public interface Riser {
    /**
     * Begin one rise step using one {@code itemId} block from the carried inventory. Returns
     * {@code false} when the step cannot start — nothing carried, no headroom, a step already in
     * flight, or this spot having already killed several steps in a row — and nothing is consumed.
     *
     * <p>That last refusal is the one callers must handle: the body's own retries are bounded, so
     * once it refuses, asking from the same spot keeps refusing — <b>treat it as "not from
     * here"</b>. A caller that re-asks every tick spins forever.
     */
    boolean up(String itemId);

    /** Progress of the most recent step; IDLE when none. */
    RiseState state();

    /** Abandon the step in flight (mid-air placement doesn't happen). Idempotent. */
    void abort();
}
