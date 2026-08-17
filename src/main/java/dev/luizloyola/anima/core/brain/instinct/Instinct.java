package dev.luizloyola.anima.core.brain.instinct;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.task.Task;

/**
 * Layer 1 of the brain — a reactive drive that bids, every tick, to be what they do right now. Each
 * instinct reports a scalar {@code pressure} the
 * {@link dev.luizloyola.anima.core.brain.Arbiter} compares against every other's, and the winner
 * supplies a {@code root} task tree for the executor to run.
 *
 * <p>An instinct never touches actuators and never runs a task itself — it names a want and how
 * strongly it wants it. The set is fixed and small, each one screen of code.
 */
public interface Instinct {

    /**
     * How strongly this drive wants to run right now, {@code 0..1} on a shared scale — must be
     * cheap: the arbiter reads it for every instinct every tick. A constant is fine for an ambient
     * default like wander.
     */
    double pressure(BrainContext ctx);

    /**
     * A FRESH task tree to pursue this drive, built anew on every grant — never a cached instance.
     * That freshness is the continuous-behavior loop: the arbiter re-grants the incumbent after a
     * SUCCESS by calling this again.
     */
    Task root(BrainContext ctx);

    /**
     * The most this drive will pay for a method while it holds the wheel, in the walk-block
     * currency methods price themselves in — published by the
     * {@link dev.luizloyola.anima.core.brain.Arbiter} as
     * {@link dev.luizloyola.anima.core.brain.BrainContext#costTolerance()}, where the executor
     * treats anything dearer as inapplicable.
     *
     * <p><b>The budget belongs to the drive, not to a shared curve.</b> It used to be one
     * hunger-shaped ramp every drive was fed through, so a need that was not hunger inherited
     * hunger's idea of what desperation is worth. A {@link NeedDrive} answers from the level its
     * body is currently at, which is a per-species number in a config file.
     *
     * <p>The default is unbounded — the same thing the arbiter means when nothing is active, and
     * the right answer for an emergency (Flee, Escape) that must never be priced out of surviving.
     * A drive that should not license an expensive method says so: see {@link WanderInstinct}.
     */
    default double costTolerance(BrainContext ctx) {
        return Double.POSITIVE_INFINITY;
    }

    /** One-word drive name for the debug readout — {@code "eat"}, {@code "wander"}. */
    String describe();

    /**
     * A stable name for this drive, for anything that refers to one across a restart — a saved
     * fail-cooldown most of all. The class's simple name, not its list position: that
     * list is ordered for tie-breaking, so adding a drive would re-point every saved index at the
     * wrong one. A name that stops matching only drops a cooldown.
     */
    default String key() {
        return getClass().getSimpleName();
    }

    /**
     * Ticks this instinct sits out after its root FAILED. Most drives use the default; an
     * emergency drive (Flee) overrides it small — a cornered Person must retry immediately, not
     * stand still being eaten.
     */
    default int failCooldown() {
        return DEFAULT_FAIL_COOLDOWN;
    }

    /**
     * The default {@link #failCooldown()} — anti fail-spin, so a drive that cannot currently be
     * satisfied doesn't monopolize the wheel while lower drives starve (see
     * {@link dev.luizloyola.anima.core.brain.Arbiter}). Layer 3's escalation flag supersedes it.
     */
    int DEFAULT_FAIL_COOLDOWN = 100;
}
