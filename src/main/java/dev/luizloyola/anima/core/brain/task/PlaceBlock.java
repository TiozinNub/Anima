package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.BlockPlacer.Refusal;
import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.sense.Pos;
import org.jspecify.annotations.Nullable;

/**
 * Place one carried block — the thinnest wrapper over the
 * {@link dev.luizloyola.anima.core.brain.act.BlockPlacer} port. The block goes in after the body's
 * {@code handling.place_cooldown_ticks}, so a builder lays a block every half second rather than every
 * tick (Luiz, 2026-10-01); then placed → SUCCESS, refused → FAILED, keeping the placer's
 * {@link Refusal}. The ticks waited are saved, so a restart neither skips the wait nor repeats it.
 * The parent method owns the approach.
 */
public final class PlaceBlock implements PrimitiveTask {

    private final Placing placing;
    private int waited;
    /** Not saved: it is read the tick it fails, by the parent choosing what to do next. */
    private @Nullable Refusal refusal;

    public PlaceBlock(String itemId, int x, int y, int z) {
        this(Placing.of(itemId, new Pos(x, y, z)));
    }

    public PlaceBlock(Placing placing) {
        this(placing, 0);
    }

    public PlaceBlock(Placing placing, int waited) {
        this.placing = placing;
        this.waited = waited;
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        if (waited < ctx.profile().i(ProfileAspect.PLACE_COOLDOWN_TICKS)) {
            waited++;
            return TaskStatus.RUNNING;
        }
        refusal = ctx.actuators().placer().attempt(placing).orElse(null);
        return refusal == null ? TaskStatus.SUCCESS : TaskStatus.FAILED;
    }

    /** Why the placer said no, once it has; null before, or when it placed. */
    public @Nullable Refusal refusal() {
        return refusal;
    }

    @Override
    public void cancel(BrainContext ctx) {
        // Nothing in the world is held while it waits.
    }

    @Override
    public String describe() {
        return "place " + placing;
    }

    @Override
    public String failureDetail() {
        return "could not place " + placing + (refusal == null ? "" : ": " + refusal.why());
    }

    public Placing placing() {
        return placing;
    }

    /** Ticks already spent on this block. */
    public int waited() {
        return waited;
    }

    public String itemId() {
        return placing.itemId();
    }

    public Pos target() {
        return placing.cell();
    }

    /**
     * Whether a body is standing in {@code cell} — the check a {@code BlockProbe} cannot make,
     * because entities are not blocks. Spot-choosers ask this so a plan does not pick a cell that
     * the placer will refuse on arrival; the placer refuses anyway, since somebody can walk into
     * the spot while the settler is on their way to it.
     *
     * <p>Counts the ASKING body too, by its whole box: a settler standing 0.04 into the cell beside
     * its feet was handed that cell for a workbench, refused, and handed it again for seven minutes
     * (2026-10-01).
     */
    public static boolean occupied(BrainContext ctx, Pos cell) {
        if (ctx.percepts().footprint().contains(cell)) {
            return true;
        }
        return ctx.percepts().beings().stream().anyMatch(being ->
                being.pos().x() == cell.x() && being.pos().y() == cell.y()
                        && being.pos().z() == cell.z());
    }
}
