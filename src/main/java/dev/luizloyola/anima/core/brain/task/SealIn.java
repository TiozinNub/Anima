package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import java.util.Locale;

/**
 * The last of digging in: the lid is on, so the space is asked again. Until it answers, the body
 * reads the open pit it stood in a moment ago, and a zombie on the lid would count as on its side.
 */
public final class SealIn implements PrimitiveTask {

    private final Pos feet;

    public SealIn(int x, int y, int z) {
        this.feet = new Pos(x, y, z);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        ctx.percepts().recheckEnclosure();
        ctx.journal().record(Category.BRAIN, "dig in", String.format(Locale.ROOT,
                "dug in at (%d, %d, %d) until sunrise", feet.x(), feet.y(), feet.z()));
        return TaskStatus.SUCCESS;
    }

    @Override
    public void cancel(BrainContext ctx) {
    }

    @Override
    public String describe() {
        return "seal in at (" + feet.x() + ", " + feet.y() + ", " + feet.z() + ")";
    }

    public Pos feet() {
        return feet;
    }
}
