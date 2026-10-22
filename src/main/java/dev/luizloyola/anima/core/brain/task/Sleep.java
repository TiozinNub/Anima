package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import java.util.Locale;

/**
 * Lie down in the bed at a cell, within reach, and stay there (sleep spec). RUNNING while asleep;
 * SUCCEEDS once something else got the body up — a hit, the bed broken, {@code brain wake} — and
 * FAILS when it could not lie down. Cancelled, it gets up: whoever outbid the night wakes it.
 *
 * <p>Holds nothing a restart would lose: vanilla saves a sleeper lying where it was, and lying down
 * again in the bed it is already in is a no-op.
 */
public final class Sleep implements PrimitiveTask {

    private final Pos bed;
    private boolean lying;

    public Sleep(int x, int y, int z) {
        this.bed = new Pos(x, y, z);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (!lying) {
            if (!ctx.actuators().bed().lieDown(bed)) {
                ctx.journal().record(Category.BRAIN, "sleep", String.format(Locale.ROOT,
                        "could not lie down in the bed at (%d, %d, %d)", bed.x(), bed.y(), bed.z()));
                return TaskStatus.FAILED;
            }
            lying = true;
            ctx.journal().record(Category.BRAIN, "sleep", String.format(Locale.ROOT,
                    "went to sleep in the bed at (%d, %d, %d)", bed.x(), bed.y(), bed.z()));
            return TaskStatus.RUNNING;
        }
        if (!ctx.actuators().bed().asleep()) {
            ctx.journal().record(Category.BRAIN, "sleep", "woke");
            return TaskStatus.SUCCESS;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void cancel(BrainContext ctx) {
        if (lying && ctx.actuators().bed().asleep()) {
            ctx.actuators().bed().getUp();
            ctx.journal().record(Category.BRAIN, "sleep", "got up");
        }
    }

    @Override
    public String describe() {
        return "sleep in the bed at (" + bed.x() + ", " + bed.y() + ", " + bed.z() + ")";
    }

    public Pos bed() {
        return bed;
    }
}
