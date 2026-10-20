package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.ShelterNoter;
import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * A way under cover that fell through, left alone for {@link RunToShelter#NO_WAY_TICKS}: the next
 * root picks another, or waits the night out, rather than ask the same one again. Not saved, the
 * knowledge's convention for leaving a place alone.
 */
public final class GiveUpShelter implements PrimitiveTask {

    private final Pos at;

    public GiveUpShelter(int x, int y, int z) {
        this.at = new Pos(x, y, z);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        ctx.knowledge().avoid(PoiKind.SHELTER, at, ctx.percepts().time() + RunToShelter.NO_WAY_TICKS);
        for (PoiMemory memory : ctx.knowledge().sighted(PoiKind.SHELTER)) {
            if (memory.anchor().equals(at) && ShelterNoter.dug(memory)) {
                ctx.knowledge().forget(PoiKind.SHELTER, at);
                break;
            }
        }
        return TaskStatus.SUCCESS;
    }

    @Override
    public void cancel(BrainContext ctx) {
    }

    @Override
    public String describe() {
        return "leave the shelter at (" + at.x() + ", " + at.y() + ", " + at.z() + ") alone";
    }

    public Pos at() {
        return at;
    }
}
