package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.act.Hand;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * Shut one door, from where the body stands (shelter spec, rung 4). A door already shut SUCCEEDS:
 * somebody else got there, and the plan holds.
 *
 * <p>FAILS rather than shut a threat in: one standing in the doorway, or already on this side of
 * it, got there first, and the next leg is ordinary flight.
 *
 * <p>Waits while somebody else is coming through ({@link Hand#crossing}): shut on them, the door
 * was swung back open by their next step, and the two traded it every tick (2026-10-28).
 */
public final class ShutDoor implements PrimitiveTask {

    /** How long a crossing is waited on: a body a step from the door is through in one second. */
    static final int WAIT_TICKS = 100;

    private final Pos door;
    private int waited;
    private String failure = "";

    public ShutDoor(int x, int y, int z) {
        this.door = new Pos(x, y, z);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        ctx.actuators().gazer().lookAt(door.x() + 0.5, door.y() + 1.0, door.z() + 0.5,
                Gazer.Priority.WORK);
        if (beaten(ctx)) {
            failure = "something got there first";
            return TaskStatus.FAILED;
        }
        if (ctx.actuators().hand().crossing(door)) {
            if (++waited > WAIT_TICKS) {
                failure = "somebody kept the doorway";
                return TaskStatus.FAILED;
            }
            return TaskStatus.RUNNING;
        }
        if (!ctx.actuators().hand().shut(door)) {
            failure = "it would not shut";
            return TaskStatus.FAILED;
        }
        return TaskStatus.SUCCESS;
    }

    @Override
    public String failureDetail() {
        return describe() + " failed — " + failure;
    }

    private boolean beaten(BrainContext ctx) {
        Enclosure space = ctx.percepts().enclosure();
        for (Being being : ctx.percepts().beings()) {
            if (!being.aggressive()) {
                continue;
            }
            Pos at = being.pos();
            boolean inTheDoorway = at.x() == door.x() && at.z() == door.z()
                    && at.y() >= door.y() - 1 && at.y() <= door.y() + 1;
            if (inTheDoorway || space.covers(at)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void cancel(BrainContext ctx) {
        // Nothing is held: the door swings on one tick or not at all.
    }

    /** Puts back how long a saved shut had waited on somebody coming through. */
    public ShutDoor resume(int waited) {
        this.waited = waited;
        return this;
    }

    public int waited() {
        return waited;
    }

    @Override
    public String describe() {
        return "shut the door at (" + door.x() + ", " + door.y() + ", " + door.z() + ")";
    }

    public Pos door() {
        return door;
    }
}
