package dev.luizloyola.anima.core.brain.act;

import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * The break actuator port — the working arm: core asks ("break that block"), the body implements it
 * with vanilla fidelity (crack animation, real break time from hardness and the HELD stack, swing,
 * real drops, exhaustion). The only way a task changes a block; nothing in {@code core} sees the
 * vanilla destroy machinery.
 *
 * <p>Same lifecycle contract as {@link ItemConsumer}: from the tick after a successful
 * {@link #begin}, {@link #state()} reports that break's progress, so an observed
 * {@link BreakState#IDLE} after a successful begin can only mean the break was stopped out from
 * under the task.
 */
public interface BlockBreaker {
    /**
     * Start breaking the block at this cell, replacing any break already in progress. Returns
     * {@code false} when there is nothing it can do there — air, an unbreakable block, or out of
     * arm's reach — in which case nothing was started.
     */
    boolean begin(Pos target);

    /**
     * The first cell the arm would strike on its way to {@code target}, {@code null} when the swing
     * path is clear (or the obstruction is the target itself). The ARM'S own answer, measured with
     * the geometry {@link #begin} refuses by: a caller sampling the line itself measures from
     * somewhere the eyes are not (the fancy-oak grind's leftover crop).
     */
    Pos obstruction(Pos target);

    /**
     * {@link #begin}, the block taken into the pack as it comes loose rather than dropped — what a
     * body digging under itself keeps to close the hole with. Just {@link #begin} for an arm with
     * no pack.
     */
    default boolean pry(Pos target) {
        return begin(target);
    }

    /**
     * Progress of the most recent {@link #begin}; {@link BreakState#IDLE} when there is none.
     * {@link BreakState#FINISHED} means the block broke and its drops are real in-world items —
     * the port owns the whole transaction, the task only observes it.
     */
    BreakState state();

    /**
     * Stop mid-break, abandoning the progress (the crack heals, vanilla-style); {@link #state()}
     * returns to {@link BreakState#IDLE}. Safe when idle — tasks cancel unconditionally and their
     * cancel must be idempotent, so the slack is absorbed here, exactly like
     * {@link ItemConsumer#abort}.
     */
    void abort();
}
