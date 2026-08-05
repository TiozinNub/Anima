package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
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
    private final int x;
    private final int y;
    private final int z;
    private final Gait gait;
    private boolean issued;

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
            return TaskStatus.RUNNING; // see class doc: issue, don't read
        }
        MoveState state = ctx.actuators().mover().state();
        switch (state) {
            case MOVING:
                return TaskStatus.RUNNING;
            case ARRIVED:
                return TaskStatus.SUCCESS;
            case FAILED:
                return TaskStatus.FAILED;
            case IDLE:
            default:
                // The mover was stopped out from under us — someone else took the legs; the
                // task cannot claim success.
                return TaskStatus.FAILED;
        }
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
