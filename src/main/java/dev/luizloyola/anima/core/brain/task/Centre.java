package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Riser;

/**
 * Step to the middle of the cell the body stands in ({@link Riser#centre}). Nothing to save: a
 * restored one shuffles again from wherever the body is.
 */
public final class Centre implements PrimitiveTask {

    private boolean begun;

    @Override
    public TaskStatus tick(BrainContext ctx) {
        Riser riser = ctx.actuators().riser();
        if (!begun) {
            begun = true;
            return riser.centre() ? TaskStatus.RUNNING : TaskStatus.FAILED;
        }
        return switch (riser.state()) {
            case RISING -> TaskStatus.RUNNING;
            case RISEN -> TaskStatus.SUCCESS;
            case IDLE, FAILED -> TaskStatus.FAILED;
        };
    }

    @Override
    public void cancel(BrainContext ctx) {
        ctx.actuators().riser().abort();
    }

    @Override
    public String describe() {
        return "step to the middle of the cell";
    }
}
