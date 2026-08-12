package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.MoveFailure;
import dev.luizloyola.anima.core.brain.act.MoveState;
import dev.luizloyola.anima.core.brain.act.Mover;
import dev.luizloyola.anima.core.nav.Gait;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Test double for the {@link Mover} port. Deliberately DUMB about state — {@code moveTo} does not
 * flip it to MOVING, so a pre-set ARRIVED survives the issuing call: that is what makes the GoTo
 * first-tick test meaningful. Records the gait of the latest order ({@link #lastGait}), which the
 * 3-arg {@link Mover#moveTo(int, int, int)} always threads through as {@link Gait#WALK}.
 */
public final class FakeMover implements Mover {
    /** Ordered call log, e.g. {@code "moveTo(1, 2, 3)"}, {@code "stop"} — for sequencing asserts. */
    public final List<String> events = new ArrayList<>();
    public int moveToCalls;
    public int stopCalls;
    public int lastX;
    public int lastY;
    public int lastZ;
    public Gait lastGait;
    private MoveState state = MoveState.IDLE;
    private MoveFailure failure = MoveFailure.NONE;

    public void setState(MoveState state) {
        this.state = state;
    }

    /** Script what {@link #failure()} reports from now on — the legs' verdict, for a FAILED state. */
    public void setFailure(MoveFailure failure) {
        this.failure = failure;
    }

    @Override
    public void moveTo(int x, int y, int z, Gait gait) {
        moveToCalls++;
        lastX = x;
        lastY = y;
        lastZ = z;
        lastGait = gait;
        events.add("moveTo(" + x + ", " + y + ", " + z
                + (gait == Gait.WALK ? "" : ", " + gait.name().toLowerCase(Locale.ROOT)) + ")");
    }

    @Override
    public MoveState state() {
        return state;
    }

    @Override
    public MoveFailure failure() {
        return failure;
    }

    @Override
    public void stop() {
        stopCalls++;
        events.add("stop");
        state = MoveState.IDLE; // the port contract: state returns to IDLE
    }
}
