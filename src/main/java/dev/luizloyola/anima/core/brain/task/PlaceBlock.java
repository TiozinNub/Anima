package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * Place one carried block — the thinnest wrapper over the
 * {@link dev.luizloyola.anima.core.brain.act.BlockPlacer} port. One-shot, vanilla placing being
 * instantaneous: placed → SUCCESS, refused (nothing carried, cell occupied, out of reach) → FAILED.
 * The parent method owns the approach.
 */
public final class PlaceBlock implements PrimitiveTask {

    private final String itemId;
    private final Pos target;

    public PlaceBlock(String itemId, int x, int y, int z) {
        this.itemId = itemId;
        this.target = new Pos(x, y, z);
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        return ctx.actuators().placer().place(itemId, target)
                ? TaskStatus.SUCCESS : TaskStatus.FAILED;
    }

    @Override
    public void cancel(BrainContext ctx) {
        // One-shot: there is nothing mid-flight to release.
    }

    @Override
    public String describe() {
        return "place " + itemId + " at (" + target.x() + ", " + target.y() + ", " + target.z() + ")";
    }

    @Override
    public String failureDetail() {
        return "could not place " + itemId + " at (" + target.x() + ", " + target.y() + ", "
                + target.z() + ")";
    }

    public String itemId() {
        return itemId;
    }

    public Pos target() {
        return target;
    }
}
