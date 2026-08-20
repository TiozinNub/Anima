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

    /**
     * Whether a body is standing in {@code cell} — the check a {@code BlockProbe} cannot make,
     * because entities are not blocks. Spot-choosers ask this so a plan does not pick a cell that
     * the placer will refuse on arrival; the placer refuses anyway, since somebody can walk into
     * the spot while the settler is on their way to it.
     *
     * <p>Counts the ASKING body too: a settler standing where they meant to build has to step out
     * first, and a chooser that ignored this would hand them their own feet.
     */
    public static boolean occupied(BrainContext ctx, Pos cell) {
        Pos feet = ctx.percepts().position();
        if (feet.x() == cell.x() && feet.y() == cell.y() && feet.z() == cell.z()) {
            return true;
        }
        return ctx.percepts().beings().stream().anyMatch(being ->
                being.pos().x() == cell.x() && being.pos().y() == cell.y()
                        && being.pos().z() == cell.z());
    }
}
