package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.act.MoveFailure;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.nav.Gait;
import java.util.Locale;

/**
 * The first primitive: walk to a cell. Issues one
 * {@link dev.luizloyola.anima.core.brain.act.Mover#moveTo} and translates the mover's lifecycle into
 * task status.
 *
 * <p><b>First tick: issue, don't read.</b> It returns {@link TaskStatus#RUNNING} unconditionally and
 * {@code state()} is consulted only from the second tick, the port promising progress only "from the
 * next tick on". Past that tick an observed {@link MoveState#IDLE} means one thing — someone else
 * stopped the legs, as the arbiter preempts — so it is clearly a failure. Cost: a goto to the cell
 * you already stand on takes two ticks.
 *
 * <p>Later ticks map MOVING → RUNNING, ARRIVED → SUCCESS, FAILED → FAILED, IDLE → FAILED.
 *
 * <p><b>Gait.</b> The {@link #GoTo(int, int, int, Gait) four-arg constructor} threads the pace to
 * {@link dev.luizloyola.anima.core.brain.act.Mover#moveTo(int, int, int, Gait)} — {@code FleeStep}
 * orders {@link Gait#SPRINT}, {@code WanderStep} {@link Gait#STROLL}; the plain constructor is
 * {@link Gait#WALK}.
 */
public final class GoTo implements PrimitiveTask {

    /**
     * How long the eyes rest on a destination before the legs get on with it — half a second.
     * Long enough to read as a glance rather than a flicker, short enough that the body is looking
     * where it walks by the time it has taken a step.
     */
    public static final int GLANCE_TICKS = 10;

    private final int x;
    private final int y;
    private final int z;
    private final Gait gait;
    private boolean issued;
    /**
     * Why the walk died, captured on the tick it is observed. Not persisted, and it does not need
     * to be: the executor asks for it in the same tick this task returns FAILED, so it never
     * outlives the tick that set it.
     */
    private MoveFailure failure = MoveFailure.NONE;

    /** An ordinary walk to {@code (x, y, z)} — see the class doc on gait. */
    public GoTo(int x, int y, int z) {
        this(x, y, z, Gait.WALK);
    }

    /** @param gait see {@link dev.luizloyola.anima.core.brain.act.Mover#moveTo(int, int, int, Gait)} */
    public GoTo(int x, int y, int z, Gait gait) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.gait = gait;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (!issued) {
            issued = true;
            ctx.actuators().mover().moveTo(x, y, z, gait);
            // Look where you are about to go: eyes reaching the destination before the legs is what
            // reads as intent. A claim, so anything that actually needs the head outranks it, and it
            // lapses on its own rather than having to be called off.
            ctx.actuators().gazer().lookAt(x + 0.5, y + 1.0, z + 0.5, Gazer.Priority.NAV,
                    GLANCE_TICKS);
            return TaskStatus.RUNNING; // see class doc: issue, don't read
        }
        MoveState state = ctx.actuators().mover().state();
        switch (state) {
            case MOVING:
                return TaskStatus.RUNNING;
            case ARRIVED:
                return TaskStatus.SUCCESS;
            case FAILED:
                // Read the reason HERE, not from failureDetail(): that call takes no context, and
                // by then the legs may already have been re-ordered by whatever ran next.
                this.failure = ctx.actuators().mover().failure();
                return TaskStatus.FAILED;
            case IDLE:
            default:
                // The mover was stopped out from under us — someone else took the legs; the
                // task cannot claim success. The legs never report this themselves (a stopped
                // Navigator is IDLE, not FAILED), so the reading is made here.
                this.failure = MoveFailure.STOPPED;
                return TaskStatus.FAILED;
        }
    }

    /**
     * The walk's ending, named — the reason channel into the journal, so four different causes no
     * longer print the same "goto (x, y, z) failed".
     */
    @Override
    public String failureDetail() {
        return failure == MoveFailure.NONE
                ? describe() + " failed"
                : describe() + " failed — " + failure.describe();
    }

    @Override
    public void cancel(BrainContext ctx) {
        // Idempotent because Mover.stop() absorbs the no-move case (see its doc) — safe before
        // the first tick, after arrival, or twice in a row.
        ctx.actuators().mover().stop();
    }

    @Override
    public String describe() {
        String pace = gait == Gait.WALK ? "" : " (" + gait.name().toLowerCase(Locale.ROOT) + ")";
        return "goto (" + x + ", " + y + ", " + z + ")" + pace;
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────
    // What this task is, and where it has got to. `issued` is the difference between a walk that
    // has been ordered and one that has not: without it a reload re-orders the walk, which reads
    // as a body that hesitated.

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public Gait gait() {
        return gait;
    }

    public boolean issued() {
        return issued;
    }

    /** Puts a saved mid-walk back — see the note above. */
    public GoTo resume(boolean issued) {
        this.issued = issued;
        return this;
    }
}
