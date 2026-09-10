package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.brain.act.LeanState;
import dev.luizloyola.anima.core.brain.act.Leaner;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.mod.body.AgentBody;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * The lean, on a live body: a crouched creep to a point {@link #MAX_LEAN} out from the middle of
 * the feet cell, held there, and the creep back. The body's pose follows {@link #crouching()} —
 * this only drives the legs, the way the riser's centring does, so it must tick after the
 * navigator, whose idle tick wipes every movement input.
 */
public final class AgentLeaner implements Leaner {

    /**
     * How close to the point counts as there. Small on purpose: the box keeps its overlap with
     * the block only while the feet stay short of 0.8 out, and the last stride and the slide
     * after it both add to wherever the creep stopped.
     */
    private static final double TOLERANCE = 0.05;
    /** A crouched creep. At full throttle one stride is more than the tolerance. */
    private static final float THROTTLE = 0.25F;
    private static final int TIMEOUT_TICKS = 60;

    private final AgentBody person;
    private LeanState state = LeanState.IDLE;
    private @Nullable BlockPos cell;
    private double x;
    private double z;
    private int ticks;

    public AgentLeaner(AgentBody person) {
        this.person = person;
    }

    @Override
    public boolean toward(double towardX, double towardZ) {
        if (state == LeanState.LEANING || state == LeanState.RELEASING || !person.onGround()) {
            return false;
        }
        BlockPos feet = person.blockPosition();
        double cx = feet.getX() + 0.5;
        double cz = feet.getZ() + 0.5;
        double dx = towardX - cx;
        double dz = towardZ - cz;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1.0E-6) {
            return false;
        }
        this.cell = feet;
        this.x = cx + dx / d * MAX_LEAN;
        this.z = cz + dz / d * MAX_LEAN;
        this.ticks = 0;
        this.state = LeanState.LEANING;
        log("lean", "toward " + point(this.x, this.z) + " from " + feet.toShortString());
        return true;
    }

    /** Whether the body is, or is being put, at the edge — what its pose follows. */
    public boolean crouching() {
        return state == LeanState.LEANING || state == LeanState.LEANT;
    }

    public void tick() {
        switch (state) {
            case LEANING -> {
                // The feet cell is no guide out here: past the middle of the edge the feet read
                // as the next cell over. Falling is the only way to leave the block.
                if (!person.onGround() || person.entity().getY() < cell.getY() - 0.01) {
                    fail("fell from " + cell.toShortString());
                } else if (creep(x, z)) {
                    person.stopMoving();
                    state = LeanState.LEANT;
                    log("leant", "at " + point(person.entity().getX(), person.entity().getZ())
                            + ", eyes " + Math.round(person.entity().getEyeHeight() * 100) / 100.0
                            + " up");
                } else if (++ticks > TIMEOUT_TICKS) {
                    fail("never reached the edge of " + cell.toShortString());
                }
            }
            case LEANT -> person.stopMoving();
            case RELEASING -> {
                if (creep(cell.getX() + 0.5, cell.getZ() + 0.5) || ++ticks > TIMEOUT_TICKS) {
                    person.stopMoving();
                    state = LeanState.IDLE;
                }
            }
            default -> {
            }
        }
    }

    private boolean creep(double toX, double toZ) {
        double dx = toX - person.entity().getX();
        double dz = toZ - person.entity().getZ();
        if (dx * dx + dz * dz <= TOLERANCE * TOLERANCE) {
            return true;
        }
        // Minecraft yaw: 0 faces +Z and increases clockwise — the riser's convention.
        person.driveForward((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F, THROTTLE);
        return false;
    }

    private void fail(String detail) {
        person.stopMoving();
        state = LeanState.FAILED;
        log("lean failed", detail);
    }

    @Override
    public LeanState state() {
        return state;
    }

    @Override
    public void release() {
        if (state == LeanState.LEANING || state == LeanState.LEANT) {
            state = LeanState.RELEASING;
            ticks = 0;
        } else if (state == LeanState.FAILED) {
            state = LeanState.IDLE;
        }
    }

    public void abort() {
        if (state != LeanState.IDLE) {
            person.stopMoving();
        }
        state = LeanState.IDLE;
    }

    private static String point(double x, double z) {
        return "(" + Math.round(x * 100) / 100.0 + ", " + Math.round(z * 100) / 100.0 + ")";
    }

    private void log(String event, String detail) {
        person.journal().record(Category.BODY, event, detail);
    }
}
