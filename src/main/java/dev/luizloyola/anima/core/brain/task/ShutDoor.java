package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * Shut one door, from where the body stands, in the one tick it is asked (shelter spec, rung 4).
 * A door already shut SUCCEEDS: somebody else got there, and the plan holds.
 *
 * <p>FAILS rather than shut a threat in: one standing in the doorway, or already on this side of
 * it, got there first, and the next leg is ordinary flight.
 */
public final class ShutDoor implements PrimitiveTask {

    private final Pos door;
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

    @Override
    public String describe() {
        return "shut the door at (" + door.x() + ", " + door.y() + ", " + door.z() + ")";
    }

    public Pos door() {
        return door;
    }
}
