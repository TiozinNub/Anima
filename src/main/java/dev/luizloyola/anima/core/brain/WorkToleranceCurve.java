package dev.luizloyola.anima.core.brain;

/**
 * The commitment side's cost budget — deliberately not a need's own (decision: Luiz): need-pressure
 * is <em>desperation</em>, unbounded once a body is starving, while a work item's priority is
 * <em>policy</em> — a job is worth a fixed effort, and nothing on a board ever spends like a
 * starving person.
 *
 * <p>A job priced out again and again earns a {@link #STEP} of budget per failure (Luiz,
 * 2026-10-01): its one way does not get cheaper by waiting, and a furnace whose stone lay 4 blocks
 * past the budget waited 22,000 ticks for its settler to wander near. Still capped, and the steps
 * end when the job succeeds.
 */
public final class WorkToleranceCurve {
    /** Blocks of acceptable method cost for the lowest-priority work. Tuning knob. */
    public static final double BASE = 40.0;
    public static final double PER_PRIORITY = 80.0;
    public static final double CAP = 150.0;
    /** Blocks of budget each priced-out failure adds. */
    public static final double STEP = 16.0;
    /** Steps past which nothing more is added: the cap is reached from any priority. */
    public static final int MAX_STEPS = (int) Math.ceil((CAP - BASE) / STEP);

    private WorkToleranceCurve() {
    }

    public static double tolerance(double priority) {
        return tolerance(priority, 0);
    }

    /** The budget of a job priced out {@code steps} times running. */
    public static double tolerance(double priority, int steps) {
        return Math.min(CAP, BASE + PER_PRIORITY * priority + STEP * steps);
    }
}
