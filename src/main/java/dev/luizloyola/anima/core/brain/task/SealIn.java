package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.NavGrid;
import java.util.Locale;

/**
 * The last of digging in: dug in only with the feet in the pocket and the lid over the head, and
 * then the space is asked again — until it answers the body reads the open pit it stood in a
 * moment ago, and a zombie on the lid would count as on its side. Otherwise it fails, saying why,
 * and the dig gives that pocket up.
 */
public final class SealIn implements PrimitiveTask {

    private final Pos feet;
    private String failure = "";

    public SealIn(int x, int y, int z) {
        this.feet = new Pos(x, y, z);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        NavGrid grid = ctx.percepts().terrain();
        String why = !here.equals(feet) ? String.format(Locale.ROOT, "standing at (%d, %d, %d), not in it",
                here.x(), here.y(), here.z())
                : grid.cell(feet.x(), feet.y() + 2, feet.z()) != CellType.GROUND ? "no lid over it"
                : null;
        if (why != null) {
            failure = String.format(Locale.ROOT, "could not dig in at (%d, %d, %d): %s", feet.x(), feet.y(),
                    feet.z(), why);
            ctx.journal().record(Category.BRAIN, "dig in", failure);
            return TaskStatus.FAILED;
        }
        ctx.percepts().recheckEnclosure();
        ctx.journal().record(Category.BRAIN, "dig in", String.format(Locale.ROOT,
                "dug in at (%d, %d, %d) until sunrise", feet.x(), feet.y(), feet.z()));
        return TaskStatus.SUCCESS;
    }

    @Override
    public String failureDetail() {
        return failure.isEmpty() ? describe() + " failed" : failure;
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
